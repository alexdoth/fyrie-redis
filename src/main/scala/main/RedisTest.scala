import logger.Logger
import net.fyrie.redis._

object RedisTest extends App {
  val client = RedisClient()
  println("Sleeping")
  Thread.sleep(15000)
  /*
  client.set("key", "val")
  client.sync.get("key").parse[String]

  client.disconnect */
  //Thread.sleep(1000)
  //println("\n\n\n\n\n\n\n\n\n DOTGRAPH")
  //Logger.printGraph()
}