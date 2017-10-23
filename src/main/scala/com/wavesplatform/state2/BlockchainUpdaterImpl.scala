package com.wavesplatform.state2

import java.util.concurrent.locks.ReentrantReadWriteLock

import cats._
import cats.implicits._
import com.wavesplatform.features.{BlockchainFeatures, FeatureProvider}
import com.wavesplatform.history.HistoryWriterImpl
import com.wavesplatform.metrics.{Instrumented, TxsInBlockchainStats}
import com.wavesplatform.settings.WavesSettings
import com.wavesplatform.state2.BlockchainUpdaterImpl._
import com.wavesplatform.state2.NgState._
import com.wavesplatform.state2.diffs.BlockDiffer
import com.wavesplatform.state2.reader.CompositeStateReader.composite
import com.wavesplatform.state2.reader.StateReader
import com.wavesplatform.utils.{UnsupportedFeature, forceStopApplication}
import kamon.Kamon
import kamon.metric.instrument.Time
import monix.execution.Scheduler.Implicits.global
import monix.reactive.subjects.ConcurrentSubject
import scorex.account.Address
import scorex.block.{Block, MicroBlock}
import scorex.transaction.ValidationError.{BlockAppendError, GenericError, MicroBlockAppendError}
import scorex.transaction._
import scorex.utils.ScorexLogging

