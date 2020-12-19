package aia.stream

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.stream.{Materializer, Supervision}
import com.typesafe.config.ConfigFactory

import java.nio.file.{FileSystems, Files}
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Success, Failure}

object LogsApp extends App {

  val config = ConfigFactory.load() 
  val host = config.getString("http.host")
  val port = config.getInt("http.port")

  val logsDir = {
    val dir = config.getString("log-stream-processor.logs-dir")
    Files.createDirectories(FileSystems.getDefault.getPath(dir))
  }
  val maxLine = config.getInt("log-stream-processor.max-line")

  implicit val system: ActorSystem = ActorSystem()
  implicit val ec: ExecutionContextExecutor = system.dispatcher
  
  val decider : Supervision.Decider = {
    case _: LogStreamProcessor.LogParseException => Supervision.Stop
    case _                    => Supervision.Stop
  }
  
  implicit val materializer: Materializer = Materializer(system)
  
  val api = new LogsApi(logsDir, maxLine).routes
 
  val bindingFuture: Future[ServerBinding] =
    Http().newServerAt(host, port).bind(api)
 
  val log =  Logging(system.eventStream, "logs")
  bindingFuture.map { serverBinding =>
    log.info(s"Bound to ${serverBinding.localAddress} ")
  } onComplete {
    case Success(_) => println("Completed")
    case Failure(_) => println("Failed to bind to {}:{}!", host, port)
      system.terminate()
  }
}
