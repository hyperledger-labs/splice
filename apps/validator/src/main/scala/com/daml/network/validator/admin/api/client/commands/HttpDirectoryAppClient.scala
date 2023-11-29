package com.daml.network.validator.admin.api.client.commands

import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}
import org.apache.pekko.stream.Materializer
import cats.data.EitherT
import com.daml.network.admin.api.client.commands.{HttpClientBuilder, HttpCommand}
import com.daml.network.codegen.java.cn.directory as codegen
import com.daml.network.codegen.java.cn.wallet.subscriptions.SubscriptionRequest
import com.daml.network.http.v0.definitions
import com.daml.network.http.v0.external.directory as externalHttp
import com.daml.network.util.{Codec, TemplateJsonDecoder}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

object HttpDirectoryAppClient {

  abstract class ExternalBaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = externalHttp.DirectoryClient

    def createClient(host: String)(implicit
        httpClient: HttpRequest => Future[HttpResponse],
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client = externalHttp.DirectoryClient.httpClient(HttpClientBuilder().buildClient(), host)
  }

  case class CreateDirectoryEntryResponse(
      entryContextCid: codegen.DirectoryEntryContext.ContractId,
      subscriptionRequestCid: SubscriptionRequest.ContractId,
      name: String,
      url: String,
      description: String,
  )
  case class CreateDirectoryEntry(name: String, url: String, description: String)
      extends ExternalBaseCommand[
        externalHttp.CreateDirectoryEntryResponse,
        CreateDirectoryEntryResponse,
      ] {

    def submitRequest(client: externalHttp.DirectoryClient, headers: List[HttpHeader]): EitherT[
      Future,
      Either[Throwable, HttpResponse],
      externalHttp.CreateDirectoryEntryResponse,
    ] = {
      val request = definitions.CreateDirectoryEntryRequest(
        name,
        url,
        description,
      )
      client.createDirectoryEntry(request, headers = headers)
    }

    protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case externalHttp.CreateDirectoryEntryResponse.OK(res) =>
      for {
        entryContextCid <- Codec.decodeJavaContractId(codegen.DirectoryEntryContext.COMPANION)(
          res.entryContextCid
        )
        subscriptionRequestCid <- Codec.decodeJavaContractId(SubscriptionRequest.COMPANION)(
          res.subscriptionRequestCid
        )
        response = CreateDirectoryEntryResponse(
          entryContextCid,
          subscriptionRequestCid,
          res.name,
          res.url,
          res.description,
        )
      } yield response
    }
  }

  case class ListDirectoryEntries()
      extends ExternalBaseCommand[
        externalHttp.ListDirectoryEntriesResponse,
        definitions.ListDirectoryEntriesResponse,
      ] {

    override def submitRequest(
        client: externalHttp.DirectoryClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], externalHttp.ListDirectoryEntriesResponse] =
      client.listDirectoryEntries(headers = headers)

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[externalHttp.ListDirectoryEntriesResponse, Either[
      String,
      definitions.ListDirectoryEntriesResponse,
    ]] = { case externalHttp.ListDirectoryEntriesResponse.OK(res) =>
      Right(res)
    }
  }
}