class BlockchainUpdaterImpl private(persisted: StateWriter with StateReader,
                                    settings: WavesSettings,
                                    featureProvider: FeatureProvider,
                                    minimumInMemoryDiffSize: Int,
                                    historyWriter: HistoryWriterImpl,
                                    val synchronizationToken: ReentrantReadWriteLock) extends BlockchainUpdater with BlockchainDebugInfo with ScorexLogging with Instrumented {

  private val topMemoryDiff = Synchronized(Monoid[BlockDiff].empty)
  private val ngState = Synchronized(Option.empty[NgState])

  override val lastBlockId: ConcurrentSubject[ByteStr, ByteStr] = ConcurrentSubject.publish[ByteStr]

  private def currentPersistedBlocksState: StateReader = persisted

  def bestLiquidState: StateReader = read { implicit l => composite(currentPersistedBlocksState, () => ngState().map(_.bestLiquidDiff).orEmpty) }

  def historyReader: NgHistory with DebugNgHistory with FeatureProvider = read { implicit l => new NgHistoryReader(() => ngState(), historyWriter, settings.blockchainSettings.functionalitySettings) }

  private def displayFeatures(s: Set[Short]): String = s"FEATURE${if (s.size > 1) "S"} ${s.mkString(", ")} ${if (s.size > 1) "WERE" else "WAS"}"

  private def featuresApprovedWithBlock(block: Block): Set[Short] = {
    val height = historyWriter.height + 1

    if (height % settings.blockchainSettings.functionalitySettings.featureCheckBlocksPeriod == 0) {

      val approvedFeatures = historyWriter.featureVotesCountWithinActivationWindow(height)
        .map { case (feature, votes) => feature -> (if (block.featureVotes.contains(feature)) votes + 1 else votes) }
        .filter { case (_, votes) => votes >= settings.blockchainSettings.functionalitySettings.blocksForFeatureActivation }
        .keySet

      log.info(s"${displayFeatures(approvedFeatures)} APPROVED ON BLOCKCHAIN")

      val unimplementedApproved = approvedFeatures.diff(BlockchainFeatures.implemented)
      if (unimplementedApproved.nonEmpty) {
        log.warn(s"UNIMPLEMENTED ${displayFeatures(unimplementedApproved)} APPROVED ON BLOCKCHAIN")
        log.warn("PLEASE, UPDATE THE NODE AS SOON AS POSSIBLE")
        log.warn("OTHERWISE THE NODE WILL BE STOPPED OR FORKED UPON FEATURE ACTIVATION")
      }

      val activatedFeatures = historyWriter.activatedFeatures(height)

      val unimplementedActivated = activatedFeatures.diff(BlockchainFeatures.implemented)
      if (unimplementedActivated.nonEmpty) {
        log.error(s"UNIMPLEMENTED ${displayFeatures(unimplementedActivated)} ACTIVATED ON BLOCKCHAIN")
        log.error("PLEASE, UPDATE THE NODE IMMEDIATELY")
        if (settings.featuresSettings.autoShutdownOnUnsupportedFeature) {
          log.error("FOR THIS REASON THE NODE WAS STOPPED AUTOMATICALLY")
          forceStopApplication(UnsupportedFeature)
        }
        else log.error("OTHERWISE THE NODE WILL END UP ON A FORK")
      }

      approvedFeatures
    }
    else Set.empty
  }

  override def processBlock(block: Block): Either[ValidationError, DiscardedTransactions] = write { implicit l =>
    val height = historyWriter.height
    val notImplementedFeatures = featureProvider.activatedFeatures(height).diff(BlockchainFeatures.implemented)

    Either.cond(!settings.featuresSettings.autoShutdownOnUnsupportedFeature || notImplementedFeatures.isEmpty, (),
      GenericError(s"UNIMPLEMENTED ${displayFeatures(notImplementedFeatures)} ACTIVATED ON BLOCKCHAIN, UPDATE THE NODE IMMEDIATELY")).flatMap(_ =>
    (ngState() match {
      case None =>
        historyWriter.lastBlock match {
          case Some(lastInner) if lastInner.uniqueId != block.reference =>
            val logDetails = s"The referenced block(${block.reference})" +
              s" ${if (historyWriter.contains(block.reference)) "exits, it's not last persisted" else "doesn't exist"}"
            Left(BlockAppendError(s"References incorrect or non-existing block: " + logDetails, block))
          case _ =>
            BlockDiffer.fromBlock(settings.blockchainSettings.functionalitySettings, featureProvider, currentPersistedBlocksState, historyWriter.lastBlock, block)
              .map(d => Some((d, Seq.empty[Transaction])))
        }
      case Some(ng) if ng.base.reference == block.reference =>
        if (block.blockScore > ng.base.blockScore) {
          BlockDiffer.fromBlock(settings.blockchainSettings.functionalitySettings, featureProvider, currentPersistedBlocksState, historyWriter.lastBlock, block).map { diff =>
            log.trace(s"Better liquid block(score=${block.blockScore}) received and applied instead of existing(score=${ng.base.blockScore})")
            Some((diff, ng.transactions))
          }
        } else if (areVersionsOfSameBlock(block, ng.base)) {
          if (block.transactionData.size <= ng.transactions.size) {
            log.trace(s"Existing liquid block is better than new one, discarding $block")
            Right(None)
          } else {
            log.trace(s"New liquid block is better version of exsting, swapping")
            BlockDiffer.fromBlock(settings.blockchainSettings.functionalitySettings, featureProvider, currentPersistedBlocksState, historyWriter.lastBlock, block).map(d => Some((d, Seq.empty[Transaction])))
          }
        } else {
          Left(BlockAppendError(s"Competitor's liquid block $block(score=${block.blockScore}) is not better than existing (ng.base ${ng.base}(score=${ng.base.blockScore}))", block))
        }
      case Some(ng) if !ng.contains(block.reference) =>
        Left(BlockAppendError(s"References incorrect or non-existing block", block))
      case Some(ng) =>
        val referencedLiquidDiff = ng.diffs(block.reference)._1
        val (referencedForgedBlock, discarded) = measureSuccessful(forgeBlockTimeStats, ng.forgeBlock(block.reference)).get
        if (referencedForgedBlock.signatureValid) {
          if (discarded.nonEmpty) {
            microBlockForkStats.increment()
            microBlockForkHeightStats.record(discarded.size)
          }

          val diff = BlockDiffer.fromBlock(settings.blockchainSettings.functionalitySettings, historyReader,
            composite(currentPersistedBlocksState, () => referencedLiquidDiff.copy(heightDiff = 1)),
            Some(referencedForgedBlock), block)
            diff.foreach(persisted.append(_, block))

          diff.map { hardenedDiff =>
              TxsInBlockchainStats.record(ng.transactions.size)
              topMemoryDiff.transform(Monoid.combine(_, referencedLiquidDiff))
              Some((hardenedDiff, discarded.flatMap(_.transactionData)))
            }
        } else {
          val errorText = s"Forged block has invalid signature: base: ${ng.base}, micros: ${ng.micros}, requested reference: ${block.reference}"
          log.error(errorText)
          Left(BlockAppendError(errorText, block))
        }
    }).map { case Some((newBlockDiff, discarded)) =>
      val height = historyWriter.height + 1
      ngState.set(Some(NgState(block, newBlockDiff, 0L, featuresApprovedWithBlock(block))))
      historyReader.lastBlock.foreach(b =>lastBlockId.onNext(b.uniqueId))
      log.info(s"$block appended. New height: $height)")
      discarded
    case None => Seq.empty})
  }

  override def removeAfter(blockId: ByteStr): Either[ValidationError, DiscardedTransactions] = write { implicit l =>
    val ng = ngState()
    if (ng.exists(_.contains(blockId))) {
      log.trace("Resetting liquid block, no rollback is necessary")
      Right(Seq.empty)
    } else {
      val discardedNgTransactions = ng.fold(Seq.empty[Transaction])(_.bestLiquidBlock.transactionData)
      ngState.set(None)
      val recoveredTransactions = persisted.rollbackTo(blockId)

      Right(recoveredTransactions ++ discardedNgTransactions)
    }
  }

  override def processMicroBlock(microBlock: MicroBlock): Either[ValidationError, Unit] = write { implicit l =>
    ngState() match {
      case None =>
        Left(MicroBlockAppendError("No base block exists", microBlock))
      case Some(ng) if ng.base.signerData.generator.toAddress != microBlock.generator.toAddress =>
        Left(MicroBlockAppendError("Base block has been generated by another account", microBlock))
      case Some(ng) =>
        ng.micros.headOption match {
          case None if ng.base.uniqueId != microBlock.prevResBlockSig =>
            blockMicroForkStats.increment()
            Left(MicroBlockAppendError("It's first micro and it doesn't reference base block(which exists)", microBlock))
          case Some(prevMicro) if prevMicro.totalResBlockSig != microBlock.prevResBlockSig =>
            microMicroForkStats.increment()
            Left(MicroBlockAppendError("It doesn't reference last known microBlock(which exists)", microBlock))
          case _ =>
            for {
              _ <- Signed.validateSignatures(microBlock)
              diff <- BlockDiffer.fromMicroBlock(settings.blockchainSettings.functionalitySettings, historyReader, composite(currentPersistedBlocksState,
                () => ng.bestLiquidDiff.copy(snapshots = Map.empty)),
                historyWriter.lastBlockTimestamp, microBlock, ng.base.timestamp)
            } yield {
              log.info(s"$microBlock appended")
              ngState.set(Some(ng + (microBlock, Monoid.combine(ng.bestLiquidDiff, diff), System.currentTimeMillis())))
              lastBlockId.onNext(microBlock.totalResBlockSig)
            }
        }
    }
  }

  override def debugInfo(): StateDebugInfo = read { implicit l =>
    StateDebugInfo(persisted = HashInfo(height = persisted.height, hash = persisted.accountPortfoliosHash),
      top = ???,
      bottom = ???,
      microBaseHash = ngState().map(ng => Hash.accountPortfolios(ng.diffs(ng.base.uniqueId)._1.txsDiff.portfolios))
    )
  }

  override def persistedAccountPortfoliosHash(): Int = 0

  override def topDiff(): Map[Address, Portfolio] = Map.empty

  override def bottomDiff(): Map[Address, Portfolio] = Map.empty
}

