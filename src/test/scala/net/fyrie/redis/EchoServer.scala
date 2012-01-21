package net.fyrie.redis

import io.BufferedSource
import messages.Socket
import protocol.Constants
import java.net.{ Socket, ServerSocket, SocketException }
import java.io.{ BufferedReader, InputStreamReader, PrintStream }
import akka.actor.IO.{ SocketHandle, ServerHandle }
import akka.actor._

/**
 * Echo server based on the IOManager implementation (didn't want to rewrite the selector implementation)
 */
object EchoServer {
  var port = 59999
  var actor: ActorRef = _

  def apply(port: Int) { this.port = port }

  case object Shutdown

  class EchoActor extends Actor {
    var ioManager: ActorRef = _
    var clientSocket: SocketHandle = _
    var serverSocket: ServerHandle = _
    var source: UntypedChannel = _

    override def preStart() {
      ioManager = Actor.actorOf(new IOManager()).start()
      serverSocket = IO.listen(ioManager, "localhost", port, self)
    }

    def receive = {
      case IO.NewClient(server) ⇒ clientSocket = server.accept()

      case IO.Read(handle, bytes) ⇒
        if (handle != clientSocket) println("EchoActor: Received socket I don't know about")
        clientSocket.write(bytes)

      case IO.Closed(handle, cause) ⇒
      //println("IO.Closed " + handle)
      //if (handle == clientSocket) { println("Replying on IO.Closed"); source.tryTell() }

      case Shutdown                 ⇒ //source = self.sender
      case _                        ⇒ println("EchoActor: Unahdled message: ")
    }

    override def postStop() {
      // TODO: Wait for IO.Closed instead by receiving Shutdown request (need synchronous)
      serverSocket.close()
      Thread.sleep(500) // wait to make sure we close the socket

      ioManager.stop()
    }
  }

  def start() {
    actor = Actor.actorOf[EchoActor].start()
  }

  def stop() {
    //println("Echo stop")
    //(actor ? Shutdown).get
    //println("Echo has stopped")

    if (actor.isRunning) {
      actor.stop()
    }
  }
}

/**
 * Simple Echo server that replies what it receives
 * NOTE: The reply will include an EOL token ("\r\n") to comply with the Redis protocol
 * Big problem with readLine (i.e. in.next() blocking), thus unable to initiate a clean close from server side
 */
/*
object EchoServer {

  var port = 59999
  def apply(port: Int) { this.port = port }

  @volatile
  private var doRun = true

  @volatile
  private var doWait = false

  var server: ServerSocket = _

  private val thread = new Thread("echo-server") {
    var connectedClients: List[Socket] = Nil

    override def run() {
      while (doRun) {
        try {
          val s = server.accept()
          connectedClients ::= s
          val in = new BufferedSource(s.getInputStream).getLines()
          val out = new PrintStream(s.getOutputStream)

          if (!s.isClosed && !in.isEmpty) {
            val smth = in.next();
            //println("Server received: " + smth)
            out.print(smth + Constants.EOL.utf8String)
            out.flush()
            //s.close() //let the client close the connection?
          }
        } catch {
          // socket was externally closed, cleanup any connected clients
          case e: SocketException ⇒ cleanup()
          case e: Exception       ⇒ println("EchoServer: Unexcpected exception " + e)
        }

        if (doWait) {
          cleanup()
          while (doWait) Thread.sleep(200)
        }
      }
      cleanup()
    }

    def cleanup() {
      connectedClients foreach (s ⇒ try { s.close() } catch { case _ ⇒ })
    }
  }

  def start() {
    server = new ServerSocket(port)
    thread.start()
  }

  def suspend() {
    doWait = true
    server.close()
  }

  def resume() {
    server = new ServerSocket(port)
    doWait = false
  }

  def stop() {
    doWait = false
    doRun = false
    server.close()
    try {
      thread.join()
    } catch {
      case e: InterruptedException ⇒
    }
  }
} */ 