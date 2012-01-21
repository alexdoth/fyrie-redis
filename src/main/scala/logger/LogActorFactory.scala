package akka.logger

/**
 * Created by IntelliJ IDEA.
 * User: TeOS
 * Date: 11/20/11
 * Time: 5:44 PM
 * To change this template use File | Settings | File Templates.
 */

object LogActorFactory {
  import akka.japi.Creator
  import akka.actor.{ ActorInitializationException, ActorRef, Actor }
  import akka.util.ReflectiveAccess
  import java.lang.reflect.InvocationTargetException

  def actorOf[T <: Actor: Manifest]: ActorRef = actorOf(manifest[T].erasure.asInstanceOf[Class[_ <: Actor]])

  def actorOf(clazz: Class[_ <: Actor]): ActorRef = new LogActorRef(() ⇒ {
    import ReflectiveAccess.{ createInstance, noParams, noArgs }
    createInstance[Actor](clazz.asInstanceOf[Class[_]], noParams, noArgs) match {
      case Right(actor) ⇒ actor
      case Left(exception) ⇒
        val cause = exception match {
          case i: InvocationTargetException ⇒ i.getTargetException
          case _                            ⇒ exception
        }

        throw new ActorInitializationException(
          "Could not instantiate Actor of " + clazz +
            "\nMake sure Actor is NOT defined inside a class/trait," +
            "\nif so put it outside the class/trait, f.e. in a companion object," +
            "\nOR try to change: 'actorOf[MyActor]' to 'actorOf(new MyActor)'.")
    }

  }, None)

  def actorOf(factory: ⇒ Actor): ActorRef = new LogActorRef(() ⇒ factory, None)

  def actorOf(creator: Creator[Actor]): ActorRef = new LogActorRef(() ⇒ creator.create, None)

}