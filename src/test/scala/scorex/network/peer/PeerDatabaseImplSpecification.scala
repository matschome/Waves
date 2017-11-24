package scorex.network.peer

import java.net.{InetAddress, InetSocketAddress}
import java.nio.file.Files

import com.typesafe.config.ConfigFactory
import com.wavesplatform.TestDB
import com.wavesplatform.network.PeerDatabaseImpl
import com.wavesplatform.settings.NetworkSettings
import net.ceedubs.ficus.Ficus._
import org.scalatest.{Matchers, path}

class PeerDatabaseImplSpecification extends path.FreeSpecLike with Matchers with TestDB {

  private val config1 = ConfigFactory.parseString(
    """waves.network {
      |  known-peers = []
      |  peers-data-residence-time: 2s
      |}""".stripMargin).withFallback(ConfigFactory.load()).resolve()
  private val settings1 = config1.as[NetworkSettings]("waves.network")

  private val config2 = ConfigFactory.parseString(
    """waves.network {
      |  known-peers = []
      |  peers-data-residence-time: 10s
      |}""".stripMargin).withFallback(ConfigFactory.load()).resolve()
  private val settings2 = config2.as[NetworkSettings]("waves.network")

  val host1 = "1.1.1.1"
  val host2 = "2.2.2.2"
  val address1 = new InetSocketAddress(host1, 1)
  val address2 = new InetSocketAddress(host2, 2)

  "Peer database" - {
    val db1 = open()
    val db2 = open()
    val database = new PeerDatabaseImpl(db1, settings1)
    val database2 = new PeerDatabaseImpl(db2, settings2)

    "new peer should not appear in internal buffer but does not appear in database" in {
      database.knownPeers shouldBe empty
      database.addCandidate(address1)
      database.randomPeer(Set()) should contain(address1)
      database.knownPeers shouldBe empty
    }

    "new peer should move from internal buffer to database" in {
      database.knownPeers shouldBe empty
      database.addCandidate(address1)
      database.knownPeers shouldBe empty
      database.touch(address1)
      database.knownPeers.keys should contain(address1)
    }

    "peer should should became obsolete after time" in {
      database.touch(address1)
      database.knownPeers.keys should contain(address1)
      sleepLong()
      database.knownPeers shouldBe empty
      database.randomPeer(Set()) shouldBe empty
    }

    "touching peer prevent it from obsoleting" in {
      database.addCandidate(address1)
      database.touch(address1)
      sleepLong()
      database.touch(address1)
      sleepShort()
      database.knownPeers.keys should contain(address1)
    }

    "blacklisted peer should disappear from internal buffer and database" in {
      database.touch(address1)
      database.addCandidate(address2)
      database.knownPeers.keys should contain(address1)
      database.knownPeers.keys should not contain address2

      database.blacklist(InetAddress.getByName(host1), "")
      database.knownPeers.keys should not contain address1
      database.knownPeers should be(empty)

      database.randomPeer(Set()) should contain(address2)
      database.blacklist(InetAddress.getByName(host2), "")
      database.randomPeer(Set()) should not contain address2
      database.randomPeer(Set()) should be(empty)
    }

    "random peer should return peers from both from database and buffer" in {
      database2.touch(address1)
      database2.addCandidate(address2)
      val keys = database2.knownPeers.keys
      keys should contain(address1)
      keys should not contain address2

      val set = (1 to 10).flatMap(i => database2.randomPeer(Set())).toSet

      set should contain(address1)
      set should contain(address2)
    }

    "filters out excluded candidates" in {
      database.addCandidate(address1)
      database.addCandidate(address1)
      database.addCandidate(address2)

      database.randomPeer(Set(address1)) should contain(address2)
    }

    db1.close()
    db2.close()

    "if blacklisting is disable" - {
      "should clear blacklist at start" in {
        val path = Files.createTempDirectory("waves-tests")
        val prevConfig = ConfigFactory.parseString(
          s"""waves.network {
             |  known-peers = []
             |  peers-data-residence-time: 100s
             |}""".stripMargin).withFallback(ConfigFactory.load()).resolve()
        val prevSettings = prevConfig.as[NetworkSettings]("waves.network")
        val db1 = open(path)
        val prevDatabase = new PeerDatabaseImpl(db1, prevSettings)
        prevDatabase.blacklist(address1.getAddress, "I don't like it")
        db1.close()

        val db2 = open(path)
        val config = ConfigFactory.parseString(
          s"""waves.network {
             |  known-peers = []
             |  peers-data-residence-time: 100s
             |  enable-blacklisting = no
             |}""".stripMargin).withFallback(ConfigFactory.load()).resolve()
        val settings = config.as[NetworkSettings]("waves.network")
        val database = new PeerDatabaseImpl(db2, settings)

        database.blacklistedHosts shouldBe empty
        db2.close()
      }

      "should not add nodes to the blacklist" in {
        val config = ConfigFactory.parseString(
          s"""waves.network {
             |  known-peers = []
             |  peers-data-residence-time: 100s
             |  enable-blacklisting = no
             |}""".stripMargin).withFallback(ConfigFactory.load()).resolve()
        val settings = config.as[NetworkSettings]("waves.network")
        val db = open()
        val database = new PeerDatabaseImpl(db, settings)
        database.blacklist(address1.getAddress, "I don't like it")

        database.blacklistedHosts shouldBe empty
        db.close()
      }
    }
  }

  private def sleepLong(): Unit = Thread.sleep(2200)

  private def sleepShort(): Unit = Thread.sleep(200)

}
