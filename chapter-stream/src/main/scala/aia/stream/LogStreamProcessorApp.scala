package aia.stream

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.stream.Materializer
import com.typesafe.config.ConfigFactory

import java.nio.file.{FileSystems, Files}
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

object LogStreamProcessorApp extends App {

  val config = ConfigFactory.load() 
  val host = config.getString("http.host")
  val port = config.getInt("http.port")

  val logsDir = {
    val dir = config.getString("log-stream-processor.logs-dir")
    Files.createDirectories(FileSystems.getDefault.getPath(dir))
  }

  val notificationsDir = {
    val dir = config.getString("log-stream-processor.notifications-dir")
    Files.createDirectories(FileSystems.getDefault.getPath(dir))
  }

  val metricsDir = {
    val dir = config.getString("log-stream-processor.metrics-dir")
    Files.createDirectories(FileSystems.getDefault.getPath(dir))
  }

  val maxLine = config.getInt("log-stream-processor.max-line")
  val maxJsObject = config.getInt("log-stream-processor.max-json-object")

  implicit val system: ActorSystem = ActorSystem()
  implicit val ec: ExecutionContextExecutor = system.dispatcher
  

  implicit val materializer: Materializer = Materializer(system)
  
  val api = new LogStreamProcessorApi(logsDir, notificationsDir, metricsDir, maxLine, maxJsObject).routes
 
  val bindingFuture: Future[ServerBinding] =
    Http().newServerAt(host, port).bind(api)
 
  val log =  Logging(system.eventStream, "processor")
  bindingFuture.map { serverBinding =>
    log.info(s"Bound to ${serverBinding.localAddress} ")
  } onComplete {
    case Success(_) => println("Completed")
    case Failure(_) => println("Failed to bind to {}:{}!", host, port)
      system.terminate()
  }
}
