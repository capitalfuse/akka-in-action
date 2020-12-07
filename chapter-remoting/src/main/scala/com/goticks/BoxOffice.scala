package com.goticks

import scala.concurrent.Future

import akka.actor._
import akka.util.Timeout

object BoxOffice {
  def props(implicit timeout: Timeout): Props = Props(new BoxOffice)
  def name = "boxOffice"

  case class CreateEvent(name: String, tickets: Int)
  case class GetEvent(name: String)
  case object GetEvents
  case class GetTickets(event: String, tickets: Int)
  case class CancelEvent(name: String)

  case class Event(name: String, tickets: Int)
  case class Events(events: Vector[Event])

  sealed trait EventResponse
  case class EventCreated(event: Event) extends EventResponse
  case object EventExists extends EventResponse
}

class BoxOffice(implicit timeout: Timeout) extends Actor {
  import BoxOffice._
  import context._

  def createTicketSeller(name: String): ActorRef =
    context.actorOf(TicketSeller.props(name), name)

  def receive: Receive = {
    case CreateEvent(name, tickets) =>
      def create(): Unit = {
        val eventTickets = createTicketSeller(name)
        val newTickets = (1 to tickets).map { ticketId =>
          TicketSeller.Ticket(ticketId)
        }.toVector
        eventTickets ! TicketSeller.Add(newTickets)
        sender() ! EventCreated(Event(name, tickets))
      }
      context.child(name).fold(create())(_ => sender() ! EventExists)

    case GetTickets(event, tickets) =>
      def notFound(): Unit = sender() ! TicketSeller.Tickets(event)
      def buy(child: ActorRef): Unit =
        child.forward(TicketSeller.Buy(tickets))

      context.child(event).fold(notFound())(buy)

    case GetEvent(event) =>
      def notFound(): Unit = sender() ! None
      def getEvent(child: ActorRef): Unit = child forward TicketSeller.GetEvent
      context.child(event).fold(notFound())(getEvent)

    case GetEvents =>
      import akka.pattern.{ ask, pipe }

      def getEvents = context.children.map { child =>
        self.ask(GetEvent(child.path.name)).mapTo[Option[Event]]
      }
      def convertToEvents(f: Future[Iterable[Option[Event]]]) =
        f.map(_.flatten).map(l=> Events(l.toVector))

      pipe(convertToEvents(Future.sequence(getEvents))) to sender()

    case CancelEvent(event) =>
      def notFound(): Unit = sender() ! None
      def cancelEvent(child: ActorRef): Unit = child forward TicketSeller.Cancel
      context.child(event).fold(notFound())(cancelEvent)
  }
}

