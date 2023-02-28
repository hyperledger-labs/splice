package com.daml.network.scan.admin.api.client.commands

import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}
import akka.stream.Materializer
import cats.data.EitherT
import cats.syntax.either.*
import cats.syntax.traverse.*
import com.daml.network.admin.api.client.commands.HttpCommand
import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.codegen.java.cc.coin.{CoinRules, FeaturedAppRight}
import com.daml.network.codegen.java.cc.round.{IssuingMiningRound, OpenMiningRound}
import com.daml.network.codegen.java.cc.{
  coin as coinCodegen,
  coinconfig as coinConfigCodegen,
  round as roundCodegen,
}
import com.daml.network.http.v0.definitions.GetCoinRulesRequest
import com.daml.network.http.v0.{definitions, scan as http}
import com.daml.network.util.{Contract, Proto, TemplateJsonDecoder}
import com.digitalasset.canton.topology.PartyId

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.OptionConverters.*

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

  /** Very similar to the AppTransferContext we use in Daml, except
    * (1) this class has contract instances attributes, not just interface-contract id attributes.
    * (2) this class has no featuredAppRight attribute.
    */
  case class TransferContextWithInstances(
      coinRules: Contract[coinCodegen.CoinRules.ContractId, coinCodegen.CoinRules],
      latestOpenMiningRound: Contract[
        roundCodegen.OpenMiningRound.ContractId,
        roundCodegen.OpenMiningRound,
      ],
      openMiningRounds: Seq[
        Contract[roundCodegen.OpenMiningRound.ContractId, roundCodegen.OpenMiningRound]
      ],
  ) {
    def toUnfeaturedAppTransferContext() = {
      val openMiningRound = latestOpenMiningRound
      new v1.coin.AppTransferContext(
        coinRules.contractId.toInterface(v1.coin.CoinRules.INTERFACE),
        openMiningRound.contractId.toInterface(v1.round.OpenMiningRound.INTERFACE),
        None.toJava,
      )
    }
  }

  case class ConfigSchedule(
      currentConfig: coinConfigCodegen.CoinConfig[coinConfigCodegen.USD],
      futureConfigs: Map[Instant, coinConfigCodegen.CoinConfig[coinConfigCodegen.USD]],
  )

  /** Rounds are sorted in ascending order according to their round number. */
  case class GetSortedOpenAndIssuingMiningRounds(
      cachedOpenRounds: Seq[Contract[OpenMiningRound.ContractId, OpenMiningRound]],
      cachedIssuingRounds: Seq[Contract[IssuingMiningRound.ContractId, IssuingMiningRound]],
  ) extends BaseCommand[
        http.GetOpenAndIssuingMiningRoundsResponse,
        (
            Seq[Contract[OpenMiningRound.ContractId, OpenMiningRound]],
            Seq[Contract[IssuingMiningRound.ContractId, IssuingMiningRound]],
            BigInt,
        ),
      ] {

    private val cachedOpenRoundsMap = cachedOpenRounds.map(r => (r.contractId.contractId, r)).toMap
    private val cachedIssuingRoundsMap =
      cachedIssuingRounds.map(r => (r.contractId.contractId, r)).toMap

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetOpenAndIssuingMiningRoundsResponse] =
      client.getOpenAndIssuingMiningRounds(
        definitions.GetOpenAndIssuingMiningRoundsRequest(
          cachedOpenRounds.map(_.contractId.contractId).toVector,
          cachedIssuingRounds.map(_.contractId.contractId).toVector,
        ),
        headers,
      )

    override def handleResponse(
        response: http.GetOpenAndIssuingMiningRoundsResponse
    )(implicit decoder: TemplateJsonDecoder): Either[
      String,
      (
          Seq[Contract[OpenMiningRound.ContractId, OpenMiningRound]],
          Seq[Contract[IssuingMiningRound.ContractId, IssuingMiningRound]],
          BigInt,
      ),
    ] =
      response match {
        case http.GetOpenAndIssuingMiningRoundsResponse.OK(response) =>
          for {
            issuingMiningRounds <- response.issuingMiningRounds.toSeq.traverse {
              case (contractId, maybeIssuingRound) =>
                Contract.handleMaybeCachedContract(roundCodegen.IssuingMiningRound.COMPANION)(
                  cachedIssuingRoundsMap.get(contractId),
                  maybeIssuingRound,
                )
            }
            openMiningRounds <- response.openMiningRounds.toSeq.traverse {
              case (contractId, maybeOpenRound) =>
                Contract.handleMaybeCachedContract(roundCodegen.OpenMiningRound.COMPANION)(
                  cachedOpenRoundsMap.get(contractId),
                  maybeOpenRound,
                )
            }
          } yield (
            openMiningRounds.sortBy(_.payload.round.number),
            issuingMiningRounds.sortBy(_.payload.round.number),
            response.timeToLiveInMicroseconds,
          )
      }
  }

  case class GetCoinRules(
      cachedCoinRules: Option[Contract[CoinRules.ContractId, CoinRules]]
  ) extends BaseCommand[
        http.GetCoinRulesResponse,
        Contract[CoinRules.ContractId, CoinRules],
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetCoinRulesResponse] = {
      client.getCoinRules(
        GetCoinRulesRequest(cachedCoinRules.map(_.contractId.contractId)),
        headers,
      )
    }

    override def handleResponse(
        response: http.GetCoinRulesResponse
    )(implicit decoder: TemplateJsonDecoder): Either[
      String,
      Contract[CoinRules.ContractId, CoinRules],
    ] =
      response match {
        case http.GetCoinRulesResponse.OK(response) =>
          for {
            coinRules <- Contract.handleMaybeCachedContract(coinCodegen.CoinRules.COMPANION)(
              cachedCoinRules,
              response.coinRulesUpdate,
            )
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

  final case class TotalBalances(
      totalUnlocked: BigDecimal,
      totalLocked: BigDecimal,
  )

  case object GetTotalCoinBalance
      extends BaseCommand[http.GetTotalCoinBalanceResponse, TotalBalances] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetTotalCoinBalanceResponse] =
      client.getTotalCoinBalance(headers)

    override def handleResponse(
        response: http.GetTotalCoinBalanceResponse
    )(implicit decoder: TemplateJsonDecoder): Either[String, TotalBalances] =
      response match {
        case http.GetTotalCoinBalanceResponse.OK(response) =>
          for {
            unlocked <- Proto.decode(Proto.BigDecimal)(response.totalUnlockedBalance)
            locked <- Proto.decode(Proto.BigDecimal)(response.totalLockedBalance)
          } yield {
            TotalBalances(
              totalUnlocked = unlocked,
              totalLocked = locked,
            )
          }
      }
  }
}
