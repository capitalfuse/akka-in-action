package aia.stream

import akka.actor._
import akka.stream.{Materializer, _}
import akka.stream.scaladsl._
import akka.testkit._
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.nio.file.StandardOpenOption._
import java.nio.file._
import java.time.ZonedDateTime
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}


class LogStreamProcessorSpec extends TestKit(ActorSystem("test-filter"))
  with AnyWordSpecLike
  with Matchers
  with StopSystemAfterAll {

   val lines: String =
   "my-host-1  | web-app | ok       | 2015-08-12T12:12:00.127Z | 5 tickets sold to RHCP.||\n" +
   "my-host-2  | web-app | ok       | 2015-08-12T12:12:01.127Z | 3 tickets sold to RHCP.| | \n" +
   "my-host-3  | web-app | ok       | 2015-08-12T12:12:02.127Z | 1 tickets sold to RHCP.| | \n" +
   "my-host-3  | web-app | error    | 2015-08-12T12:12:03.127Z | exception occurred...| | \n"

  "A log stream processor" must {
    "be able to read a log file and parse events" in {
       implicit val materializer: Materializer = Materializer(ActorSystem("test-filter"))
       val path = Files.createTempFile("logs", ".txt")

       val bytes = lines.getBytes("UTF8")
       Files.write(path, bytes, StandardOpenOption.APPEND)

       import LogStreamProcessor._
       
       val source: Source[String, Future[IOResult]] = 
        logLines(path)
       
       val eventsSource: Source[Event, Future[IOResult]] = 
         errors(parseLogEvents(source))

       val events: Future[Seq[Event]] = 
         eventsSource.runWith(Sink.seq[Event])

       Await.result(events, 10 seconds) must be(
        Vector(Event("my-host-3", "web-app", Error, ZonedDateTime.parse("2015-08-12T12:12:03.127Z"), "exception occurred..." ))
       )
    }

    "be able to read it's own output" in {
      implicit val materializer: Materializer = Materializer(ActorSystem("test-filter"))
      val path = Files.createTempFile("logs", ".json")
      val json = 
      """
      [
      {
        "host": "my-host-1",
        "service": "web-app",
        "state": "ok",
        "time": "2015-08-12T12:12:00.127Z",
        "description": "5 tickets sold to RHCP."
      },
      {
        "host": "my-host-2",
        "service": "web-app",
        "state": "ok",
        "time": "2015-08-12T12:12:01.127Z",
        "description": "3 tickets sold to RHCP."
      },
      {
        "host": "my-host-3",
        "service": "web-app",
        "state": "ok",
        "time": "2015-08-12T12:12:02.127Z",
        "description": "1 tickets sold to RHCP."
      },
      {
        "host": "my-host-3",
        "service": "web-app",
        "state": "error",
        "time": "2015-08-12T12:12:03.127Z",
        "description": "exception occurred..."
      }       
      ]
      """

      val bytes = json.getBytes("UTF8")
      Files.write(path, bytes, StandardOpenOption.APPEND)

      import LogStreamProcessor._
      val source = jsonText(path)

      val results = errors(parseJsonEvents(source)).runWith(Sink.seq[Event])
      Await.result(results, 10 seconds) must be(
      Vector(Event("my-host-3", "web-app", Error, ZonedDateTime.parse("2015-08-12T12:12:03.127Z"), "exception occurred..." ))
      )
    }

    "be able to output JSON events to a Sink" in {
      implicit val materializer: Materializer = Materializer(ActorSystem("test-filter"))
      import system.dispatcher
      val pathLog = Files.createTempFile("logs", ".txt")
      val pathEvents = Files.createTempFile("events", ".json")

      val bytes = lines.getBytes("UTF8")
      Files.write(pathLog, bytes, StandardOpenOption.APPEND)

      import LogStreamProcessor._
      val source = logLines(pathLog)
       
      val results = convertToJsonBytes(errors(parseLogEvents(source)))
        .toMat(FileIO.toPath(pathEvents, Set(CREATE, WRITE, APPEND)))(Keep.right)
        .run
        .flatMap { _ =>
          parseJsonEvents(jsonText(pathEvents))
          .runWith(Sink.seq[Event])
        }
      Await.result(results, 10 seconds) must be(
        Vector(
          Event(
            "my-host-3", 
            "web-app", 
            Error, 
            ZonedDateTime.parse("2015-08-12T12:12:03.127Z"), 
            "exception occurred..." 
          )
        )
      )
    }

    "be able to rollup events" in {
      implicit val materializer: Materializer = Materializer(ActorSystem("test-filter"))

      val source = Source[Event](Vector(mkEvent, mkEvent, mkEvent, mkEvent))
       
      val results = LogStreamProcessor.rollup(source, e => e.state == Error, 3, 10 seconds)
        .runWith(Sink.seq[Seq[Event]])

      val grouped = Await.result(results, 10 seconds)
      grouped.head.size must be (3)
      grouped(1).size must be (1)
    }

    "be able to use implicit classes to extend DSL" in {
      implicit val materializer:Materializer = Materializer(ActorSystem("test-filter"))

      val source = Source[Event](Vector(mkEvent, mkEvent, mkEvent, mkEvent))
       
      val results = LogStreamProcessor.rollup(source, e => e.state == Error, 3, 10 seconds)
        .runWith(Sink.seq[Seq[Event]])

      val grouped = Await.result(results, 10 seconds)
      grouped.head.size must be (3)
      grouped(1).size must be (1)
    }
  }

  def mkEvent: Event = Event("host", "service", Error, ZonedDateTime.now(), "description")
}
