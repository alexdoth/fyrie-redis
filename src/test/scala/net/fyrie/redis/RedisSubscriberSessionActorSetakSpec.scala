package net.fyrie.redis

import actors.{ RedisClientWorker, RedisSubscriberSession }
import messages._
import org.scalatest.WordSpec
import akka.setak.SetakWordSpec
import akka.setak.core.TestActorRef
import akka.setak.core.TestMessageEnvelopSequence._
import akka.setak.Commons._
import akka.actor.{ IO, IOManager, ActorRef, Actor }
import org.scalatest.Assertions._
import pubsub._
import akka.testkit.TestProbe
import akka.util.Duration
import serialization.Parse
import java.net.ConnectException

class RedisSubscriberSessionActorSetakSpec extends SetakWordSpec {

  val port = 6379
  val host = "localhost"
  val defaultConfig = RedisClientConfig()
  val defaultTimeout = Duration(4, "seconds")

  var subscriberSession: TestActorRef = _
  var worker: TestActorRef = _
  var listener: TestProbe = _
  var ioManager: TestActorRef = _

  def init(port: Int = this.port,
           host: String = this.host,
           config: RedisClientConfig = defaultConfig) {
    listener = TestProbe()
    ioManager = actorOf(new IOManager()).start()
    subscriberSession = actorOf(new RedisSubscriberSession(listener.testActor)(ioManager, host, port, config))
    worker = actorOf(new RedisClientWorker(ioManager, host, port, config))
    subscriberSession.actorObject[RedisSubscriberSession].worker = worker
  }

  override def setUp() {
    init()
  }

  override def tearDown() {
    listener.stopTestActor
    if (subscriberSession.isRunning) {
      val disconnect = testMessageEnvelop(anyActorRef, subscriberSession, Disconnect)
      subscriberSession ! Disconnect
      afterMessage(disconnect) {}
    }
    ioManager.stop()
    worker.stop()
  }

  def subscribe(channels: String*) {
    subscriberSession ! Subscribe(channels)
    val results = listener.receiveWhile(defaultTimeout) {
      case Subscribed(channel, count) ⇒ Parse[String](channel)
    }
    results.map(c ⇒ assert(channels.contains(c)))
  }

  def psubscribe(channels: String*) {
    subscriberSession ! PSubscribe(channels)
    val results = listener.receiveWhile(defaultTimeout) {
      case PSubscribed(channel, count) ⇒ Parse[String](channel)
    }
    results.map(c ⇒ assert(channels.contains(c)))
  }

  def unsubscribe(channels: String*) {
    subscriberSession ! Unsubscribe(channels)
    val results = listener.receiveWhile(defaultTimeout) {
      case Unsubscribed(channel, count) ⇒ Parse[String](channel)
    }
    results.map(c ⇒ assert(channels.contains(c)))
  }

  def punsubscribe(channels: String*) {
    subscriberSession ! PUnsubscribe(channels)
    val results = listener.receiveWhile(defaultTimeout) {
      case PUnsubscribed(channel, count) ⇒ Parse[String](channel)
    }
    results.map(c ⇒ assert(channels.contains(c)))
  }

  "RedisSubscriberSession" when {
    "connecting to a server" should {

      implicit val implicitConfig = defaultConfig
      def offline(body: ⇒ Unit)(implicit config: RedisClientConfig) {
        tearDown()
        init(port = EchoServer.port, config = config)
        EchoServer.start()
        try {
          body
        } finally {
          EchoServer.stop()
        }
      }

      "establish a connection if the server is online" in offline {
        testMessagePatternEnvelop(anyActorRef, worker, { case IO.Connected(handle) ⇒ })
        testMessagePatternEnvelop(anyActorRef, worker, { case IO.Closed(handle, cause) ⇒ })

        subscriberSession.start()
        subscriberSession ! Disconnect
        afterAllMessages {
          assert(subscriberSession.isShutdown, "Session actor was not stopped")
          assert(worker.isShutdown, "Worker actor was not stopped")
        }
      }

      "keep trying to connect if the server is offline and connect once the server is online if " +
        "auto-reconnect is enabled" in offline {
          testMessagePatternEnvelop(anyActorRef, worker, { case IO.Closed(handle, cause) ⇒ })
          testMessagePatternEnvelop(anyActorRef, worker, { case IO.Connected(handle) ⇒ })
          testMessagePatternEnvelop(worker, subscriberSession, { case Socket(handle) ⇒ })
          testMessagePatternEnvelop(subscriberSession, worker, { case Socket(handle) ⇒ })

          EchoServer.stop()
          subscriberSession.start()
          // add afterMessage(closedMsg)
          Thread.sleep(1000) // wait for 1 second, simulating a server down-time
          EchoServer.start()
          // make sure we have connected to the server and that the worker has sent us the new socket
          afterAllMessages {}
        }(RedisClientConfig(autoReconnect = true))

      "keep trying to connect if the server is offline and connect once the server is online if " +
        "auto-reconnect is enabled (out of order)" in offline {
          val closed = testMessagePatternEnvelop(anyActorRef, worker, { case IO.Closed(handle, cause) ⇒ })
          testMessagePatternEnvelop(anyActorRef, worker, { case IO.Connected(handle) ⇒ })
          testMessagePatternEnvelop(worker, subscriberSession, { case Socket(handle) ⇒ })
          val socket = testMessagePatternEnvelop(subscriberSession, worker, { case Socket(handle) ⇒ })

          setSchedule(closed -> socket)
          EchoServer.stop()
          subscriberSession.start()
          // add afterMessage(closedMsg)
          Thread.sleep(1000) // wait for 1 second, simulating a server down-time
          EchoServer.start()
          // make sure we have connected to the server and that the worker has sent us the new socket
          afterAllMessages {}
        }(RedisClientConfig(autoReconnect = true))

    }
  }
}

/**
 * Dummy test actor that receives anything
 */
class TestListener extends Actor {
  def receive = {
    case _ ⇒
  }
}