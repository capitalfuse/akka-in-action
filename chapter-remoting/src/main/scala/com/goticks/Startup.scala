package com.goticks

import scala.concurrent.{ExecutionContextExecutor, Future}
import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.server.Route
import scala.util.{Failure, Success}

trait Startup extends RequestTimeout {
  def startup(api: Route)(implicit system: ActorSystem): Any = {
    val host = system.settings.config.getString("http.host") // Gets the host and a port from the configuration
    val port = system.settings.config.getInt("http.port")
    startHttpServer(api, host, port)
  }

  def startHttpServer(api: Route, host: String, port: Int)
      (implicit system: ActorSystem): Any = {
    implicit val ec: ExecutionContextExecutor = system.dispatcher  //bindAndHandle requires an implicit ExecutionContext
    val bindingFuture: Future[ServerBinding] =
      Http().newServerAt(host, port).bind(api) //Starts the HTTP server
   
    val log = Logging(system.eventStream, "go-ticks")
    bindingFuture.map { serverBinding =>
      log.info(s"RestApi bound to ${serverBinding.localAddress} ")
    }.onComplete {
      case Success(_) => println("Success")
      case Failure(_) => println("Failed to bind to {}:{}!", host, port)
        system.terminate()
    }
  }
}
