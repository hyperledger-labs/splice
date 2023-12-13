package com.daml.network.scan.admin.api.client.commands

import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}
import org.apache.pekko.stream.Materializer
import cats.data.EitherT
import cats.syntax.either.*
import cats.syntax.traverse.*
import com.daml.network.admin.api.client.commands.{HttpClientBuilder, HttpCommand}
import com.daml.network.codegen.java.cc.coin.FeaturedAppRight
import com.daml.network.codegen.java.cc.coinrules.{AppTransferContext, CoinRules}
import com.daml.network.codegen.java.cc.round.{
  ClosedMiningRound,
  IssuingMiningRound,
  OpenMiningRound,
}
import com.daml.network.codegen.java.cn.cns as cnsCodegen
import com.daml.network.codegen.java.cn.cns.{CnsEntry, CnsRules}
import com.daml.network.http.v0.{definitions, scan as http}
import com.daml.network.http.v0.definitions.{GetCnsRulesRequest, GetCoinRulesRequest}
import com.daml.network.store.MultiDomainAcsStore
import com.daml.network.codegen.java.cc
import com.daml.network.util.{Codec, Contract, ContractWithState, TemplateJsonDecoder}
import com.digitalasset.canton.topology.{DomainId, PartyId, SequencerId, Member}
import com.digitalasset.canton.tracing.TraceContext

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.OptionConverters.*

object HttpScanAppClient {

  abstract class BaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = http.ScanClient

    def createClient(host: String)(implicit
        httpClient: HttpRequest => Future[HttpResponse],
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      http.ScanClient.httpClient(HttpClientBuilder().buildClient(), host)
  }

