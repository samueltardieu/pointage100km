package replicate.alerts

import akka.actor.{Actor, ActorLogging, Props}
import com.typesafe.config.Config
import net.ceedubs.ficus.Ficus._
import net.rfc1149.canape.Database
import replicate.messaging.Message.{Severity, Administrativia}
import replicate.messaging._
import replicate.utils.Global

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

class Alerts(database: Database) extends Actor with ActorLogging {

  import Alerts._
  import Global.dispatcher

  override def preStart() = {
    deliverAlert(officers, Message(Administrativia, Severity.Info, "Alert service starting",
      s"Delivering alerts to ${officers.mkString(", ")}",
      Global.configuration.map(_.adminLink)))
    for (infos <- Global.infos)
      for (raceId <- infos.races.keys)
        context.actorOf(Props(new RaceRanking(database, raceId)), s"race-ranking-$raceId")
  }

  def receive = {
    case 'ignore =>
  }
}

object Alerts {

  import Global.dispatcher

  val officers: List[Messaging] = SystemLogger :: {
    val officersConfig = Global.replicateConfig.as[Map[String, Config]]("officers")
    officersConfig.toList.filterNot(_._2.as[Option[Boolean]]("disabled") == Some(true)).map { case (officerId, config) =>
        config.as[String]("type") match {
          case "pushbullet"     => new Pushbullet(officerId, config.as[String]("token"))
          case "freemobile-sms" => new FreeMobileSMS(officerId, config.as[String]("user"), config.as[String]("password"))
          case s                => sys.error(s"Unknown officer type $s for officer $officerId")
        }
    }
  }

  def deliverAlert(recipients: Seq[Messaging], message: Message): Future[Map[Messaging, String]] = {
    val deliveryResult = Future.sequence(recipients.map { recipient =>
      recipient.sendMessage(message).map(Success(_)).recover { case t => Failure(t) }.map(recipient -> _)
    })
    deliveryResult.onSuccess { case result =>
      val failures = result.collect { case (officer, Failure(_)) => officer }
      if (failures.nonEmpty) {
        val remaining = recipients.diff(failures)
        val errorRecipients = if (message.category == Administrativia || message.severity <= Severity.Warning) List(SystemLogger) else remaining
        if (remaining.nonEmpty) {
          deliverAlert(errorRecipients, Message(Administrativia, Severity.Info, "Delivery issues",
            s"Could not deliver alert to ${failures.mkString(", ")}", None))
        }
      }
    }
    deliveryResult.map(_.collect { case (officer, Success(Some(identifier))) => officer -> identifier }.toMap)
  }

}
