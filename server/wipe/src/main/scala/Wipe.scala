import akka.actor.ActorSystem
import net.rfc1149.canape._
import play.api.libs.json._
import scala.concurrent.duration._
import scopt.OptionParser

object Wipe extends App {

  import implicits._
  implicit val timeout: Duration = (5, SECONDS)

  private case class Options(login: String = null, password: String = null)

  private val parser = new OptionParser[Options]("wipe") {
    help("help") abbr("h") text ("show this help")
    arg[String]("<login>") text("login to access the master database") action { (x, c) => c.copy(login = x) }
    arg[String]("<password>") text("password to access the master database") action { (x, c) => c.copy(password = x) }
  }

  private val options = parser.parse(args, Options()) getOrElse { sys.exit(1) }

  private implicit val system = ActorSystem()
  private implicit val dispatcher = system.dispatcher

  val hubCouch = steenwerck.masterCouch(Some(options.login, options.password))

  val cfgDatabase = hubCouch.db("steenwerck-config")

  val newName = try {
    val oldNameDoc = cfgDatabase("configuration").execute()
    val JsString(oldName) = oldNameDoc \ "dbname"
    val newCount = oldName.substring(11).toInt
    val newName = "steenwerck-" + (newCount + 1)
    cfgDatabase.insert(oldNameDoc - "dbname" ++ Json.obj("dbname" -> newName)).execute()
    newName
  } catch {
    case _: Exception =>
      try {
        cfgDatabase.create()
      } catch {
        case e: Exception =>
          println("Cannot create configuration database: " + e)
      }
      cfgDatabase.insert(Json.obj("dbname" -> "steenwerck-0"), "configuration").execute()
      "steenwerck-0"
  }

  val hubDatabase = hubCouch.db(newName)
  try {
    println("Creating database " + newName)
    hubDatabase.create().execute()
    println("Copying security document")
    hubDatabase.insert(cfgDatabase("_security").execute(), "_security").execute()
    println("Inserting configuration document")
    hubDatabase.insert(Json.obj("dbname" -> newName, "tests_allowed" -> false), "configuration").execute()
    println("Generating random identification for couchsync")
    val key = new Array[Byte](256)
    scala.util.Random.nextBytes(key)
    val md = java.security.MessageDigest.getInstance("SHA-1")
    val ha = new sun.misc.BASE64Encoder().encode(md.digest(key))
    hubDatabase.insert(Json.obj("key" -> ha), "couchsync").execute()
    println("All things done")
  } catch {
      case Couch.StatusError(401, _, _) =>
        println("You are not authorized to perform this operation")
      case t: Exception =>
        println("Exception caught: " + t)
  }

  hubCouch.releaseExternalResources()
  system.shutdown()

}
