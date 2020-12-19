package aia.stream

import spray.json._

trait NotificationMarshalling extends EventMarshalling with DefaultJsonProtocol {
  implicit val summary: RootJsonFormat[Summary] = jsonFormat1(Summary)
}
