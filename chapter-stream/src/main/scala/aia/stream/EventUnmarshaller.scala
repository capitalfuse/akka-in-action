package aia.stream

import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, Unmarshaller}
import akka.http.scaladsl.unmarshalling.Unmarshaller._
import akka.stream.Materializer
import akka.stream.scaladsl.Source

import scala.concurrent.{ExecutionContext, Future}

object EventUnmarshaller extends EventMarshalling {
  val supported: Set[ContentTypeRange] = Set[ContentTypeRange](
    ContentTypes.`text/plain(UTF-8)`, 
    ContentTypes.`application/json`
  )

  def create(maxLine: Int, maxJsonObject: Int): FromEntityUnmarshaller[Source[Event, _]] = {
    new Unmarshaller[HttpEntity, Source[Event, _]] {
      def apply(entity: HttpEntity)(implicit ec: ExecutionContext, 
        materializer: Materializer): Future[Source[Event, _]] = {

        val future = entity.contentType match {
          case ContentTypes.`text/plain(UTF-8)` => 
            Future.successful(LogJson.textInFlow(maxLine))
          case ContentTypes.`application/json` =>
            Future.successful(LogJson.jsonInFlow(maxJsonObject))
          case _ =>
            Future.failed(
              UnsupportedContentTypeException(supported)
            )
        }
        future.map(flow => entity.dataBytes.via(flow))(ec)
      } 
    }.forContentTypes(supported.toList:_*)
  }
}

