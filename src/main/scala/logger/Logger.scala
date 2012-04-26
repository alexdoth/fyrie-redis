package logger

import akka.actor.{ ActorRef, Actor }
import akka.event.{ EventHandler }
import logger.graph._
import logger.graph.Implicits._

sealed abstract class Source
class ActorSource(val actorName: String) extends Source {
  override def toString = {
    actorName
  }
}
class CallerSource(val className: String, val methodName: String) extends Source {
  override def toString = {
    className + "::" + methodName
  }
}

object Logger {

  // possible to have a list of listeners in the future
  var defaultListener = Actor.actorOf[VoidListener].start()

  sealed trait ActorEvent
  case class Started(actor: Actor) extends ActorEvent
  case class Stopped(actor: Actor) extends ActorEvent
  case class Sent(source: Source, message: Any, destination: Actor) extends ActorEvent
  case class Forwarded(source: Source, message: Any, destination: Actor) extends ActorEvent
  case class Received(message: Any, actor: Actor) extends ActorEvent
  case object Export

  def stopped(implicit actor: Actor) = defaultListener ! Stopped(actor)

  def started(implicit actor: Actor) = defaultListener ! Started(actor)

  def enable() {
    defaultListener.stop()
    defaultListener = Actor.actorOf[DefaultListener].start()
  }

  def sent(sender: Source, message: Any = "")(implicit actor: Actor) {
    defaultListener ! Sent(sender, message, actor)
  }

  def received(message: Any = "")(implicit actor: Actor) {
    defaultListener ! Received(message, actor)
  }

  def forwarded(sender: Source, message: Any = "")(implicit actor: Actor) {
    defaultListener ! Forwarded(sender, message, actor)
  }

  def printGraph() {
    defaultListener ! Export
  }

  def shutdown() {
    defaultListener.stop()
  }


  class VoidListener extends Actor {
    def receive = { case _ => }
  }


  class DefaultListener extends Actor {

    private val actorGraph = new Graph

    def receive = {

      case Started(actor) ⇒
        println("Started " + simpleName(actor))
        // add the actor to the graph
        actorGraph + ActorVertex(simpleName(actor))

      case Stopped(actor) ⇒
        println("Stopped " + simpleName(actor))

      case e: Sent      ⇒ processOutgoing(e)
      case e: Forwarded ⇒ processOutgoing(e)

      case Received(message, actor) ⇒
        println(simpleName(actor) + " received message " + simpleName(message))

      case Export ⇒ actorGraph.exportInDOT()
    }

    private def processOutgoing(event: ActorEvent) = {
      val (source, message, actor, isForward) = event match {
        case Sent(s, m, a)      ⇒ (s, m, a, false)
        case Forwarded(s, m, a) ⇒ (s, m, a, true)
        case _                  ⇒ throw new Exception("Unknown actor event in process outgoing")
      }
      println(source + (if (isForward) " forwarded " else " sent ") + "message " +
        simpleName(message) + " to " + simpleName(actor))

      var label = ""
      val destVertex = ActorVertex(simpleName(actor))
      val sourceVertex = source match {
        case c: CallerSource ⇒ label = c.methodName + "::"; ObjectVertex(c.className)
        case a: ActorSource  ⇒ ActorVertex(a.actorName)
      }

      val edge = if (isForward)
        (sourceVertex --> destVertex)(label + simpleName(message))
      else
        (sourceVertex ~> destVertex)(label + simpleName(message))
      actorGraph + sourceVertex + destVertex + edge
    }

    private def simpleName(instance: Any): String = {
      instance.asInstanceOf[AnyRef].getClass.getSimpleName
    }
  }
}