package net.fyrie.redis

import akka.setak._
import akka.setak.Commons._
import akka.setak.core.TestMessageEnvelopSequence
import akka.setak.core.TestMessageEnvelopSequence._
import core.TestActorRef
import actors.{ RedisClientWorker, RedisClientSession }
import messages._
import akka.actor._
import org.scalatest.BeforeAndAfterAll
import akka.util.ByteString
import protocol.Constants
import types.RedisString
import akka.dispatch.FutureTimeoutException
import java.net.{ ConnectException, InetSocketAddress }

class RedisClientSessionActorSpec extends SetakWordSpec {

  TestConfig.timeOutForMessages = 5000 // 5 seconds

  var ioManager: TestActorRef = _
  var session: TestActorRef = _
  var worker: TestActorRef = _
  val defaultConfig = RedisClientConfig()
  val localhostAddress = new InetSocketAddress("localhost", EchoServer.port)

  def init(config: RedisClientConfig = defaultConfig) {
    ioManager = actorOf(new IOManager()).start()
    session = actorOf(new RedisClientSession(ioManager, localhostAddress.getHostName, localhostAddress.getPort, config))
    worker = actorOf(new RedisClientWorker(ioManager, localhostAddress.getHostName, localhostAddress.getPort, config))
    // replace the worker with the TestActorRef worker
    session.actorObject[RedisClientSession].worker = worker
  }

  override def setUp() {
    EchoServer.start()
    init()
  }

  override def tearDown() {
    // make sure we exit properly
    try {
      session ! Disconnect
    } catch {
      case e: ActorInitializationException ⇒ // nothing to do actor is being shut down
    }

    ioManager.stop()
    EchoServer.stop()
  }

