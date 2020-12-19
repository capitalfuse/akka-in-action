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
import scala.util.{Failure, Success}

class FanLogsApi(
  val logsDir: Path, 
  val maxLine: Int,
  val maxJsObject: Int
)(
  implicit val executionContext: ExecutionContext, 
  val materializer: Materializer
) extends EventMarshalling {
  def logFile(id: String): Path = logsDir.resolve(id)


  import akka.stream.scaladsl.{Broadcast, GraphDSL}
  import akka.stream.{FlowShape, Graph}

  val decider : Supervision.Decider = {
    case _: LogStreamProcessor.LogParseException => Supervision.Resume
    case _                    => Supervision.Stop
  }
  
  type FlowLike = Graph[FlowShape[Event, ByteString], NotUsed]

  def processStates(logId: String): FlowLike = {
    val jsFlow = LogJson.jsonOutFlow
    Flow.fromGraph(
      GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._
      // all logs, ok, warning, error, critical, so 5 outputs
      val bcast = builder.add(Broadcast[Event](5))
      val js = builder.add(jsFlow)

      val ok = Flow[Event].filter(_.state == Ok) 
      val warning = Flow[Event].filter(_.state == Warning)
      val error = Flow[Event].filter(_.state == Error)
      val critical = Flow[Event].filter(_.state == Critical)

      bcast ~> js.in
      bcast ~> ok       ~> jsFlow ~> logFileSink(logId, Ok)
      bcast ~> warning  ~> jsFlow ~> logFileSink(logId, Warning)
      bcast ~> error    ~> jsFlow ~> logFileSink(logId, Error)
      bcast ~> critical ~> jsFlow ~> logFileSink(logId, Critical)

      FlowShape(bcast.in, js.out)
    }).withAttributes(ActorAttributes.supervisionStrategy(decider))
  }

  def logFileSource(logId: String, state: State): Source[ByteString, Future[IOResult]] =
    FileIO.fromPath(logStateFile(logId, state))
  def logFileSink(logId: String, state: State): Sink[ByteString, Future[IOResult]] =
    FileIO.toPath(logStateFile(logId, state), Set(CREATE, WRITE, APPEND))
  def logStateFile(logId: String, state: State): Path =
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

  
  def logFileSource(logId: String): Source[ByteString, Future[IOResult]] = FileIO.fromPath(logFile(logId))
  def logFileSink(logId: String): Sink[ByteString, Future[IOResult]] =
    FileIO.toPath(logFile(logId), Set(CREATE, WRITE, APPEND))
  def routes: Route = 
    getLogsRoute ~  
    getLogNotOkRoute ~ 
    postRoute ~ 
    getLogStateRoute ~ 
    getRoute ~ 
    deleteRoute
  
  implicit val unmarshaller: FromEntityUnmarshaller[Source[Event, _]] = EventUnmarshaller.create(maxLine, maxJsObject)

  implicit val jsonStreamingSupport: JsonEntityStreamingSupport = EntityStreamingSupport.json()

  def postRoute: Route =
    pathPrefix("logs" / Segment) { logId =>
      pathEndOrSingleSlash {
        post {
          entity(asSourceOf[Event]) { src =>
            onComplete(

              src.via(processStates(logId))
                .toMat(logFileSink(logId))(Keep.right)
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

  implicit val marshaller: LEM = LogEntityMarshaller.create(maxJsObject)

  def getRoute: Route =
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


  val StateSegment: PathMatcher1[State] = Segment.flatMap {
    case State(state) => Some(state)
    case _ => None
  }

  def getLogStateRoute: Route =
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
    } finally {
      dirStream.close()
    }
  }


  def getLogsRoute: Route =
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



  def getLogNotOkRoute: Route =
    pathPrefix("logs" / Segment /"not-ok") { logId =>
      pathEndOrSingleSlash {
        get {
          extractRequest { req => 
            complete(Marshal(mergeNotOk(logId)).toResponseFor(req))
          }
        }
      }
    }


  def deleteRoute(): Route =
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