  case class GetSvcPartyId(headers: List[HttpHeader])
      extends BaseCommand[http.GetSvcPartyIdResponse, PartyId] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetSvcPartyIdResponse] =
      client.getSvcPartyId(headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.GetSvcPartyIdResponse.OK(response) =>
      Codec.decode(Codec.Party)(response.svcPartyId)
    }
  }

  /** Very similar to the AppTransferContext we use in Daml, except
    * (1) this class has contract instances, not just (interface) contract-ids of the respective Daml contracts.
    * (2) this class has no featuredAppRight contract.
    */
  case class TransferContextWithInstances(
      coinRules: ContractWithState[CoinRules.ContractId, CoinRules],
      latestOpenMiningRound: ContractWithState[
        OpenMiningRound.ContractId,
        OpenMiningRound,
      ],
      openMiningRounds: Seq[
        ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]
      ],
  ) {
    def toUnfeaturedAppTransferContext() = {
      val openMiningRound = latestOpenMiningRound
      new AppTransferContext(
        coinRules.contractId,
        openMiningRound.contractId,
        None.toJava,
      )
    }
  }

  /** Rounds are sorted in ascending order according to their round number. */
  case class GetSortedOpenAndIssuingMiningRounds(
      cachedOpenRounds: Seq[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]],
      cachedIssuingRounds: Seq[ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]],
  ) extends BaseCommand[
        http.GetOpenAndIssuingMiningRoundsResponse,
        (
            Seq[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]],
            Seq[ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]],
            BigInt,
        ),
      ] {

    private val cachedOpenRoundsMap =
      cachedOpenRounds.map(r => (r.contractId.contractId, r)).toMap
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

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetOpenAndIssuingMiningRoundsResponse.OK(response) =>
        for {
          issuingMiningRounds <- response.issuingMiningRounds.toSeq.traverse {
            case (contractId, maybeIssuingRound) =>
              ContractWithState.handleMaybeCached(IssuingMiningRound.COMPANION)(
                cachedIssuingRoundsMap.get(contractId),
                maybeIssuingRound,
              )
          }
          openMiningRounds <- response.openMiningRounds.toSeq.traverse {
            case (contractId, maybeOpenRound) =>
              ContractWithState.handleMaybeCached(OpenMiningRound.COMPANION)(
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
      cachedCoinRules: Option[ContractWithState[CoinRules.ContractId, CoinRules]]
  ) extends BaseCommand[
        http.GetCoinRulesResponse,
        ContractWithState[CoinRules.ContractId, CoinRules],
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetCoinRulesResponse] = {
      import MultiDomainAcsStore.ContractState.*
      client.getCoinRules(
        GetCoinRulesRequest(
          cachedCoinRules.map(_.contractId.contractId),
          cachedCoinRules.flatMap(_.state match {
            case Assigned(domain) => Some(domain.toProtoPrimitive)
            case InFlight => None
          }),
        ),
        headers,
      )
    }

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetCoinRulesResponse.OK(response) =>
        for {
          coinRules <- ContractWithState.handleMaybeCached(CoinRules.COMPANION)(
            cachedCoinRules,
            response.coinRulesUpdate,
          )
        } yield coinRules
    }
  }

  case class GetCnsRules(
      cachedCnsRules: Option[ContractWithState[CnsRules.ContractId, CnsRules]]
  ) extends BaseCommand[
        http.GetCnsRulesResponse,
        ContractWithState[CnsRules.ContractId, CnsRules],
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetCnsRulesResponse] = {
      import MultiDomainAcsStore.ContractState.*
      client.getCnsRules(
        GetCnsRulesRequest(
          cachedCnsRules.map(_.contractId.contractId),
          cachedCnsRules.flatMap(_.state match {
            case Assigned(domain) => Some(domain.toProtoPrimitive)
            case InFlight => None
          }),
        ),
        headers,
      )
    }

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetCnsRulesResponse.OK(response) =>
        for {
          cnsRules <- ContractWithState.handleMaybeCached(cnsCodegen.CnsRules.COMPANION)(
            cachedCnsRules,
            response.cnsRulesUpdate,
          )
        } yield cnsRules
    }
  }

  case object GetClosedRounds
      extends BaseCommand[
        http.GetClosedRoundsResponse,
        Seq[Contract[ClosedMiningRound.ContractId, ClosedMiningRound]],
      ] {

    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetClosedRoundsResponse] =
      client.getClosedRounds(headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.GetClosedRoundsResponse.OK(response) =>
      response.rounds
        .traverse(round => Contract.fromHttp(ClosedMiningRound.COMPANION)(round))
        .leftMap(_.toString)
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

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListFeaturedAppRightsResponse.OK(response) =>
      response.featuredApps
        .traverse(co => Contract.fromHttp(FeaturedAppRight.COMPANION)(co))
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

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.LookupFeaturedAppRightResponse.OK(response) =>
      response.featuredAppRight
        .traverse(co => Contract.fromHttp(FeaturedAppRight.COMPANION)(co))
        .leftMap(_.toString)
    }
  }

  case class ListCnsEntries(
      namePrefix: String,
      pageSize: Int,
  ) extends BaseCommand[http.ListCnsEntriesResponse, Seq[
        Contract[CnsEntry.ContractId, CnsEntry]
      ]] {

    def submitRequest(client: Client, headers: List[HttpHeader]) =
      client.listCnsEntries(Some(namePrefix), pageSize, headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListCnsEntriesResponse.OK(response) =>
      response.entries
        .traverse(entry => Contract.fromHttp(CnsEntry.COMPANION)(entry))
        .leftMap(_.toString)
    }
  }

  case class LookupCnsEntryByParty(
      party: PartyId
  ) extends BaseCommand[
        http.LookupCnsEntryByPartyResponse,
        Contract[CnsEntry.ContractId, CnsEntry],
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ) = client.lookupCnsEntryByParty(party.toProtoPrimitive, headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.LookupCnsEntryByPartyResponse.OK(response) =>
      for {
        entry <- Contract
          .fromHttp(CnsEntry.COMPANION)(response.entry)
          .leftMap(_.toString)
      } yield entry
    }
  }

  case class LookupCnsEntryByName(
      name: String
  ) extends BaseCommand[
        http.LookupCnsEntryByNameResponse,
        Contract[CnsEntry.ContractId, CnsEntry],
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ) = client.lookupCnsEntryByName(name, headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.LookupCnsEntryByNameResponse.OK(response) =>
      for {
        entry <- Contract
          .fromHttp(CnsEntry.COMPANION)(response.entry)
          .leftMap(_.toString)
      } yield entry
    }
  }

  case class GetTotalCoinBalance(asOfEndOfRound: Long)
      extends BaseCommand[http.GetTotalCoinBalanceResponse, BigDecimal] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetTotalCoinBalanceResponse] =
      client.getTotalCoinBalance(asOfEndOfRound, headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetTotalCoinBalanceResponse.OK(response) =>
        Codec.decode(Codec.BigDecimal)(response.totalBalance)
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

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.GetCoinConfigForRoundResponse.OK(response) =>
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
    }
  }

  case class GetRoundOfLatestData()
      extends BaseCommand[http.GetRoundOfLatestDataResponse, (Long, Instant)] {

    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetRoundOfLatestDataResponse] =
      client.getRoundOfLatestData(headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetRoundOfLatestDataResponse.OK(response) =>
        Right((response.round, response.effectiveAt.toInstant))
    }
  }

  case class GetRewardsCollected(round: Option[Long])
      extends BaseCommand[http.GetRewardsCollectedResponse, BigDecimal] {

    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetRewardsCollectedResponse] =
      client.getRewardsCollected(round, headers)

    override protected def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetRewardsCollectedResponse.OK(response) =>
        for {
          amount <- Codec.decode(Codec.BigDecimal)(response.amount)
        } yield amount
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

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.GetTopProvidersByAppRewardsResponse.OK(response) =>
      decodePartiesAndRewards(response.providersAndRewards)
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

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.GetTopValidatorsByValidatorRewardsResponse.OK(response) =>
      decodePartiesAndRewards(response.validatorsAndRewards)
    }
  }

  final case class ValidatorPurchasedTraffic(
      validator: PartyId,
      numPurchases: Long,
      totalTrafficPurchased: Long,
      totalCcSpent: BigDecimal,
      lastPurchasedInRound: Long,
  )

  case class GetTopValidatorsByPurchasedTraffic(asOfEndOfRound: Long, limit: Int)
      extends BaseCommand[http.GetTopValidatorsByPurchasedTrafficResponse, Seq[
        ValidatorPurchasedTraffic
      ]] {
    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetTopValidatorsByPurchasedTrafficResponse] =
      client.getTopValidatorsByPurchasedTraffic(asOfEndOfRound, limit, headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetTopValidatorsByPurchasedTrafficResponse.OK(response) =>
        response.validatorsByPurchasedTraffic.traverse(decodeValidatorPurchasedTraffic)
    }

    private def decodeValidatorPurchasedTraffic(traffic: definitions.ValidatorPurchasedTraffic) = {
      for {
        vp <- Codec.decode(Codec.Party)(traffic.validator)
        n = traffic.numPurchases
        tot = traffic.totalTrafficPurchased
        cc <- Codec.decode(Codec.BigDecimal)(traffic.totalCcSpent)
        lpr = traffic.lastPurchasedInRound
      } yield ValidatorPurchasedTraffic(vp, n, tot, cc, lpr)
    }
  }

  case class GetMemberTrafficStatus(domainId: DomainId, memberId: Member)
      extends BaseCommand[http.GetMemberTrafficStatusResponse, definitions.MemberTrafficStatus] {

    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetMemberTrafficStatusResponse] =
      client.getMemberTrafficStatus(domainId.toProtoPrimitive, memberId.toProtoPrimitive, headers)

    override protected def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetMemberTrafficStatusResponse.OK(response) => Right(response.trafficStatus)
    }
  }

  case class ListImportCrates(party: PartyId)
      extends BaseCommand[http.ListImportCratesResponse, Seq[
        ContractWithState[cc.coinimport.ImportCrate.ContractId, cc.coinimport.ImportCrate]
      ]] {
    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.ListImportCratesResponse] =
      client.listImportCrates(party.toProtoPrimitive, headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.ListImportCratesResponse.OK(response) =>
        response.crates
          .traverse(co =>
            ContractWithState.handleMaybeCached(cc.coinimport.ImportCrate.COMPANION)(None, co)
          )
    }
  }
  case class ListSvcSequencers()
      extends BaseCommand[
        http.ListSvcSequencersResponse,
        Seq[DomainSequencers],
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.ListSvcSequencersResponse] =
      client.listSvcSequencers(headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.ListSvcSequencersResponse.OK(response) =>
        response.domainSequencers.traverse { domain =>
          Codec.decode(Codec.DomainId)(domain.domainId).flatMap { domainId =>
            domain.sequencers
              .traverse { s =>
                Codec.decode(Codec.Sequencer)(s.id).map { sequencerId =>
                  SvcSequencer(sequencerId, s.url, s.svName, s.availableAfter.toInstant)
                }
              }
              .map { sequencers =>
                DomainSequencers(domainId, sequencers)
              }
          }
        }
    }
  }

  final case class DomainSequencers(domainId: DomainId, sequencers: Seq[SvcSequencer])

  final case class SvcSequencer(
      id: SequencerId,
      url: String,
      svName: String,
      availableAfter: Instant,
  )

  case class ListTransactions(
      pageEndEventId: Option[String],
      sortOrder: definitions.TransactionHistoryRequest.SortOrder,
      pageSize: Int,
  ) extends BaseCommand[http.ListTransactionHistoryResponse, Seq[
        definitions.TransactionHistoryResponseItem
      ]] {
    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.ListTransactionHistoryResponse] = {
      client.listTransactionHistory(
        definitions
          .TransactionHistoryRequest(pageEndEventId, Some(sortOrder), pageSize.toLong),
        headers,
      )
    }

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.ListTransactionHistoryResponse.OK(response) =>
        Right(response.transactions)
    }
  }
}
