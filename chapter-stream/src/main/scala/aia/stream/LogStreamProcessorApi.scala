package aia.stream

import aia.stream.LogEntityMarshaller.LEM
import akka.http.scaladsl.common.{EntityStreamingSupport, JsonEntityStreamingSupport}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import akka.stream.scaladsl.{FileIO, Flow, Keep, Sink, Source}
import akka.stream.{ActorAttributes, IOResult, Materializer, Supervision}
import akka.util.ByteString
import akka.{Done, NotUsed}

import java.nio.file.StandardOpenOption._
import java.nio.file.{Files, Path}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}

class LogStreamProcessorApi(
  val logsDir: Path, 
  val notificationsDir: Path, 
  val metricsDir: Path, 
  val maxLine: Int,
  val maxJsObject: Int
)(
  implicit val executionContext: ExecutionContext, 
  val materializer: Materializer
) extends EventMarshalling {
  def logFile(id: String): Path = logsDir.resolve(id)
  
  // this is just for testing, 
  // in a realistice application this would be a Sink to some other
  // service, for instance a Kafka Sink, or an email service.
  val notificationSink: Sink[ByteString, Future[IOResult]] = FileIO.toPath(notificationsDir.resolve("notifications.json"), Set(CREATE, WRITE, APPEND))
  val metricsSink: Sink[ByteString, Future[IOResult]] = FileIO.toPath(metricsDir.resolve("metrics.json"), Set(CREATE, WRITE, APPEND))


  import akka.stream.scaladsl.{Broadcast, GraphDSL, MergePreferred}
  import akka.stream.{FlowShape, Graph, OverflowStrategy}

  val decider : Supervision.Decider = {
    case _: LogStreamProcessor.LogParseException => Supervision.Resume
    case _                    => Supervision.Stop
  }
  
  type FlowLike = Graph[FlowShape[Event, ByteString], NotUsed]

  def processEvents(logId: String): FlowLike = { 
    val jsFlow = LogJson.jsonOutFlow
    val notifyOutFlow = LogJson.notifyOutFlow
    val metricOutFlow = LogJson.metricOutFlow

    Flow.fromGraph( 
      GraphDSL.create() { implicit builder => 
      import GraphDSL.Implicits._ 

      val nrWarnings = 100
      val nrErrors = 10
      val archBufSize = 100000
      val warnBufSize = 100
      val errBufSize = 1000
      val errDuration = 10 seconds
      val warnDuration = 1 minute

      val toMetric = Flow[Event].collect {
        case Event(_, service, _, time, _, Some(tag), Some(metric)) =>
          Metric(service, time, metric, tag) 
      }



      val recordDrift = Flow[Metric]
        .expand { metric => 
          Iterator.from(0).map(d => metric.copy(drift = d))
        }



      val bcast = builder.add(Broadcast[Event](5))  
      val wbcast = builder.add(Broadcast[Event](2))
      val ebcast = builder.add(Broadcast[Event](2))
      val cbcast = builder.add(Broadcast[Event](2))
      val okcast = builder.add(Broadcast[Event](2))

      val mergeNotify = builder.add(MergePreferred[Summary](2))
      val archive = builder.add(jsFlow)



      val toNot = Flow[Event].map(e=> Summary(Vector(e)))


      val ok = Flow[Event].filter(_.state == Ok) 
      val warning = Flow[Event].filter(_.state == Warning)
      val error = Flow[Event].filter(_.state == Error)
      val critical = Flow[Event].filter(_.state == Critical)


      def rollup(nr: Int, duration: FiniteDuration) =
        Flow[Event].groupedWithin(nr, duration)
          .map(events => Summary(events.toVector))

      val rollupErr = rollup(nrErrors, errDuration)    
      val rollupWarn = rollup(nrWarnings, warnDuration)    



      val archBuf = Flow[Event]
        .buffer(archBufSize, OverflowStrategy.fail)

      val warnBuf = Flow[Event]
        .buffer(warnBufSize, OverflowStrategy.dropHead)
      
      val errBuf = Flow[Event]
        .buffer(errBufSize, OverflowStrategy.backpressure)

      val metricBuf = Flow[Event]
        .buffer(errBufSize, OverflowStrategy.dropHead)



      bcast ~> archBuf  ~> archive.in
      bcast ~> ok       ~> okcast
      bcast ~> warning  ~> wbcast
      bcast ~> error    ~> ebcast
      bcast ~> critical ~> cbcast

      okcast ~> jsFlow ~> logFileSink(logId, Ok) 
      okcast ~> metricBuf ~> 
        toMetric ~> recordDrift ~> metricOutFlow ~> metricsSink

      cbcast ~> jsFlow ~> logFileSink(logId, Critical)
      cbcast ~> toNot ~> mergeNotify.preferred

      ebcast ~> jsFlow ~> logFileSink(logId, Error)
      ebcast ~> errBuf ~> rollupErr ~> mergeNotify.in(0)

      wbcast ~> jsFlow ~> logFileSink(logId, Warning)
      wbcast ~> warnBuf ~> rollupWarn ~> mergeNotify.in(1)
      
      mergeNotify ~> notifyOutFlow ~> notificationSink 

      FlowShape(bcast.in, archive.out)

    }).withAttributes(ActorAttributes.supervisionStrategy(decider))
  }



  private def logFileSource(logId: String, state: State): Source[ByteString, Future[IOResult]] =
    FileIO.fromPath(logStateFile(logId, state))
  private def logFileSink(logId: String, state: State): Sink[ByteString, Future[IOResult]] =
    FileIO.toPath(logStateFile(logId, state), Set(CREATE, WRITE, APPEND))
  private def logStateFile(logId: String, state: State): Path =
    logFile(s"$logId-${State.norm(state)}")  


  import akka.stream.SourceShape
  import akka.stream.scaladsl.{GraphDSL, Merge}

  def mergeNotOk(logId: String): Source[ByteString, NotUsed] = {
    val warning = logFileSource(logId, Warning)
      .via(LogJson.jsonFramed(maxJsObject))
    val error = logFileSource(logId, Error)
      .via(LogJson.jsonFramed(maxJsObject))
    val critical = logFileSource(logId, Critical)
      .via(LogJson.jsonFramed(maxJsObject))

    Source.fromGraph(
      GraphDSL.create() { implicit builder => 
      import GraphDSL.Implicits._

      val warningShape = builder.add(warning) 
      val errorShape = builder.add(error) 
      val criticalShape = builder.add(critical)
      val merge = builder.add(Merge[ByteString](3)) 

      warningShape  ~> merge
      errorShape    ~> merge
      criticalShape ~> merge
      SourceShape(merge.out)
    })
  }

  
  private def logFileSource(logId: String): Source[ByteString, Future[IOResult]] = FileIO.fromPath(logFile(logId))
  private def archiveSink(logId: String): Sink[ByteString, Future[IOResult]] = FileIO.toPath(logFile(logId), Set(CREATE, WRITE, APPEND))
  private def logFileSink(logId: String): Sink[ByteString, Future[IOResult]] = FileIO.toPath(logFile(logId), Set(CREATE, WRITE, APPEND))
  def routes: Route =
    getLogsRoute ~  
    getLogNotOkRoute ~ 
    postRoute ~ 
    getLogStateRoute ~ 
    getRoute ~ 
    deleteRoute
  
  private implicit val unmarshaller: FromEntityUnmarshaller[Source[Event, _]] = EventUnmarshaller.create(maxLine, maxJsObject)
  
  private implicit val jsonStreamingSupport: JsonEntityStreamingSupport = EntityStreamingSupport.json()

  private def postRoute: Route =
    pathPrefix("logs" / Segment) { logId =>
      pathEndOrSingleSlash {
        post {
          entity(asSourceOf[Event]) { src =>
            onComplete(

              src.via(processEvents(logId))
                .toMat(archiveSink(logId))(Keep.right)
                .run()

            ) {
            // Handling Future result omitted here, done the same as before.
              case Success(IOResult(count, Success(Done))) =>
                complete((StatusCodes.OK, LogReceipt(logId, count)))
              case Success(IOResult(_, Failure(e))) =>
                complete((
                  StatusCodes.BadRequest, 
                  ParseError(logId, e.getMessage)
                ))
              case Failure(e) =>
                complete((
                  StatusCodes.BadRequest, 
                  ParseError(logId, e.getMessage)
                ))
            }
          }
        } 
      }
    }

  private implicit val marshaller: LEM = LogEntityMarshaller.create(maxJsObject)

  private def getRoute: Route =
    pathPrefix("logs" / Segment) { logId =>
      pathEndOrSingleSlash {
        get { 
          extractRequest { req =>
            if(Files.exists(logFile(logId))) {
              val src = logFileSource(logId) 
              complete(Marshal(src).toResponseFor(req))
            } else {
              complete(StatusCodes.NotFound)
            }
          }
        }
      }
    }


  private val StateSegment: PathMatcher1[State] = Segment.flatMap {
    case State(state) => Some(state)
    case _ => None
  }

  private def getLogStateRoute: Route =
    pathPrefix("logs" / Segment / StateSegment) { (logId, state) =>
      pathEndOrSingleSlash {
        get { 
          extractRequest { req =>
            if(Files.exists(logStateFile(logId, state))) {
              val src = logFileSource(logId, state) 
              complete(Marshal(src).toResponseFor(req))
            } else {
              complete(StatusCodes.NotFound)
            }
          }
        }
      }
    }

  

  import akka.stream.scaladsl.Merge

  def mergeSources[E](
    sources: Vector[Source[E, _]]
  ): Option[Source[E, _]] = {
    if(sources.isEmpty) None
    else if(sources.size == 1) Some(sources(0))
    else {
      Some(Source.combine(
        sources(0), 
        sources(1), 
        sources.drop(2) : _*
      )(Merge(_)))
    }
  } 


  def getFileSources[T](dir: Path): Vector[Source[ByteString, Future[IOResult]]] = {
    val dirStream = Files.newDirectoryStream(dir)
    try {
      import scala.jdk.CollectionConverters._
      val paths = dirStream.iterator.asScala.toVector
      paths.map(path => FileIO.fromPath(path))
    } finally dirStream.close()
  }


  private def getLogsRoute: Route =
    pathPrefix("logs") {
      pathEndOrSingleSlash {
        get {
          extractRequest { req => 
            val sources = getFileSources(logsDir).map { src =>
              src.via(LogJson.jsonFramed(maxJsObject))
            }            
            mergeSources(sources) match {
              case Some(src) => 
                complete(Marshal(src).toResponseFor(req))
              case None => 
                complete(StatusCodes.NotFound)
            }
          }
        }
      }
    }



  private def getLogNotOkRoute: Route =
    pathPrefix("logs" / Segment /"not-ok") { logId =>
      pathEndOrSingleSlash {
        get {
          extractRequest { req => 
            complete(Marshal(mergeNotOk(logId)).toResponseFor(req))
          }
        }
      }
    } 


  private def deleteRoute(): Route =
    pathPrefix("logs" / Segment) { logId =>
      pathEndOrSingleSlash {
        delete {
          if(Files.deleteIfExists(logFile(logId))) {
            complete(StatusCodes.OK)
          } else {
            complete(StatusCodes.NotFound)
          }
        }
      }
    }
}
