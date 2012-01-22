package net.fyrie.redis

import actors.RedisClientWorker
import messages._
import akka.actor.IO._
import akka.setak._
import core.TestActorRef
import akka.testkit.TestProbe
import java.net.{ InetSocketAddress, ConnectException }
import akka.util.{ ByteString, Duration }
import protocol.Constants
import net.fyrie.redis.types._
import akka.actor.{ ActorRef, IO }
import akka.dispatch.{ FutureTimeoutException, ActorCompletableFuture }

class RedisClientWorkerActorSpec extends SetakWordSpec {

  var ioManagerProbe: TestProbe = _
  var supervisorProbe: TestProbe = _
  var worker: TestActorRef = _
  val defaultConfig = RedisClientConfig()
  var socket: SocketHandle = _
  val defaultTimeout = Duration(2, "seconds")
  val defaultTimeoutNoMsg = Duration(1, "second")

  override def setUp() {
    ioManagerProbe = TestProbe()
    supervisorProbe = TestProbe()
    worker = testActorRefFactory.actorOf(new RedisClientWorker(ioManagerProbe.ref, "", 0, defaultConfig)).start()
    socket = SocketHandle(worker, ioManagerProbe.ref)
    supervisorProbe.ref link worker
  }

  override def tearDown() {
    ioManagerProbe.stopTestActor
    supervisorProbe.stopTestActor
    worker.stop()
  }

  "RedisClientWorker" should {
    "set the socket correctly" in {
      worker ! Socket(socket)
      whenStable {
        assert(worker.actorObject[RedisClientWorker].socket == socket)
      }
    }

    "close the socket and stop its self on a disconnect message" in {
      worker ! Socket(socket)
      worker ! Disconnect

      whenStable {
        assert(worker.isShutdown, "Worker was not shut down")
        ioManagerProbe.expectMsg(defaultTimeout, IO.Close(socket))
      }
    }

  }

  "RedisClientWorker" when {
    "connection is disconnected" should {

      "reconnect and send the new socket to supervisor when auto-reconnect is enabled" in {
        assert(defaultConfig.autoReconnect, "Default value of config autoReconnect is not true")

        worker ! Socket(socket)
        worker ! IO.Connected(socket)
        worker ! IO.Closed(socket, None)

        whenStable {
          val newSocket = worker.actorObject[RedisClientWorker].socket
          val address = new InetSocketAddress("", 0)
          ioManagerProbe.expectMsg(defaultTimeout, IO.Connect(newSocket, address))
          supervisorProbe.expectMsg(Socket(newSocket))
        }
      }

      "notify supervisor and not try to reconnect when auto-reconnect is disabled" in {
        val config = RedisClientConfig(autoReconnect = false)
        val newWorker = testActorRefFactory.actorOf(new RedisClientWorker(ioManagerProbe.ref, "", 0, config)).start()

        supervisorProbe.ref link newWorker
        newWorker ! Socket(socket)
        newWorker ! IO.Connected(socket)
        newWorker ! IO.Closed(socket, None)
        try {
          supervisorProbe.expectMsg(defaultTimeout, Disconnect)
          ioManagerProbe.expectNoMsg(defaultTimeoutNoMsg)
        } finally {
          newWorker.stop()
        }
      }

    }

    "has received data from the IO" should {

      val stringToken = "+"
      val errorToken = "-"
      val intToken = ":"
      val bulkToken = "$"
      val multiToken = "*"

      val msgString = "String"
      val msgError = "Error"
      val msgInt = "1234"

      implicit def str2ByteString(str: String) = ByteString(str)
      def send(msg: ByteString, worker: ActorRef = this.worker): ActorCompletableFuture = {
        val f = worker ? Run
        worker ! IO.Read(socket, msg ++ Constants.EOL)
        f
      }

      "forward correctly-formated redis type data from a single request to the caller" in {
        worker ! Socket(socket)
        worker ! IO.Connected(socket)

        val futureString = send(stringToken + msgString)
        val futureError = send(errorToken + msgError)
        val futureInt = send(intToken + msgInt)

        assert(futureString.get == RedisString(msgString))
        assert(futureError.get == RedisError(msgError))
        assert(futureInt.get == RedisInteger(msgInt.toLong))
      }

      "forward correctly-formated redis type data from a multi request to the caller" is (pending)

      // right now no the future expires and new data can not be received
      "complain if the data is ill-formated" in {
        worker ! Socket(socket)
        worker ! IO.Connected(socket)

        val illFormatedMsg = ByteString("missing token")

        // send the ill formatted message
        intercept[FutureTimeoutException] {
          send(illFormatedMsg).get // should this throw a RedisProtocolException?
        }
        // send the healthy message, we should resume normal operations
        val future = send(stringToken + msgString)
        assert(future.get == RedisString(msgString))
      }

      "notify supervisor about received data only if retry-on-reconnect is enabled" in {
        assert(defaultConfig.retryOnReconnect, "default value for retryOnReconnect is not true")
        worker ! Socket(socket)
        worker ! IO.Connected(socket)

        send(stringToken + msgString).get
        supervisorProbe.expectMsg(defaultTimeout, Received)

        val config = RedisClientConfig(retryOnReconnect = false)
        val newWorker = testActorRefFactory.actorOf(new RedisClientWorker(ioManagerProbe.ref, "", 0, config)).start()

        supervisorProbe.ref link newWorker
        newWorker ! Socket(socket)
        newWorker ! IO.Connected(socket)
        send(stringToken + msgString, newWorker).get
        try {
          ioManagerProbe.expectNoMsg(defaultTimeoutNoMsg)
        } finally {
          newWorker.stop()
        }
      }

      "invoke result callbacks with unique ids and correct timestamps" in {
        worker ! Socket(socket)
        worker ! IO.Connected(socket)

        val callback = new {
          var passed = true
          var prevId = 0L;
          var prevTime = 0L;
          // cant put assert here because its going to kill the actor(see test below)
          def apply(id: Long, time: Long) {
            passed = passed && id != prevId && time >= prevTime
            prevId = id; prevTime = time
          }
        }

        worker ! ResultCallback(callback.apply)
        for (_ ← 0 until 10) {
          send(stringToken + msgString).get
        }

        assert(callback.passed, "Callback parameters are not unique")
      }

      "invoke result callbacks and catch thrown exceptions associated with the callback" in {
        worker ! Socket(socket)
        worker ! IO.Connected(socket)

        // note callback function must be thread safe
        worker ! ResultCallback((id, time) ⇒ throw new Exception("Someone should catch me"))

        worker ! Run
        send(stringToken + msgString)

        whenStable {
          assert(worker.isRunning, "Worker stopped unexpectedly")
        }
      }
    }
  }
}