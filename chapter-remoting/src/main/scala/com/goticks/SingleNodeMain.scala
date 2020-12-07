package com.goticks

import akka.actor.{ActorRef, ActorSystem}
import akka.event.{Logging, LoggingAdapter}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory

import scala.concurrent.ExecutionContextExecutor

object SingleNodeMain extends App
    with Startup {
  val config = ConfigFactory.load("singlenode") 
  implicit val system: ActorSystem = ActorSystem("singlenode", config)

  val api: RestApi = new RestApi() {
    val log: LoggingAdapter = Logging(system.eventStream, "go-ticks")
    implicit val requestTimeout: Timeout = configuredRequestTimeout(config)
    implicit def executionContext: ExecutionContextExecutor = system.dispatcher
    def createBoxOffice(): ActorRef = system.actorOf(BoxOffice.props, BoxOffice.name)
  }

  startup(api.routes)
}
