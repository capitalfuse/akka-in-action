package aia.stream

import spray.json._

trait MetricMarshalling extends EventMarshalling with DefaultJsonProtocol {
  implicit val metric: RootJsonFormat[Metric] = jsonFormat5(Metric)
}
