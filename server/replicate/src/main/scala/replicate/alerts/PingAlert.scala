package replicate.alerts

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.NotUsed
import akka.actor.Status.Failure
import akka.actor.{Actor, ActorLogging, ActorRefFactory, Cancellable, Props}
import akka.stream._
import akka.stream.scaladsl._
import net.rfc1149.canape.Database
import play.api.libs.json.{JsObject, Json}
import replicate.messaging.Message
import replicate.messaging.Message.{Checkpoint, Severity}
import replicate.utils.Global.CheckpointAlerts._
import replicate.utils._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object PingAlert {

  private[this] lazy val checkpoints = Global.infos.get.checkpoints

  private[this] implicit val dispatcher = Global.system.dispatcher

  sealed trait State
  case object Starting extends State
  case object Inactive extends State
  case object Up extends State
  case object Notice extends State
  case object Warning extends State
  case object Critical extends State

  private class CheckpointWatcher(siteId: Int, database: Database) extends Actor with ActorLogging {

    import CheckpointWatcher._
    private[this] var currentNotification: Option[UUID] = None
    private[this] val checkpointInfo = checkpoints(siteId)
    private[this] var currentState: State = Starting
    private[this] var currentTimestamp: Long = -1
    private[this] var currentRecheckTimer: Option[Cancellable] = None

    private[this] def alert(severity: Severity.Severity, message: String, icon: String): Unit = {
      currentNotification.foreach(Alerts.cancelAlert)
      currentNotification = Some(Alerts.sendAlert(Message(Checkpoint, severity, title = checkpointInfo.name, body = message,
        url = Some(checkpointInfo.coordinates.url), icon = Some(icon))))
    }

    private[this] def alertDuration(severity: Severity.Severity, duration: FiniteDuration, icon: String): Unit =
      alert(severity, s"Site has been unresponsive for ${duration.toCoarsest}", icon)

    private[this] def scheduleRecheck(nextDuration: FiniteDuration, currentDuration: FiniteDuration): Unit = {
      currentRecheckTimer.foreach(_.cancel())
      currentRecheckTimer = Some(context.system.scheduler.scheduleOnce(nextDuration - currentDuration,
        self, Recheck(currentTimestamp)))
    }

    private[this] def checkTimestamp(ts: Long): Unit = {
      if (ts == -1 || ts >= currentTimestamp) {
        val oldState = currentState
        val elapsed = FiniteDuration(System.currentTimeMillis() - ts, TimeUnit.MILLISECONDS)
        currentState = if (ts == -1) Inactive else timestampToState(elapsed)
        currentTimestamp = ts
        (oldState, currentState) match {
          case (before, after) if before == after =>
          case (Starting, _) =>
          case (Inactive, Up) =>
            alert(Severity.Verbose, "Site went up for the first time", Glyphs.beatingHeart)
          case (_, Notice) =>
            alertDuration(Severity.Info, noticeDelay, Glyphs.brokenHeart)
          case (_, Warning) =>
            alertDuration(Severity.Warning, warningDelay, Glyphs.brokenHeart)
          case (_, Critical) =>
            alertDuration(Severity.Critical, criticalDelay, Glyphs.brokenHeart)
          case (_, Up) =>
            alert(Severity.Info, "Site is back up", Glyphs.growingHeart)
          case (_, Inactive) =>
            alert(Severity.Critical, "Site info has disappeared from database", Glyphs.skullAndCrossbones)
          case (_, _) =>
            log.error("Impossible checkpoint state transition from {} to {}", oldState, currentState)
        }
        currentState match {
          case Up => scheduleRecheck(noticeDelay, elapsed)
          case Notice => scheduleRecheck(warningDelay, elapsed)
          case Warning => scheduleRecheck(criticalDelay, elapsed)
          case _ =>
        }

      }
    }

    def receive = {
      case Initial =>
        sender() ! Ack

      case ts: Long =>
        checkTimestamp(ts)
        sender() ! Ack

      case Recheck(ts) =>
        if (ts == currentTimestamp)
          checkTimestamp(ts)

      case Complete =>
        log.error("CheckpointWatcher for site {} has terminated on complete", siteId)
        context.stop(self)

      case Failure(t) =>
        log.error(t, "CheckpointWatcher for site {} has terminated on failure", siteId)
        context.stop(self)
    }

  }

  private object CheckpointWatcher {
    case object Initial
    case object Ack
    case object Complete
    case class Recheck(ts: Long)

    private def timestampToState(elapsed: FiniteDuration): State =
      if (elapsed >= criticalDelay)
        Critical
      else if (elapsed >= warningDelay)
        Warning
      else if (elapsed >= noticeDelay)
        Notice
      else
        Up
  }

  /**
    * Return the timestamp corresponding to the last proof of live of a site.
    */
  private def lastPing(siteId: Int, database: Database)(implicit ec: ExecutionContext): Future[Option[Long]] =
    database.view[Int, JsObject]("admin", "alive",
      Seq("startkey" -> siteId.toString, "endkey" -> siteId.toString, "group" -> "true")).map { rows =>
      rows.headOption.map(row => (row._2 \ "max").as[Long])
    }

  private val siteRegex = """(checkpoints|ping)-(\d+)-.*""".r

  private def docToSite(js: JsObject): Option[Int] =
    siteRegex.findFirstMatchIn((js \ "_id").as[String]).map(_.group(2).toInt)

  private def throttleLast[T](duration: FiniteDuration): Flow[T, T, NotUsed] =
    Flow[T]
  // XXXXX This needs to wait for Akka 2.4.3 since in 2.4.2 throttle will
  // fail with a DivisionByZeroError
  // Flow[T].buffer(1, OverflowStrategy.dropHead).throttle(1, duration, 1, ThrottleMode.Shaping)

  private def docToMaxTimestamp(siteId: Int, database: Database): Flow[JsObject, Long, NotUsed] =
    Flow[JsObject].flatMapConcat { doc =>
      if ((doc \ "_deleted").asOpt[Boolean].contains(true)) {
        Source.fromFuture(lastPing(siteId, database).map(_.getOrElse(-1)))
      } else {
        (doc \ "times").asOpt[List[Long]] match {
          case None => Source.single((doc \ "time").as[Long])
          case Some(Nil) => Source.empty[Long]
          case Some(l) => Source.single(l.max)
        }
      }
    }

  private def pingAlerts(database: Database)(implicit context: ActorRefFactory) = RunnableGraph.fromGraph(GraphDSL.create() { implicit b =>
    import CheckpointWatcher._
    import GraphDSL.Implicits._

    val sites = checkpoints.size
    val in = database.changesSource(Map("filter" -> "admin/liveness-info", "include_docs" -> "true"))
    val partition = b.add(Partition[JsObject](sites + 1, docToSite(_) match {
      case Some(n) => n
      case None    => sites
    }))

    in ~> Flow[JsObject].map(js => (js \ "doc").as[JsObject]) ~> partition
    for (siteId <- 0 until sites) {
      val actorRef = context.actorOf(Props(new CheckpointWatcher(siteId, database)))
      partition.out(siteId) ~> Flow[JsObject].prepend(Source.single(Json.obj("_deleted" -> true))) ~>
        throttleLast[JsObject](noticeDelay / 2) ~>
        docToMaxTimestamp(siteId, database) ~> Sink.actorRefWithAck[Long](actorRef, Initial, Ack, Complete)
    }
    partition.out(sites) ~> Sink.ignore
    ClosedShape
  })

  def runPingAlerts(database: Database)(implicit context: ActorRefFactory) =
    pingAlerts(database).run()(ActorMaterializer.create(context))

}
