package aia.stream

import akka.NotUsed
import akka.stream.scaladsl.{BidiFlow, Flow, Framing, JsonFraming}
import akka.util.ByteString
import spray.json._

object LogJson extends EventMarshalling 
    with NotificationMarshalling 
    with MetricMarshalling {
  def textInFlow(maxLine: Int): Flow[ByteString, Event, NotUsed] = {
    Framing.delimiter(ByteString("\n"), maxLine)
    .map(_.decodeString("UTF8"))
    .map(LogStreamProcessor.parseLineEx)
    .collect { case Some(e) => e }
  }

  def jsonInFlow(maxJsonObject: Int): Flow[ByteString, Event, NotUsed] = {
    JsonFraming.objectScanner(maxJsonObject) 
      .map(_.decodeString("UTF8").parseJson.convertTo[Event])
  }

  def jsonFramed(maxJsonObject: Int): Flow[ByteString, ByteString, NotUsed] =
    JsonFraming.objectScanner(maxJsonObject) 

  val jsonOutFlow: Flow[Event, ByteString, NotUsed] = Flow[Event].map { event =>
    ByteString(event.toJson.compactPrint)
  }

  val notifyOutFlow: Flow[Summary, ByteString, NotUsed] = Flow[Summary].map { ws =>
    ByteString(ws.toJson.compactPrint)
  }

  val metricOutFlow: Flow[Metric, ByteString, NotUsed] = Flow[Metric].map { m =>
    ByteString(m.toJson.compactPrint)
  }

  val textOutFlow: Flow[Event, ByteString, NotUsed] = Flow[Event].map{ event =>
    ByteString(LogStreamProcessor.logLine(event))
  }

  def logToJson(maxLine: Int): BidiFlow[ByteString, Event, Event, ByteString, NotUsed] = {
    BidiFlow.fromFlows(textInFlow(maxLine), jsonOutFlow)
  }

  def jsonToLog(maxJsonObject: Int): BidiFlow[ByteString, Event, Event, ByteString, NotUsed] = {
    BidiFlow.fromFlows(jsonInFlow(maxJsonObject), textOutFlow)
  }

  def logToJsonFlow(maxLine: Int): Flow[ByteString, ByteString, NotUsed] = {
    logToJson(maxLine).join(Flow[Event])
  }

  def jsonToLogFlow(maxJsonObject: Int): Flow[ByteString, ByteString, NotUsed] = {
    jsonToLog(maxJsonObject).join(Flow[Event])
  }
}