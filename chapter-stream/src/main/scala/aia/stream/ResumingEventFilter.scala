package aia.stream

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.{IOResult, Materializer}
import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import spray.json._

import java.nio.file.StandardOpenOption._
import scala.concurrent.{ExecutionContextExecutor, Future}

object ResumingEventFilter extends App with EventMarshalling {
  val config = ConfigFactory.load() 
  val maxLine = config.getInt("log-stream-processor.max-line")
  
  if(args.length != 3) {
    System.err.println("Provide args: input-file output-file state")
    System.exit(1)
  }

  val inputFile = FileArg.shellExpanded(args(0))
  val outputFile = FileArg.shellExpanded(args(1))

  val filterState = args(2) match {
    case State(state) => state
    case unknown => 
      System.err.println(s"Unknown state $unknown, exiting.") 
      System.exit(1)
  }
  import akka.stream.scaladsl._

  val source: Source[ByteString, Future[IOResult]] = 
    FileIO.fromPath(inputFile)

  val sink: Sink[ByteString, Future[IOResult]] = 
    FileIO.toPath(outputFile, Set(CREATE, WRITE, APPEND))

  val frame: Flow[ByteString, String, NotUsed] =  
    Framing.delimiter(ByteString("\n"), maxLine) 
      .map(_.decodeString("UTF8"))


  import LogStreamProcessor.LogParseException
  import akka.stream.{ActorAttributes, Supervision}

  val decider : Supervision.Decider = {
    case _: LogParseException => Supervision.Resume
    case _                    => Supervision.Stop
  }

  val parse: Flow[String, Event, NotUsed] = 
    Flow[String].map(LogStreamProcessor.parseLineEx) 
      .collect { case Some(e) => e }
      .withAttributes(ActorAttributes.supervisionStrategy(decider))


  val filter: Flow[Event, Event, NotUsed] =   
    Flow[Event].filter(_.state == filterState)
  
  val serialize: Flow[Event, ByteString, NotUsed] =  
    Flow[Event].map(event => ByteString(event.toJson.compactPrint))

  implicit val system: ActorSystem = ActorSystem()
  implicit val ec: ExecutionContextExecutor = system.dispatcher


  val graphDecider : Supervision.Decider = { 
    case _: LogParseException => Supervision.Resume
    case _                    => Supervision.Stop
  }

  implicit val materializer: Materializer = Materializer(system)

  val composedFlow: Flow[ByteString, ByteString, NotUsed] =  
    frame.via(parse)
      .via(filter)
      .via(serialize)
      .withAttributes(ActorAttributes.supervisionStrategy(graphDecider))

  val runnableGraph: RunnableGraph[Future[IOResult]] = 
    source.via(composedFlow).toMat(sink)(Keep.right)

  runnableGraph.run().foreach { result =>
    println(s"Wrote ${result.count} bytes to '$outputFile'.")
    system.terminate()
  }  

}
