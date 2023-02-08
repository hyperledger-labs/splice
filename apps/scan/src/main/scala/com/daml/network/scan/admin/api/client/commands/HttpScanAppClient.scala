package com.daml.network.scan.admin.api.client.commands

import cats.syntax.either.*
import cats.data.EitherT
import cats.syntax.traverse.*
import com.daml.network.codegen.java.cc.round.{IssuingMiningRound, OpenMiningRound}
import com.daml.network.codegen.java.cc.coin.{CoinRules, FeaturedAppRight}
import com.daml.network.codegen.java.cc.{coin as coinCodegen, round as roundCodegen}
import com.daml.network.util.Contract
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.daml.network.admin.api.client.commands.HttpCommand
import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}
import akka.stream.Materializer
import com.daml.network.http.v0.scan as http
import com.daml.network.util.TemplateJsonDecoder

import scala.concurrent.{ExecutionContext, Future}

object HttpScanAppClient {

  abstract class BaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = http.ScanClient

    def createClient(host: String)(implicit
        httpClient: HttpRequest => Future[HttpResponse],
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      http.ScanClient(host)
  }

  case class GetSvcPartyId(headers: List[HttpHeader])
      extends BaseCommand[http.GetSvcPartyIdResponse, PartyId] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetSvcPartyIdResponse] =
      client.getSvcPartyId(headers)

    override def handleResponse(response: http.GetSvcPartyIdResponse)(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, PartyId] =
      response match {
        case http.GetSvcPartyIdResponse.OK(response) =>
          // TODO(#2739)
          PartyId.fromProtoPrimitive(response.svcPartyId)
      }
  }

  case class TransferContext(
      coinRules: Option[
        Contract[coinCodegen.CoinRules.ContractId, coinCodegen.CoinRules]
      ],
      latestOpenMiningRound: Contract[
        roundCodegen.OpenMiningRound.ContractId,
        roundCodegen.OpenMiningRound,
      ],
      openMiningRounds: Seq[
        Contract[roundCodegen.OpenMiningRound.ContractId, roundCodegen.OpenMiningRound]
      ],
  )

  case object GetTransferContext
      extends BaseCommand[http.GetTransferContextResponse, TransferContext] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetTransferContextResponse] =
      client.getTransferContext(headers)

    override def handleResponse(
        response: http.GetTransferContextResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, TransferContext] =
      response match {
        case http.GetTransferContextResponse.OK(response) =>
          for {
            coinRules <- response.coinRules
              .traverse(coinRules => Contract.fromJson(coinCodegen.CoinRules.COMPANION)(coinRules))
              .leftMap(_.toString)

            openMiningRounds <- response.openMiningRounds
              .traverse(round => Contract.fromJson(roundCodegen.OpenMiningRound.COMPANION)(round))
              .leftMap(_.toString)

            latestOpenMiningRound <- Contract
              .fromJson(roundCodegen.OpenMiningRound.COMPANION)(
                response.latestOpenMiningRound
              )
              .leftMap(_.toString)
          } yield TransferContext(
            coinRules,
            latestOpenMiningRound,
            openMiningRounds,
          )
      }
  }

  case object GetLatestOpenAndIssuingMiningRounds
      extends BaseCommand[
        http.GetLatestOpenAndIssuingMiningRoundsResponse,
        (
            Contract[OpenMiningRound.ContractId, OpenMiningRound],
            Seq[Contract[IssuingMiningRound.ContractId, IssuingMiningRound]],
        ),
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetLatestOpenAndIssuingMiningRoundsResponse] =
      client.getLatestOpenAndIssuingMiningRounds(headers)

    override def handleResponse(
        response: http.GetLatestOpenAndIssuingMiningRoundsResponse
    )(implicit decoder: TemplateJsonDecoder): Either[
      String,
      (
          Contract[OpenMiningRound.ContractId, OpenMiningRound],
          Seq[Contract[IssuingMiningRound.ContractId, IssuingMiningRound]],
      ),
    ] =
      response match {
        case http.GetLatestOpenAndIssuingMiningRoundsResponse.OK(response) =>
          for {
            issuingMiningRounds <- response.issuingMiningRounds
              .traverse(round =>
                Contract.fromJson(roundCodegen.IssuingMiningRound.COMPANION)(round)
              )
              .leftMap(_.toString)
            latestOpenMiningRound <- Contract
              .fromJson(roundCodegen.OpenMiningRound.COMPANION)(response.latestOpenMiningRound)
              .leftMap(_.toString)
          } yield (latestOpenMiningRound, issuingMiningRounds)
      }
  }

  case object GetCoinRules
      extends BaseCommand[
        http.GetCoinRulesResponse,
        Contract[CoinRules.ContractId, CoinRules],
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetCoinRulesResponse] =
      client.getCoinRules(headers)

    override def handleResponse(
        response: http.GetCoinRulesResponse
    )(implicit decoder: TemplateJsonDecoder): Either[
      String,
      Contract[CoinRules.ContractId, CoinRules],
    ] =
      response match {
        case http.GetCoinRulesResponse.OK(response) =>
          for {
            coinRulesO <- response.coinRules
              .traverse(coinRules => Contract.fromJson(coinCodegen.CoinRules.COMPANION)(coinRules))
              .leftMap(_.toString)
            coinRules <- coinRulesO.toRight("found no coin rules")
          } yield coinRules
      }
  }

  case object GetClosedRounds
      extends BaseCommand[
        http.GetClosedRoundsResponse,
        Seq[Contract[roundCodegen.ClosedMiningRound.ContractId, roundCodegen.ClosedMiningRound]],
      ] {

    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetClosedRoundsResponse] =
      client.getClosedRounds(headers)

    override def handleResponse(
        response: http.GetClosedRoundsResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, Seq[
      Contract[roundCodegen.ClosedMiningRound.ContractId, roundCodegen.ClosedMiningRound]
    ]] = {
      response match {
        case http.GetClosedRoundsResponse.OK(response) =>
          response.rounds
            .traverse(round => Contract.fromJson(roundCodegen.ClosedMiningRound.COMPANION)(round))
            .leftMap(_.toString)
      }
    }
  }

  case object ListFeaturedAppRight
      extends BaseCommand[
        http.ListFeaturedAppRightsResponse,
        Seq[Contract[FeaturedAppRight.ContractId, FeaturedAppRight]],
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.ListFeaturedAppRightsResponse] =
      client.listFeaturedAppRights(headers)

    override def handleResponse(
        response: http.ListFeaturedAppRightsResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, Seq[Contract[FeaturedAppRight.ContractId, FeaturedAppRight]]] =
      response match {
        case http.ListFeaturedAppRightsResponse.OK(response) =>
          response.featuredApps
            .traverse(co => Contract.fromJson(FeaturedAppRight.COMPANION)(co))
            .leftMap(_.toString)
      }
  }

  case class LookupFeaturedAppRight(providerPartyId: PartyId)
      extends BaseCommand[
        http.LookupFeaturedAppRightResponse,
        Option[Contract[FeaturedAppRight.ContractId, FeaturedAppRight]],
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.LookupFeaturedAppRightResponse] =
      client.lookupFeaturedAppRight(providerPartyId.toProtoPrimitive, headers)

    override def handleResponse(
        response: http.LookupFeaturedAppRightResponse
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, Option[Contract[FeaturedAppRight.ContractId, FeaturedAppRight]]] =
      response match {
        case http.LookupFeaturedAppRightResponse.OK(response) =>
          response.featuredAppRight
            .traverse(co => Contract.fromJson(FeaturedAppRight.COMPANION)(co))
            .leftMap(_.toString)
      }
  }

  case object ListConnectedDomains
      extends BaseCommand[http.ListConnectedDomainsResponse, Map[DomainAlias, DomainId]] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.ListConnectedDomainsResponse] =
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
