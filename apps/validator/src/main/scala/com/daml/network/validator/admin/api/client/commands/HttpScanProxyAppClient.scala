package com.daml.network.validator.admin.api.client.commands

import cats.data.EitherT
import com.daml.network.admin.api.client.commands.{HttpClientBuilder, HttpCommand}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}
import org.apache.pekko.stream.Materializer
import com.daml.network.http.v0.scanproxy as scanProxy
import com.daml.network.http.v0.scanproxy.{GetDsoPartyIdResponse, ScanproxyClient}
import com.daml.network.util.TemplateJsonDecoder
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.{ExecutionContext, Future}

object HttpScanProxyAppClient {

  abstract class ScanProxyBaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = scanProxy.ScanproxyClient

    def createClient(host: String)(implicit
        httpClient: HttpRequest => Future[HttpResponse],
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client = scanProxy.ScanproxyClient.httpClient(HttpClientBuilder().buildClient(), host)
  }

  case object GetDsoParty extends ScanProxyBaseCommand[scanProxy.GetDsoPartyIdResponse, PartyId] {
    override def submitRequest(
        client: ScanproxyClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], GetDsoPartyIdResponse] =
      client.getDsoPartyId(headers)

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[GetDsoPartyIdResponse, Either[String, PartyId]] = {
      case scanProxy.GetDsoPartyIdResponse.OK(response) =>
        Right(PartyId.tryFromProtoPrimitive(response.dsoPartyId))
    }
  }

}
