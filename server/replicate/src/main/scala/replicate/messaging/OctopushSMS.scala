package replicate.messaging

import akka.actor.Status.Failure
import akka.actor.{Actor, ActorLogging}
import akka.pattern.pipe
import net.rfc1149.octopush.Octopush
import net.rfc1149.octopush.Octopush.{PremiumFrance, SMS, SMSResult}

class OctopushSMS(userLogin: String, apiKey: String, sender: Option[String]) extends Actor with ActorLogging with BalanceTracker {

  import OctopushSMS._

  private[this] implicit val dispatcher = context.system.dispatcher
  private[this] val octopush = new Octopush(userLogin, apiKey)(context.system)
  private[this] var currentStatus: Status = null
  val messageTitle = "Octopush"

  override def preStart =
    pipe(octopush.credit().transform(Balance, BalanceError)).to(self)

  def receive = {
    case (recipient: String, message: String) =>
      log.info(s"Sending SMS to $recipient: $message")
      val sms = SMS(smsRecipients = List(recipient), smsText = message, smsType = PremiumFrance, smsSender = sender)
      pipe(octopush.sms(sms).transform(SendOk(sms, _), SendError(sms, _))).to(self)

    case SendOk(sms, result) =>
      log.debug(s"SMS to ${sms.smsRecipients.head} sent succesfully: ${sms.smsText}")
      self ! Balance(result.balance)

    case Failure(SendError(sms, failure)) =>
      log.error(failure, s"SMS to ${sms.smsRecipients.head} (${sms.smsText}}) failed")

    case Balance(balance) =>
      trackBalance(balance)

    case Failure(BalanceError(failure)) =>
      balanceError(failure)
  }

}

object OctopushSMS {

  private case class SendOk(sms: SMS, result: SMSResult)
  private case class SendError(sms: SMS, failure: Throwable) extends Exception(failure)

}