object BlockchainUpdaterImpl {

  private val blockMicroForkStats = Kamon.metrics.counter("block-micro-fork")
  private val microMicroForkStats = Kamon.metrics.counter("micro-micro-fork")
  private val microBlockForkStats = Kamon.metrics.counter("micro-block-fork")
  private val microBlockForkHeightStats = Kamon.metrics.histogram("micro-block-fork-height")
  private val forgeBlockTimeStats = Kamon.metrics.histogram("forge-block-time", Time.Milliseconds)

  def apply(persistedState: StateWriter with StateReader,
            history: HistoryWriterImpl,
            settings: WavesSettings,
            minimumInMemoryDiffSize: Int,
            synchronizationToken: ReentrantReadWriteLock): BlockchainUpdaterImpl = {
    new BlockchainUpdaterImpl(persistedState, settings, history, minimumInMemoryDiffSize, history, synchronizationToken)
  }

  def ranges(from: Int, to: Int, by: Int): Stream[(Int, Int)] =
    if (from + by < to)
      (from, from + by) #:: ranges(from + by, to, by)
    else
      (from, to) #:: Stream.empty[(Int, Int)]

  def areVersionsOfSameBlock(b1: Block, b2: Block): Boolean =
    b1.signerData.generator == b2.signerData.generator &&
      b1.consensusData.baseTarget == b2.consensusData.baseTarget &&
      b1.reference == b2.reference &&
      b1.timestamp == b2.timestamp

}