  "RedisClientSession" when {
    "connecting to a server" should {
      "establishing a connection if the server is online" in {
        val disconnectMsg = testMessageEnvelop(session, worker, Disconnect)
        val connectedMsg = testMessagePatternEnvelop(anyActorRef, worker, { case IO.Connected(handle) ⇒ })
        val socketMsg = testMessagePatternEnvelop(session, worker, { case Socket(socket) ⇒ })

        setSchedule(connectedMsg -> socketMsg -> disconnectMsg)
        //setSchedule(socketMsg -> connectedMsg -> disconnectMsg)

        session.start()
        afterMessage(connectedMsg) {
          session ! Disconnect
          whenStable {
            assert(isProcessed(socketMsg), "Worker did not receive the socket")
            assert(isProcessed(disconnectMsg), "Worker did not receive disconnect")
            assert(session.isShutdown, "Session is still running")
            assert(worker.isShutdown, "Worker is still running")
          }
        }
      }

      "keep trying to connect if the server is offline and connect once the server is online if " +
        "auto-reconnect is enabled" in {
          assert(defaultConfig.autoReconnect, "auto-reconnect is not enabled in default-config")
          val closedMsg = testMessagePatternEnvelop(anyActorRef, worker, { case IO.Closed(handle, cause) ⇒ })
          val connectedMsg = testMessagePatternEnvelop(anyActorRef, worker, { case IO.Connected(handle) ⇒ })
          // sometimes received sometimes not, not sure why
          val toSessionSocketMsg = testMessagePatternEnvelop(worker, session, { case Socket(handle) ⇒ })
          val toWorkerSocketMsg = testMessagePatternEnvelop(session, worker, { case Socket(handle) ⇒ })

          EchoServer.stop()
          session.start()
          // add afterMessage(closedMsg)
          Thread.sleep(1000) // wait for 1 second, simulating a server down-time
          EchoServer.start()
          // make sure we have connected to the server and that the worker has sent us the new socket
          afterAllMessages {}
        }

      "keep trying to connect if the server is offline and connect once the server is online if " +
        "auto-reconnect is enabled (out of order)" in {
          assert(defaultConfig.autoReconnect, "auto-reconnect is not enabled in default-config")
          val closedMsg = testMessagePatternEnvelop(anyActorRef, worker, { case IO.Closed(handle, cause) ⇒ })
          val connectedMsg = testMessagePatternEnvelop(anyActorRef, worker, { case IO.Connected(handle) ⇒ })
          // sometimes received sometimes not, not sure why
          val toSessionSocketMsg = testMessagePatternEnvelop(worker, session, { case Socket(handle) ⇒ })
          val toWorkerSocketMsg = testMessagePatternEnvelop(session, worker, { case Socket(handle) ⇒ })

          // possible interesting schedule
          setSchedule(closedMsg -> toWorkerSocketMsg)

          EchoServer.stop()
          session.start()
          // add afterMessage(closedMsg)
          Thread.sleep(1000) // wait for 1 second, simulating a server down-time
          EchoServer.start()
          // make sure we have connected to the server and that the worker has sent us the new socket
          afterAllMessages {}
        }

      "shutdown if the server refuses the connection and auto-reconnect is disabled" in {
        ioManager.stop(); session.stop(); worker.stop();
        init(RedisClientConfig(autoReconnect = false))

        val closedMsg = testMessagePatternEnvelop(anyActorRef, worker, { case IO.Closed(handle, cause) ⇒ })
        val disconnectedMsg = testMessageEnvelop(worker, session, Disconnect)

        // bring the server down
        EchoServer.stop()
        session.start()

        afterAllMessages { // wait for closed and disconnected
          assert(session.isShutdown, "Session is still running")
          assert(worker.isShutdown, "Worker is still running")
        }
      }

    }

    "connected to a server" should {
      "submit client requests and return the result to the caller" in {
        session.start()
        val f = session ? Request(ByteString("+some string") ++ Constants.EOL)
        assert(f.get == RedisString("some string"))
      }

      "submit client requests and return the result to the caller (out of order)" in {
        val runMsg = testMessageEnvelop(anyActorRef, worker, Run)
        val readMsg = testMessagePatternEnvelop(anyActorRef, worker, { case IO.Read(handle, bytes) ⇒ })
        // interesting out of order receive of messages (can happen if actors are remote)
        setSchedule(readMsg -> runMsg)
        session.start()
        val f = session ? Request(ByteString("+some string") ++ Constants.EOL)
        assert(f.get == RedisString("some string"))
        afterAllMessages {}
      }

      "retry and complete pending requests when the server goes offline if retry-on-reconnect is enabled" in {
        val closed = testMessagePatternEnvelop(anyActorRef, worker, { case IO.Closed(handle, Some(cause: ConnectException)) ⇒ })
        EchoServer.stop()
        session.start()
        afterMessage(closed) {}
        val f = session ? Request(ByteString("+some string") ++ Constants.EOL)
        EchoServer.start()
        assert(f.get == RedisString("some string"))
      }

      "ignore pending requests when the server goes offline if retry-on-reconnect is disabled" in {
        ioManager.stop(); session.stop(); worker.stop();
        init(RedisClientConfig(retryOnReconnect = false))

        val closed = testMessagePatternEnvelop(anyActorRef, worker, { case IO.Closed(handle, Some(cause: ConnectException)) ⇒ })
        val connected = testMessagePatternEnvelop(anyActorRef, worker, { case IO.Connected(handle) ⇒ })

        EchoServer.stop()
        assert(EchoServer.actor.isShutdown)
        session.start()
        afterMessage(closed) {}
        val ignoredRequest = session ? Request(ByteString("+some string") ++ Constants.EOL)
        EchoServer.start()
        intercept[FutureTimeoutException] {
          ignoredRequest.get
        }

        afterMessage(connected) {}
        val validRequest = session ? Request(ByteString("+some string 2") ++ Constants.EOL)
        assert(validRequest.get == RedisString("some string 2"))

      }
    }
  }
}