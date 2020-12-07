package com.goticks

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.event.{Logging, LoggingAdapter}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory

import scala.concurrent.ExecutionContextExecutor

object FrontendMain extends App
    with Startup {
  val config = ConfigFactory.load("frontend") 

  implicit val system: ActorSystem = ActorSystem("frontend", config)

  val api: RestApi = new RestApi() {
    val log: LoggingAdapter = Logging(system.eventStream, "frontend")
    implicit val requestTimeout: Timeout = configuredRequestTimeout(config)
    implicit def executionContext: ExecutionContextExecutor = system.dispatcher
    
    def createPath(): String = {
      val config = ConfigFactory.load("frontend").getConfig("backend")
      val host = config.getString("host")
      val port = config.getInt("port")
      val protocol = config.getString("protocol")
      val systemName = config.getString("system")
      val actorName = config.getString("actor")
      s"$protocol://$systemName@$host:$port/$actorName"
    }

    def createBoxOffice(): ActorRef = {
      val path = createPath()
      system.actorOf(Props(new RemoteLookupProxy(path)), "lookupBoxOffice")
    }
  }

  startup(api.routes)
}
