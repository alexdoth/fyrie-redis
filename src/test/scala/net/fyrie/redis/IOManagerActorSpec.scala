package net.fyrie.redis

import akka.actor.IO._
import akka.actor.Actor
import akka.util.duration._
import akka.util.ByteString
import java.net.{ ConnectException, InetSocketAddress }
import akka.testkit.{ TestKitLight, TestProbe, TestKit }
import org.scalatest.{ BeforeAndAfterEach, BeforeAndAfterAll, WordSpec }

//import org.specs2.mutable._
//import akka.setack.SetackTest

import akka.actor.{ ActorRef, Actor, IO, IOManager }

// Use this trait if specs2 is used to avoid the implicit conversion of Duration (conflicting with TestKit)
trait DeactivatedTimeConversions extends org.specs2.time.TimeConversions {
  override def intToRichLong(v: Int) = super.intToRichLong(v)
  override def longToRichLong(v: Long) = super.longToRichLong(v)
}

class IOManagerActorSpec extends WordSpec with BeforeAndAfterEach with TestKit {

  import protocol.Constants._

  var ioManager: ActorRef = _
  val localhostAddress = new InetSocketAddress("localhost", EchoServer.port)
  val defaultTimeout = 3.seconds
  implicit var socket: SocketHandle = _

  override def beforeEach() {
    EchoServer.start()
    ioManager = Actor.actorOf(new IOManager).start()
    socket = SocketHandle(testActor, ioManager)
  }

  override def afterEach() {
    ioManager.stop()
    EchoServer.stop()
  }

  def connect(testKit: TestKitLight = this)(implicit socket: SocketHandle) {
    ioManager ! Connect(socket, localhostAddress)
    testKit.expectMsg(defaultTimeout, IO.Connected(socket))
  }

  def disconnect(testKit: TestKitLight = this)(implicit socket: SocketHandle) {
    ioManager ! IO.Close(socket)
    testKit.expectMsgPF(defaultTimeout) {
      case IO.Closed(handle, cause) if handle == socket ⇒
    }
  }

  def write(msg: String)(implicit socket: SocketHandle) {
    socket write (ByteString(msg) ++ EOL)
  }

  "IOManager" when {
    "used as a client" should {

      "establish and terminate a connection with an onlinse server" in {
        connect()
        disconnect()
      }

      "send data and receive the same data back from an online echo server" in {
        connect()
        val sentMsg = "Hello world"
        write(sentMsg)
        val replyMsg = expectMsgPF(defaultTimeout) {
          case IO.Read(handle, bytes) if handle == socket ⇒ bytes.utf8String
        }
        // we add the EOL to match the EchoServer reply behaviour
        assert(replyMsg == sentMsg + EOL.utf8String, "Received message differs from sent message")
        disconnect()
      }

      "send an exception cause when trying to connect to an offline server" in {
        EchoServer.stop() // bring the server down
        ioManager ! Connect(socket, localhostAddress)
        expectMsgPF(defaultTimeout) {
          case IO.Closed(handle, Some(cause: ConnectException)) if handle == socket ⇒
        }
      }

      "send a notification when a connected server disconnects" in {
        connect()
        EchoServer.stop()
        expectMsgPF(defaultTimeout) { case IO.Closed(handle, cause) if handle == socket ⇒ }
      }

      "clean up and close the connection when the socket owner actor dies" in {
        // cant stop testActor so we need to do this the ugly way
        val probe = TestProbe()
        val aSocket = SocketHandle(probe.ref, ioManager)
        try {
          connect(probe)(aSocket)
          probe.stopTestActor
          write("Some random data")(aSocket)
          // echo server should reply back, but ioManager should not die or send us any message back
          probe.expectNoMsg(defaultTimeout)
        } finally {
          if (probe.testActor.isRunning) probe.stopTestActor
        }
      }
    }

    /* IO Manager currently NOT being used as a server - omitting tests for now */
    /* Also EchoServer was converted to use IOManager */
    "used as a server" should {

      "accept new clients" is (pending)

      "reply to clients" is (pending)

      "disconnect cleints when server shutsdown" is (pending)

    }

    "used as a server and a cleint" should {

    }
  }
}