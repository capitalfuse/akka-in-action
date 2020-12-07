package com.goticks

import akka.actor.{ActorRef, ActorSystem}
import akka.event.{Logging, LoggingAdapter}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory

import scala.concurrent.ExecutionContextExecutor

object FrontendRemoteDeployWatchMain extends App
    with Startup {
  val config = ConfigFactory.load("frontend-remote-deploy") 
  implicit val system: ActorSystem = ActorSystem("frontend", config)

  val api: RestApi = new RestApi() {
    val log: LoggingAdapter = Logging(system.eventStream, "frontend-remote-watch")
    implicit val requestTimeout: Timeout = configuredRequestTimeout(config)
    implicit def executionContext: ExecutionContextExecutor = system.dispatcher
    def createBoxOffice(): ActorRef = {
      system.actorOf(
        RemoteBoxOfficeForwarder.props, 
        RemoteBoxOfficeForwarder.name
      )
    }
  }

  startup(api.routes)
}
