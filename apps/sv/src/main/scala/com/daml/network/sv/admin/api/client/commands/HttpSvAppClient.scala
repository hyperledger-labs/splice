package com.daml.network.sv.admin.api.client.commands

import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}
import akka.stream.{Materializer}
import cats.data.EitherT
import cats.syntax.traverse.*
import com.daml.network.admin.api.client.commands.HttpCommand
import com.daml.network.codegen.java.cc.coin.CoinRules
import com.daml.network.codegen.java.cn.svcrules.SvcRules
import com.daml.network.http.v0.{definitions, sv as http}
import com.daml.network.util.TemplateJsonDecoder
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.topology.{DomainId, PartyId}

import scala.concurrent.{ExecutionContext, Future}

object HttpSvAppClient {
  abstract class BaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = http.SvClient

    def createClient(host: String)(implicit
        httpClient: HttpRequest => Future[HttpResponse],
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      http.SvClient(host)
  }

  final case class DebugInfo(
      svUser: String,
      svParty: PartyId,
      svcParty: PartyId,
      coinRules: CoinRules.ContractId,
      svcRules: SvcRules.ContractId,
      ongoingValidatorOnboardings: Int,
  )

  // TODO(#2657) use secret
  case class OnboardValidator(candidate: PartyId, secret: String, headers: List[HttpHeader])
      extends BaseCommand[http.OnboardValidatorResponse, Unit] {

    override def submitRequest(
        client: Client
    ): EitherT[Future, Either[Throwable, HttpResponse], http.OnboardValidatorResponse] =
      client.onboardValidator(
        body = definitions.OnboardValidatorRequest(candidate.toProtoPrimitive, secret),
        headers = headers,
      )

    override def handleResponse(response: http.OnboardValidatorResponse)(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, Unit] = response match {
      case http.OnboardValidatorResponse.OK => Right(())
      case http.OnboardValidatorResponse.BadRequest(e) => Left(e)
      case http.OnboardValidatorResponse.Unauthorized(e) => Left(e)
    }
  }

  case class GetDebugInfo(headers: List[HttpHeader])
      extends BaseCommand[http.GetDebugInfoResponse, DebugInfo] {

    override def submitRequest(
        client: Client
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetDebugInfoResponse] =
      client.getDebugInfo(headers = headers)

    override def handleResponse(
        response: http.GetDebugInfoResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, DebugInfo] = {
      response match {
        case http.GetDebugInfoResponse.OK(
              definitions.GetDebugInfoResponse(
                svUser,
                svPartyId,
                svcPartyId,
                coinRulesContractId,
                svcRulesContractId,
                ongoingValidatorOnboardings,
              )
            ) =>
          for {
            svPartyId <- PartyId.fromProtoPrimitive(svPartyId)
            svcPartyId <- PartyId.fromProtoPrimitive(svcPartyId)
          } yield DebugInfo(
            svUser,
            svPartyId,
            svcPartyId,
            new CoinRules.ContractId(coinRulesContractId),
            new SvcRules.ContractId(svcRulesContractId),
            ongoingValidatorOnboardings,
          )
      }
    }
  }

  case class ListConnectedDomains(headers: List[HttpHeader])
      extends BaseCommand[http.ListConnectedDomainsResponse, Map[DomainAlias, DomainId]] {

    override def submitRequest(
        client: Client
    ) =
      client.listConnectedDomains(headers)

    override def handleResponse(
        response: http.ListConnectedDomainsResponse
    )(implicit decoder: TemplateJsonDecoder): Either[String, Map[DomainAlias, DomainId]] =
      response match {
        case http.ListConnectedDomainsResponse.OK(response) =>
          response.connectedDomains.toList
            .traverse { case (k, v) =>
              for {
                k <- DomainAlias.create(k)
                v <- DomainId.fromString(v)
              } yield (k, v)
            }
            .map(_.toMap)
      }
  }
}
