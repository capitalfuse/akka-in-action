package aia.stream

import spray.json._

import java.time.ZonedDateTime
import java.time.format.{DateTimeFormatter, DateTimeParseException}

trait EventMarshalling extends DefaultJsonProtocol {
  implicit val dateTimeFormat: JsonFormat[ZonedDateTime] = new JsonFormat[ZonedDateTime] {
    def write(dateTime: ZonedDateTime): JsString = JsString(dateTime.format(DateTimeFormatter.ISO_INSTANT))
    def read(value: JsValue): ZonedDateTime = value match {
      case JsString(str) => 
        try {
          ZonedDateTime.parse(str)
        } catch {
          case _: DateTimeParseException =>
            val msg = s"Could not deserialize $str to ZonedDateTime"
            deserializationError(msg)
        }
      case js => 
        val msg = s"Could not deserialize $js to ZonedDateTime."
        deserializationError(msg)
    }
  }

  implicit val stateFormat: JsonFormat[State] = new JsonFormat[State] {
    def write(state: State): JsString = JsString(State.norm(state))
    def read(value: JsValue): State = value match {
      case JsString("ok") => Ok
      case JsString("warning") => Warning
      case JsString("error") => Error
      case JsString("critical") => Critical
      case js => 
        val msg = s"Could not deserialize $js to State."
        deserializationError(msg)
    }
  }

  implicit val eventFormat: RootJsonFormat[Event] = jsonFormat7(Event)
  implicit val logIdFormat: RootJsonFormat[LogReceipt] = jsonFormat2(LogReceipt)
  implicit val errorFormat: RootJsonFormat[ParseError] = jsonFormat2(ParseError)
}
