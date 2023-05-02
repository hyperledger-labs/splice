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
import com.daml.network.util.{Codec, Contract, TemplateJsonDecoder}
import com.digitalasset.canton.topology.PartyId

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.OptionConverters.*
import com.daml.network.codegen.java.cc.v1test.coin.CoinRulesV1Test

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
          Codec.decode(Codec.Party)(response.svcPartyId)
      }
  }

  /** Very similar to the AppTransferContext we use in Daml, except
    * (1) this class has contract instances, not just (interface) contract-ids of the respective Daml contracts.
    * (2) this class has no featuredAppRight contract.
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

  case class GetCoinRulesV1Test(
      cachedCoinRules: Option[Contract[CoinRulesV1Test.ContractId, CoinRulesV1Test]]
  ) extends BaseCommand[
        http.GetCoinRulesV1TestResponse,
        Contract[CoinRulesV1Test.ContractId, CoinRulesV1Test],
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetCoinRulesV1TestResponse] = {
      client.getCoinRulesV1Test(
        GetCoinRulesRequest(cachedCoinRules.map(_.contractId.contractId)),
        headers,
      )
    }

    override def handleResponse(
        response: http.GetCoinRulesV1TestResponse
    )(implicit decoder: TemplateJsonDecoder): Either[
      String,
      Contract[CoinRulesV1Test.ContractId, CoinRulesV1Test],
    ] =
      response match {
        case http.GetCoinRulesV1TestResponse.OK(response) =>
          for {
            coinRules <- Contract.handleMaybeCachedContract(CoinRulesV1Test.COMPANION)(
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
            unlocked <- Codec.decode(Codec.BigDecimal)(response.totalUnlockedBalance)
            locked <- Codec.decode(Codec.BigDecimal)(response.totalLockedBalance)
          } yield {
            TotalBalances(
              totalUnlocked = unlocked,
              totalLocked = locked,
            )
          }
      }
  }

  final case class RateStep(
      amount: BigDecimal,
      rate: BigDecimal,
  )
  final case class SteppedRate(
      initial: BigDecimal,
      steps: Seq[RateStep],
  )
  final case class CoinConfig(
      coinCreateFee: BigDecimal,
      holdingFee: BigDecimal,
      lockHolderFee: BigDecimal,
      transferFee: SteppedRate,
  )
  case class GetCoinConfigForRound(round: Long)
      extends BaseCommand[http.GetCoinConfigForRoundResponse, CoinConfig] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetCoinConfigForRoundResponse] =
      client.getCoinConfigForRound(round, headers)

    private def decodeStep(step: definitions.RateStep): Either[String, RateStep] =
      for {
        amount <- Codec.decode(Codec.BigDecimal)(step.amount)
        rate <- Codec.decode(Codec.BigDecimal)(step.rate)
      } yield RateStep(amount, rate)

    private def decodeTransferFeeSteps(
        tf: Seq[definitions.RateStep]
    ): Either[String, Seq[RateStep]] =
      tf.map(decodeStep(_)).sequence

    override def handleResponse(response: http.GetCoinConfigForRoundResponse)(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, CoinConfig] =
      response match {
        case http.GetCoinConfigForRoundResponse.OK(response) =>
          for {
            coinCreate <- Codec.decode(Codec.BigDecimal)(response.coinCreateFee)
            holding <- Codec.decode(Codec.BigDecimal)(response.holdingFee)
            lockHolder <- Codec.decode(Codec.BigDecimal)(response.lockHolderFee)
            initial <- Codec.decode(Codec.BigDecimal)(response.transferFee.initial)
            steps <- decodeTransferFeeSteps(response.transferFee.steps.toSeq)
          } yield {
            CoinConfig(
              coinCreateFee = coinCreate,
              holdingFee = holding,
              lockHolderFee = lockHolder,
              transferFee = SteppedRate(
                initial = initial,
                steps = steps,
              ),
            )
          }
        case http.GetCoinConfigForRoundResponse.NotFound(value) =>
          Left(value.error)
      }
  }

  case class GetRoundOfLatestData()
      extends BaseCommand[http.GetRoundOfLatestDataResponse, (Long, Instant)] {

    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetRoundOfLatestDataResponse] =
      client.getRoundOfLatestData(headers)

    override def handleResponse(
        response: http.GetRoundOfLatestDataResponse
    )(implicit decoder: TemplateJsonDecoder): Either[String, (Long, Instant)] =
      response match {
        case http.GetRoundOfLatestDataResponse.OK(response) =>
          Right((response.round, response.effectiveAt.toInstant))
        case http.GetRoundOfLatestDataResponse.NotFound(value) =>
          Left(value.error)
      }

  }

  private def decodePartiesAndRewards(
      partiesAndRewards: Vector[definitions.PartyAndRewards]
  ): Either[String, Seq[(PartyId, BigDecimal)]] =
    partiesAndRewards.traverse(par =>
      for {
        p <- Codec.decode(Codec.Party)(par.provider)
        r <- Codec.decode(Codec.BigDecimal)(par.rewards)
      } yield (p, r)
    )

  case class getTopProvidersByAppRewards(asOfEndOfRound: Long, limit: Int)
      extends BaseCommand[http.GetTopProvidersByAppRewardsResponse, Seq[(PartyId, BigDecimal)]] {
    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetTopProvidersByAppRewardsResponse] =
      client.getTopProvidersByAppRewards(asOfEndOfRound, limit, headers)

    override def handleResponse(response: http.GetTopProvidersByAppRewardsResponse)(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, Seq[(PartyId, BigDecimal)]] = response match {
      case http.GetTopProvidersByAppRewardsResponse.OK(response) =>
        decodePartiesAndRewards(response.providersAndRewards)
      case http.GetTopProvidersByAppRewardsResponse.NotFound(value) =>
        Left(value.error)
    }
  }

  case class getTopValidatorsByValidatorRewards(asOfEndOfRound: Long, limit: Int)
      extends BaseCommand[http.GetTopValidatorsByValidatorRewardsResponse, Seq[
        (PartyId, BigDecimal)
      ]] {
    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetTopValidatorsByValidatorRewardsResponse] =
      client.getTopValidatorsByValidatorRewards(asOfEndOfRound, limit, headers)

    override def handleResponse(response: http.GetTopValidatorsByValidatorRewardsResponse)(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, Seq[(PartyId, BigDecimal)]] = response match {
      case http.GetTopValidatorsByValidatorRewardsResponse.OK(response) =>
        decodePartiesAndRewards(response.validatorsAndRewards)
      case http.GetTopValidatorsByValidatorRewardsResponse.NotFound(value) =>
        Left(value.error)
    }
  }

  final case class ValidatorTrafficBalance(
      remainingBalance: Double,
      totalPaid: Double,
  )

  case class GetValidatorTrafficBalance(validatorParty: PartyId)
      extends BaseCommand[http.GetValidatorTrafficBalanceResponse, ValidatorTrafficBalance] {
    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetValidatorTrafficBalanceResponse] =
      client.getValidatorTrafficBalance(validatorParty.toProtoPrimitive, headers)

    override def handleResponse(
        response: http.GetValidatorTrafficBalanceResponse
    )(implicit decoder: TemplateJsonDecoder): Either[String, ValidatorTrafficBalance] =
      response match {
        case http.GetValidatorTrafficBalanceResponse.OK(response) =>
          Right(ValidatorTrafficBalance(response.remainingBalance, response.totalPaid))
        case http.GetValidatorTrafficBalanceResponse.NotFound(value) =>
          Left(value.error)
      }
  }

  case class CheckAndUpdateValidatorTrafficBalance(validatorParty: PartyId)
      extends BaseCommand[http.CheckAndUpdateValidatorTrafficBalanceResponse, Boolean] {
    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.CheckAndUpdateValidatorTrafficBalanceResponse] =
      client.checkAndUpdateValidatorTrafficBalance(validatorParty.toProtoPrimitive, headers)

    override def handleResponse(
        response: http.CheckAndUpdateValidatorTrafficBalanceResponse
    )(implicit decoder: TemplateJsonDecoder): Either[String, Boolean] =
      response match {
        case http.CheckAndUpdateValidatorTrafficBalanceResponse.OK(response) =>
          Right(response.approved)
        case http.CheckAndUpdateValidatorTrafficBalanceResponse.NotFound(value) =>
          Left(value.error)
      }

  }
}
