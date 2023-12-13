package com.daml.network.validator.admin.api.client.commands

import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}
import org.apache.pekko.stream.Materializer
import cats.data.EitherT
import com.daml.network.admin.api.client.commands.{HttpClientBuilder, HttpCommand}
import com.daml.network.codegen.java.cn.cns as codegen
import com.daml.network.codegen.java.cn.wallet.subscriptions.SubscriptionRequest
import com.daml.network.http.v0.definitions
import com.daml.network.http.v0.external.cns as externalHttp
import com.daml.network.util.{Codec, TemplateJsonDecoder}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

object HttpCnsAppClient {

  abstract class ExternalBaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = externalHttp.CnsClient

    def createClient(host: String)(implicit
        httpClient: HttpRequest => Future[HttpResponse],
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client = externalHttp.CnsClient.httpClient(HttpClientBuilder().buildClient(), host)
  }

  case class CreateCnsEntryResponse(
      entryContextCid: codegen.CnsEntryContext.ContractId,
      subscriptionRequestCid: SubscriptionRequest.ContractId,
      name: String,
      url: String,
      description: String,
  )
  case class CreateCnsEntry(name: String, url: String, description: String)
      extends ExternalBaseCommand[
        externalHttp.CreateCnsEntryResponse,
        CreateCnsEntryResponse,
      ] {

    def submitRequest(client: externalHttp.CnsClient, headers: List[HttpHeader]): EitherT[
      Future,
      Either[Throwable, HttpResponse],
      externalHttp.CreateCnsEntryResponse,
    ] = {
      val request = definitions.CreateCnsEntryRequest(
        name,
        url,
        description,
      )
      client.createCnsEntry(request, headers = headers)
    }

    protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case externalHttp.CreateCnsEntryResponse.OK(res) =>
      for {
        entryContextCid <- Codec.decodeJavaContractId(codegen.CnsEntryContext.COMPANION)(
          res.entryContextCid
        )
        subscriptionRequestCid <- Codec.decodeJavaContractId(SubscriptionRequest.COMPANION)(
          res.subscriptionRequestCid
        )
        response = CreateCnsEntryResponse(
          entryContextCid,
          subscriptionRequestCid,
          res.name,
          res.url,
          res.description,
        )
      } yield response
    }
  }

  case class ListCnsEntries()
      extends ExternalBaseCommand[
        externalHttp.ListCnsEntriesResponse,
        definitions.ListCnsEntriesResponse,
      ] {

    override def submitRequest(
        client: externalHttp.CnsClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], externalHttp.ListCnsEntriesResponse] =
      client.listCnsEntries(headers = headers)

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[externalHttp.ListCnsEntriesResponse, Either[
      String,
      definitions.ListCnsEntriesResponse,
    ]] = { case externalHttp.ListCnsEntriesResponse.OK(res) =>
      Right(res)
    }
  }
}
