import akka.dispatch.Future
import akka.event.LoggingAdapter
import akka.util.duration._
import net.liftweb.json._
import net.liftweb.json.Serialization.write
import net.rfc1149.canape._
import net.rfc1149.canape.helpers._
import net.rfc1149.canape.util._

import FutureUtils._
import Global._

trait ConflictsSolver {

  val log: LoggingAdapter

  private implicit val formats = DefaultFormats

  private def getTimes(from: mapObject, name: String) = from(name).extract[List[BigInt]]

  private def times(from: mapObject): List[BigInt] = getTimes(from, "times")

  private def deletedTimes(from: mapObject): List[BigInt] = getTimes(from, "deleted_times")

  private def mergeInto(ref: mapObject, conflicting: mapObject): mapObject = {
    val deleted = deletedTimes(ref).union(deletedTimes(conflicting)).distinct.sorted
    val remaining = times(ref).union(times(conflicting)).diff(deleted).distinct.sorted
    ref + ("deleted_times" -> toJValue(deleted)) + ("times" -> toJValue(remaining))
  }

  private def solveConflicts(db: Database, id: String, revs: List[String]) =
    for {
      docs <- getRevs(db, id, revs).toFuture
      result <- (solve(db, docs) { docs => docs.tail.foldLeft(docs.head)(mergeInto(_, _)) }).toFuture
    } yield {
      log.info("solved conflicts for " + id + " (" + revs.size + " documents)")
      result
    }

  def fixConflictingCheckpoints(db: Database) =
    db.view("bib_input", "conflicting-checkpoints").toFuture flatMap { r =>
      Future.sequence(for ((id, _, value) <- r.items[Nothing, List[String]])
		      yield solveConflicts(db, id, value))
      }

}