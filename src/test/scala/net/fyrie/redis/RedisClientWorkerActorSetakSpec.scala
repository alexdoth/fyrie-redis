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
import akka.dispatch.{ FutureTimeoutException, ActorCompletableFuture }
import akka.actor.{ Actor, ActorRef, IO }

class RedisClientWorkerActorSetakSpec extends SetakWordSpec {

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

  "RedisClientWorker" when {

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

class ActorThatReceivesOnlyIntMessage extends Actor {
  def receive = {
    case x: Int ⇒ // do nothing
  }
}