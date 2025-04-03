// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.api.client.commands

import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpResponse, StatusCodes, Uri}
import org.apache.pekko.stream.Materializer
import cats.data.EitherT
import cats.syntax.either.*
import cats.syntax.traverse.*
import org.lfdecentralizedtrust.splice.admin.api.client.commands.{HttpClientBuilder, HttpCommand}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.FeaturedAppRight
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.{
  AmuletRules,
  AppTransferContext,
  TransferPreapproval,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.externalpartyamuletrules.{
  ExternalPartyAmuletRules,
  TransferCommandCounter,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.{
  ClosedMiningRound,
  IssuingMiningRound,
  OpenMiningRound,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.ans as ansCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.ans.AnsRules
import org.lfdecentralizedtrust.splice.config.SpliceInstanceNamesConfig
import org.lfdecentralizedtrust.splice.environment.ledger.api.LedgerClient
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.http.v0.{definitions, scan as http}
import org.lfdecentralizedtrust.splice.http.v0.scan.{
  ForceAcsSnapshotNowResponse,
  GetDateOfMostRecentSnapshotBeforeResponse,
  ScanClient,
}
import org.lfdecentralizedtrust.splice.scan.admin.http.ProtobufJsonScanHttpEncodings
import org.lfdecentralizedtrust.splice.scan.store.db.ScanAggregator
import org.lfdecentralizedtrust.splice.store.HistoryBackfilling.SourceMigrationInfo
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore
import org.lfdecentralizedtrust.splice.util.{
  Codec,
  Contract,
  ContractWithState,
  DomainRecordTimeRange,
  PackageQualifiedName,
  TemplateJsonDecoder,
}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.{DomainId, Member, ParticipantId, PartyId, SequencerId}
import com.digitalasset.canton.tracing.TraceContext
import com.google.protobuf.ByteString
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  DsoRules_CloseVoteRequestResult,
  VoteRequest,
}

import java.util.Base64
import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.OptionConverters.*
import scala.util.Try

object HttpScanAppClient {

  abstract class InternalBaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = http.ScanClient

    def createClient(host: String)(implicit
        httpClient: HttpClient,
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      http.ScanClient.httpClient(HttpClientBuilder().buildClient(Set(StatusCodes.NotFound)), host)
  }

  abstract class ExternalBaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = http.ScanClient

    def createClient(host: String)(implicit
        httpClient: HttpClient,
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      http.ScanClient.httpClient(HttpClientBuilder().buildClient(), host)
  }

  case class GetDsoPartyId(headers: List[HttpHeader])
      extends InternalBaseCommand[http.GetDsoPartyIdResponse, PartyId] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetDsoPartyIdResponse] =
      client.getDsoPartyId(headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.GetDsoPartyIdResponse.OK(response) =>
      Codec.decode(Codec.Party)(response.dsoPartyId)
    }
  }

  case class GetDsoInfo(headers: List[HttpHeader])
      extends InternalBaseCommand[http.GetDsoInfoResponse, definitions.GetDsoInfoResponse] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetDsoInfoResponse] =
      client.getDsoInfo(headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.GetDsoInfoResponse.OK(response) =>
      Right(response)
    }
  }

  /** Very similar to the AppTransferContext we use in Daml, except
    * (1) this class has contract instances, not just (interface) contract-ids of the respective Daml contracts.
    * (2) this class has no featuredAppRight contract.
    */
  case class TransferContextWithInstances(
      amuletRules: ContractWithState[AmuletRules.ContractId, AmuletRules],
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
        amuletRules.contractId,
        openMiningRound.contractId,
        None.toJava,
      )
    }
  }

  /** Rounds are sorted in ascending order according to their round number. */
  case class GetSortedOpenAndIssuingMiningRounds(
      cachedOpenRounds: Seq[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]],
      cachedIssuingRounds: Seq[ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]],
  ) extends InternalBaseCommand[
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

  case class GetAmuletRules(
      cachedAmuletRules: Option[ContractWithState[AmuletRules.ContractId, AmuletRules]]
  ) extends InternalBaseCommand[
        http.GetAmuletRulesResponse,
        ContractWithState[AmuletRules.ContractId, AmuletRules],
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetAmuletRulesResponse] = {
      import MultiDomainAcsStore.ContractState.*
      client.getAmuletRules(
        definitions.GetAmuletRulesRequest(
          cachedAmuletRules.map(_.contractId.contractId),
          cachedAmuletRules.flatMap(_.state match {
            case Assigned(domain) => Some(domain.toProtoPrimitive)
            case InFlight => None
          }),
        ),
        headers,
      )
    }

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetAmuletRulesResponse.OK(response) =>
        for {
          amuletRules <- ContractWithState.handleMaybeCached(AmuletRules.COMPANION)(
            cachedAmuletRules,
            response.amuletRulesUpdate,
          )
        } yield amuletRules
    }
  }

  case class GetExternalPartyAmuletRules(
      cachedExternalPartyAmuletRules: Option[
        ContractWithState[ExternalPartyAmuletRules.ContractId, ExternalPartyAmuletRules]
      ]
  ) extends InternalBaseCommand[
        http.GetExternalPartyAmuletRulesResponse,
        ContractWithState[ExternalPartyAmuletRules.ContractId, ExternalPartyAmuletRules],
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[
      Future,
      Either[Throwable, HttpResponse],
      http.GetExternalPartyAmuletRulesResponse,
    ] = {
      import MultiDomainAcsStore.ContractState.*
      client.getExternalPartyAmuletRules(
        definitions.GetExternalPartyAmuletRulesRequest(
          cachedExternalPartyAmuletRules.map(_.contractId.contractId),
          cachedExternalPartyAmuletRules.flatMap(_.state match {
            case Assigned(domain) => Some(domain.toProtoPrimitive)
            case InFlight => None
          }),
        ),
        headers,
      )
    }

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetExternalPartyAmuletRulesResponse.OK(response) =>
        for {
          externalPartyAmuletRules <- ContractWithState.handleMaybeCached(
            ExternalPartyAmuletRules.COMPANION
          )(
            cachedExternalPartyAmuletRules,
            response.externalPartyAmuletRulesUpdate,
          )
        } yield externalPartyAmuletRules
    }
  }

  case class GetAnsRules(
      cachedAnsRules: Option[ContractWithState[AnsRules.ContractId, AnsRules]]
  ) extends InternalBaseCommand[
        http.GetAnsRulesResponse,
        ContractWithState[AnsRules.ContractId, AnsRules],
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetAnsRulesResponse] = {
      import MultiDomainAcsStore.ContractState.*
      client.getAnsRules(
        definitions.GetAnsRulesRequest(
          cachedAnsRules.map(_.contractId.contractId),
          cachedAnsRules.flatMap(_.state match {
            case Assigned(domain) => Some(domain.toProtoPrimitive)
            case InFlight => None
          }),
        ),
        headers,
      )
    }

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetAnsRulesResponse.OK(response) =>
        for {
          ansRules <- ContractWithState.handleMaybeCached(ansCodegen.AnsRules.COMPANION)(
            cachedAnsRules,
            response.ansRulesUpdate,
          )
        } yield ansRules
    }
  }

  case object GetClosedRounds
      extends InternalBaseCommand[
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
      extends InternalBaseCommand[
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
      extends InternalBaseCommand[
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

  case class ListAnsEntries(
      namePrefix: Option[String],
      pageSize: Int,
  ) extends InternalBaseCommand[http.ListAnsEntriesResponse, Seq[definitions.AnsEntry]] {

    def submitRequest(client: Client, headers: List[HttpHeader]) =
      client.listAnsEntries(namePrefix, pageSize, headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListAnsEntriesResponse.OK(response) =>
      Right(response.entries)
    }
  }

  case class LookupAnsEntryByParty(
      party: PartyId
  ) extends InternalBaseCommand[http.LookupAnsEntryByPartyResponse, Option[definitions.AnsEntry]] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ) = client.lookupAnsEntryByParty(party.toProtoPrimitive, headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = {
      case http.LookupAnsEntryByPartyResponse.OK(response) =>
        Right(Some(response.entry))
      case http.LookupAnsEntryByPartyResponse.NotFound(_) =>
        Right(None)
    }
  }

  case class LookupAnsEntryByName(
      name: String
  ) extends InternalBaseCommand[http.LookupAnsEntryByNameResponse, Option[definitions.AnsEntry]] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ) = client.lookupAnsEntryByName(name, headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = {
      case http.LookupAnsEntryByNameResponse.OK(response) =>
        Right(Some(response.entry))
      case http.LookupAnsEntryByNameResponse.NotFound(_) =>
        Right(None)
    }
  }

  case class LookupTransferPreapprovalByParty(
      party: PartyId
  ) extends InternalBaseCommand[http.LookupTransferPreapprovalByPartyResponse, Option[
        ContractWithState[TransferPreapproval.ContractId, TransferPreapproval]
      ]] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ) = client.lookupTransferPreapprovalByParty(party.toProtoPrimitive, headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = {
      case http.LookupTransferPreapprovalByPartyResponse.OK(response) =>
        ContractWithState
          .fromHttp(TransferPreapproval.COMPANION)(response.transferPreapproval)
          .map(Some(_))
          .leftMap(_.toString)
      case http.LookupTransferPreapprovalByPartyResponse.NotFound(_) =>
        Right(None)
    }
  }

  case class LookupTransferCommandCounterByParty(
      party: PartyId
  ) extends InternalBaseCommand[http.LookupTransferCommandCounterByPartyResponse, Option[
        ContractWithState[TransferCommandCounter.ContractId, TransferCommandCounter]
      ]] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ) = client.lookupTransferCommandCounterByParty(party.toProtoPrimitive, headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = {
      case http.LookupTransferCommandCounterByPartyResponse.OK(response) =>
        ContractWithState
          .fromHttp(TransferCommandCounter.COMPANION)(response.transferCommandCounter)
          .map(Some(_))
          .leftMap(_.toString)
      case http.LookupTransferCommandCounterByPartyResponse.NotFound(_) =>
        Right(None)
    }
  }

  case class LookupTransferCommandStatus(
      sender: PartyId,
      nonce: Long,
  ) extends InternalBaseCommand[http.LookupTransferCommandStatusResponse, Option[
        definitions.LookupTransferCommandStatusResponse
      ]] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ) = client.lookupTransferCommandStatus(Codec.encode(sender), nonce, headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = {
      case http.LookupTransferCommandStatusResponse.OK(ev) =>
        Right(Some(ev))
      case http.LookupTransferCommandStatusResponse.NotFound(_) =>
        Right(None)
    }
  }

  case class GetTotalAmuletBalance(asOfEndOfRound: Long)
      extends InternalBaseCommand[http.GetTotalAmuletBalanceResponse, Option[BigDecimal]] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetTotalAmuletBalanceResponse] =
      client.getTotalAmuletBalance(asOfEndOfRound, headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetTotalAmuletBalanceResponse.OK(response) =>
        Codec.decode(Codec.BigDecimal)(response.totalBalance).map(Some(_))
      case http.GetTotalAmuletBalanceResponse.NotFound(_) =>
        Right(None)
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
  final case class AmuletConfig(
      amuletCreateFee: BigDecimal,
      holdingFee: BigDecimal,
      lockHolderFee: BigDecimal,
      transferFee: SteppedRate,
  )
  case class GetAmuletConfigForRound(round: Long)
      extends InternalBaseCommand[http.GetAmuletConfigForRoundResponse, AmuletConfig] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetAmuletConfigForRoundResponse] =
      client.getAmuletConfigForRound(round, headers)

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
    ) = {
      case http.GetAmuletConfigForRoundResponse.OK(response) =>
        for {
          amuletCreate <- Codec.decode(Codec.BigDecimal)(response.amuletCreateFee)
          holding <- Codec.decode(Codec.BigDecimal)(response.holdingFee)
          lockHolder <- Codec.decode(Codec.BigDecimal)(response.lockHolderFee)
          initial <- Codec.decode(Codec.BigDecimal)(response.transferFee.initial)
          steps <- decodeTransferFeeSteps(response.transferFee.steps.toSeq)
        } yield {
          AmuletConfig(
            amuletCreateFee = amuletCreate,
            holdingFee = holding,
            lockHolderFee = lockHolder,
            transferFee = SteppedRate(
              initial = initial,
              steps = steps,
            ),
          )
        }
      case http.GetAmuletConfigForRoundResponse.NotFound(err) =>
        Left(err.error)
    }
  }

  case class GetRoundOfLatestData()
      extends InternalBaseCommand[http.GetRoundOfLatestDataResponse, (Long, Instant)] {

    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetRoundOfLatestDataResponse] =
      client.getRoundOfLatestData(headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetRoundOfLatestDataResponse.OK(response) =>
        Right((response.round, response.effectiveAt.toInstant))
      case http.GetRoundOfLatestDataResponse.NotFound(err) =>
        Left(err.error)
    }
  }

  case class GetRewardsCollected(round: Option[Long])
      extends InternalBaseCommand[http.GetRewardsCollectedResponse, BigDecimal] {

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
      case http.GetRewardsCollectedResponse.NotFound(err) =>
        Left(err.error)
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
      extends InternalBaseCommand[http.GetTopProvidersByAppRewardsResponse, Seq[
        (PartyId, BigDecimal)
      ]] {
    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetTopProvidersByAppRewardsResponse] =
      client.getTopProvidersByAppRewards(asOfEndOfRound, limit, headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = {
      case http.GetTopProvidersByAppRewardsResponse.OK(response) =>
        decodePartiesAndRewards(response.providersAndRewards)
      case http.GetTopProvidersByAppRewardsResponse.NotFound(err) =>
        Left(err.error)
    }
  }

  case class getTopValidatorsByValidatorRewards(asOfEndOfRound: Long, limit: Int)
      extends InternalBaseCommand[http.GetTopValidatorsByValidatorRewardsResponse, Seq[
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
    ) = {
      case http.GetTopValidatorsByValidatorRewardsResponse.OK(response) =>
        decodePartiesAndRewards(response.validatorsAndRewards)
      case http.GetTopValidatorsByValidatorRewardsResponse.NotFound(err) =>
        Left(err.error)
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
      extends InternalBaseCommand[http.GetTopValidatorsByPurchasedTrafficResponse, Seq[
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
      case http.GetTopValidatorsByPurchasedTrafficResponse.NotFound(err) =>
        Left(err.error)
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
      extends ExternalBaseCommand[
        http.GetMemberTrafficStatusResponse,
        definitions.MemberTrafficStatus,
      ] {

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

  case class GetPartyToParticipant(domainId: DomainId, partyId: PartyId)
      extends ExternalBaseCommand[
        http.GetPartyToParticipantResponse,
        ParticipantId,
      ] {

    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetPartyToParticipantResponse] =
      client.getPartyToParticipant(domainId.toProtoPrimitive, partyId.toProtoPrimitive, headers)

    override protected def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetPartyToParticipantResponse.OK(response) =>
        for {
          participantId <- Codec.decode(Codec.Participant)(response.participantId)
        } yield participantId
    }
  }

  case class ListDsoSequencers()
      extends InternalBaseCommand[
        http.ListDsoSequencersResponse,
        Seq[DomainSequencers],
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.ListDsoSequencersResponse] =
      client.listDsoSequencers(headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.ListDsoSequencersResponse.OK(response) =>
        response.domainSequencers.traverse { domain =>
          // TODO (#9309): malicious scans can make these decoding fail
          Codec.decode(Codec.DomainId)(domain.domainId).flatMap { domainId =>
            domain.sequencers
              .traverse { s =>
                Codec.decode(Codec.Sequencer)(s.id).map { sequencerId =>
                  DsoSequencer(
                    s.migrationId,
                    sequencerId,
                    s.url,
                    s.svName,
                    s.availableAfter.toInstant,
                  )
                }
              }
              .map { sequencers =>
                DomainSequencers(domainId, sequencers)
              }
          }
        }
    }
  }

  final case class DomainSequencers(domainId: DomainId, sequencers: Seq[DsoSequencer])

  final case class DsoSequencer(
      migrationId: Long,
      id: SequencerId,
      url: String,
      svName: String,
      availableAfter: Instant,
  )

  case class ListDsoScans()
      extends InternalBaseCommand[
        http.ListDsoScansResponse,
        Seq[DomainScans],
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.ListDsoScansResponse] =
      client.listDsoScans(headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.ListDsoScansResponse.OK(response) =>
        response.scans.traverse { domain =>
          // TODO (#9309): malicious scans can make this decoding fail
          Codec.decode(Codec.DomainId)(domain.domainId).map { domainId =>
            // all SVs validate the Uri, so this should only fail to parse for malicious SVs.
            val (malformed, scanList) =
              domain.scans.partitionMap(scan =>
                Try(Uri(scan.publicUrl)).toEither
                  .bimap(scan.publicUrl -> _, url => DsoScan(url, scan.svName))
              )
            DomainScans(domainId, scanList, malformed.toMap)
          }
        }
    }
  }
  final case class DomainScans(
      domainId: DomainId,
      scans: Seq[DsoScan],
      malformed: Map[String, Throwable],
  )

  final case class DsoScan(publicUrl: Uri, svName: String)

  case class ListTransactions(
      pageEndEventId: Option[String],
      sortOrder: definitions.TransactionHistoryRequest.SortOrder,
      pageSize: Int,
  ) extends InternalBaseCommand[http.ListTransactionHistoryResponse, Seq[
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

  case class GetAcsSnapshot(
      party: PartyId
  ) extends InternalBaseCommand[http.GetAcsSnapshotResponse, ByteString] {
    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetAcsSnapshotResponse] = {
      client.getAcsSnapshot(party.toProtoPrimitive, headers)
    }

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetAcsSnapshotResponse.OK(response) =>
        Right(ByteString.copyFrom(Base64.getDecoder.decode(response.acsSnapshot)))
    }
  }

  case object ForceAcsSnapshotNow
      extends InternalBaseCommand[http.ForceAcsSnapshotNowResponse, CantonTimestamp] {
    override def submitRequest(
        client: ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], ForceAcsSnapshotNowResponse] =
      client.forceAcsSnapshotNow(headers)

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[ForceAcsSnapshotNowResponse, Either[String, CantonTimestamp]] = {
      case http.ForceAcsSnapshotNowResponse.OK(response) =>
        Right(CantonTimestamp.assertFromInstant(response.recordTime.toInstant))
    }
  }

  case class GetDateOfMostRecentSnapshotBefore(
      before: java.time.OffsetDateTime,
      migrationId: Long,
  ) extends InternalBaseCommand[
        http.GetDateOfMostRecentSnapshotBeforeResponse,
        Option[java.time.OffsetDateTime],
      ] {
    override def submitRequest(
        client: ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], GetDateOfMostRecentSnapshotBeforeResponse] =
      client.getDateOfMostRecentSnapshotBefore(before, migrationId, headers)

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[GetDateOfMostRecentSnapshotBeforeResponse, Either[
      String,
      Option[java.time.OffsetDateTime],
    ]] = {
      case http.GetDateOfMostRecentSnapshotBeforeResponse.OK(value) =>
        Right(Some(value.recordTime))
      case http.GetDateOfMostRecentSnapshotBeforeResponse.NotFound(_) =>
        Right(None)
    }
  }

  case class GetAcsSnapshotAt(
      at: java.time.OffsetDateTime,
      migrationId: Long,
      after: Option[Long] = None,
      pageSize: Int = 100,
      partyIds: Option[Vector[PartyId]] = None,
      templates: Option[Vector[PackageQualifiedName]] = None,
  ) extends InternalBaseCommand[
        http.GetAcsSnapshotAtResponse,
        Option[definitions.AcsResponse],
      ] {
    override def submitRequest(
        client: ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetAcsSnapshotAtResponse] =
      client.getAcsSnapshotAt(
        definitions.AcsRequest(
          migrationId,
          at,
          after,
          pageSize,
          partyIds.map(_.map(_.toProtoPrimitive)),
          templates.map(_.map(_.toString)),
        ),
        headers,
      )

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[http.GetAcsSnapshotAtResponse, Either[
      String,
      Option[definitions.AcsResponse],
    ]] = {
      case http.GetAcsSnapshotAtResponse.OK(value) =>
        Right(Some(value))
      case http.GetAcsSnapshotAtResponse.NotFound(_) =>
        Right(None)
    }
  }

  case class GetHoldingsStateAt(
      at: java.time.OffsetDateTime,
      migrationId: Long,
      partyIds: Vector[PartyId],
      after: Option[Long] = None,
      pageSize: Int = 100,
  ) extends InternalBaseCommand[
        http.GetHoldingsStateAtResponse,
        Option[definitions.AcsResponse],
      ] {
    override def submitRequest(
        client: ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetHoldingsStateAtResponse] =
      client.getHoldingsStateAt(
        definitions.HoldingsStateRequest(
          migrationId,
          at,
          after,
          pageSize,
          partyIds.map(_.toProtoPrimitive),
        ),
        headers,
      )

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[http.GetHoldingsStateAtResponse, Either[
      String,
      Option[definitions.AcsResponse],
    ]] = {
      case http.GetHoldingsStateAtResponse.OK(value) =>
        Right(Some(value))
      case http.GetHoldingsStateAtResponse.NotFound(_) =>
        Right(None)
    }
  }

  object GetAggregatedRounds
      extends InternalBaseCommand[http.GetAggregatedRoundsResponse, Option[
        ScanAggregator.RoundRange
      ]] {
    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetAggregatedRoundsResponse] = {
      client.getAggregatedRounds(headers)
    }

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetAggregatedRoundsResponse.OK(response) =>
        Right(Some(ScanAggregator.RoundRange(response.start, response.end)))
      case http.GetAggregatedRoundsResponse.NotFound(_) =>
        Right(None)
    }
  }
  case class ListRoundTotals(start: Long, end: Long)
      extends InternalBaseCommand[
        http.ListRoundTotalsResponse,
        Seq[definitions.RoundTotals],
      ] {
    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.ListRoundTotalsResponse] = {
      client.listRoundTotals(definitions.ListRoundTotalsRequest(start, end), headers)
    }

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.ListRoundTotalsResponse.OK(response) =>
        Right(response.entries)
    }
  }

  case class ListRoundPartyTotals(start: Long, end: Long)
      extends InternalBaseCommand[
        http.ListRoundPartyTotalsResponse,
        Seq[definitions.RoundPartyTotals],
      ] {
    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.ListRoundPartyTotalsResponse] = {
      client.listRoundPartyTotals(definitions.ListRoundPartyTotalsRequest(start, end), headers)
    }

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.ListRoundPartyTotalsResponse.OK(response) =>
        Right(response.entries)
    }
  }

  case class GetMigrationSchedule()
      extends InternalBaseCommand[
        http.GetMigrationScheduleResponse,
        Option[definitions.MigrationSchedule],
      ] {

    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetMigrationScheduleResponse] = {
      client.getMigrationSchedule(headers)
    }

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetMigrationScheduleResponse.OK(response) =>
        Right(Some(response))
      case http.GetMigrationScheduleResponse.NotFound =>
        Right(None)
    }
  }

  @deprecated(message = "Use GetUpdateHistory instead", since = "0.2.5")
  case class GetUpdateHistoryV0(
      count: Int,
      after: Option[(Long, String)],
      lossless: Boolean,
  ) extends InternalBaseCommand[http.GetUpdateHistoryResponse, Seq[
        definitions.UpdateHistoryItem
      ]] {
    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetUpdateHistoryResponse] = {
      client.getUpdateHistory(
        definitions.UpdateHistoryRequest(
          after = after.map { case (migrationId, recordTime) =>
            definitions.UpdateHistoryRequestAfter(migrationId, recordTime)
          },
          pageSize = count,
          lossless = Some(lossless),
        ),
        headers,
      )
    }
    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetUpdateHistoryResponse.OK(response) =>
        Right(response.transactions)
    }
  }

  case class GetUpdateHistory(
      count: Int,
      after: Option[(Long, String)],
      damlValueEncoding: definitions.DamlValueEncoding,
  ) extends InternalBaseCommand[http.GetUpdateHistoryV1Response, Seq[
        definitions.UpdateHistoryItem
      ]] {
    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetUpdateHistoryV1Response] = {
      client.getUpdateHistoryV1(
        definitions.UpdateHistoryRequestV1(
          after = after.map { case (migrationId, recordTime) =>
            definitions.UpdateHistoryRequestAfter(migrationId, recordTime)
          },
          pageSize = count,
          damlValueEncoding = Some(damlValueEncoding),
        ),
        headers,
      )
    }

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetUpdateHistoryV1Response.OK(response) =>
        Right(response.transactions)
    }
  }

  @deprecated(message = "Use GetUpdateHistory instead", since = "0.2.5")
  case class GetUpdateV0(updateId: String, lossless: Boolean)
      extends InternalBaseCommand[http.GetUpdateByIdResponse, definitions.UpdateHistoryItem] {
    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetUpdateByIdResponse] = {
      client.getUpdateById(
        updateId = updateId,
        lossless = Some(lossless),
        headers,
      )
    }

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetUpdateByIdResponse.OK(response) =>
        Right(response)
    }
  }

  case class GetUpdate(
      updateId: String,
      damlValueEncoding: definitions.DamlValueEncoding,
  ) extends InternalBaseCommand[http.GetUpdateByIdV1Response, definitions.UpdateHistoryItem] {
    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetUpdateByIdV1Response] = {
      client.getUpdateByIdV1(
        updateId = updateId,
        damlValueEncoding = Some(damlValueEncoding),
        headers,
      )
    }

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetUpdateByIdV1Response.OK(response) =>
        Right(response)
    }
  }

  case class GetSpliceInstanceNames()
      extends InternalBaseCommand[
        http.GetSpliceInstanceNamesResponse,
        SpliceInstanceNamesConfig,
      ] {
    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetSpliceInstanceNamesResponse] = {
      client.getSpliceInstanceNames(headers)
    }

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetSpliceInstanceNamesResponse.OK(response) =>
        Right(
          SpliceInstanceNamesConfig(
            networkName = response.networkName,
            networkFaviconUrl = response.networkFaviconUrl,
            amuletName = response.amuletName,
            amuletNameAcronym = response.amuletNameAcronym,
            nameServiceName = response.nameServiceName,
            nameServiceNameAcronym = response.nameServiceNameAcronym,
          )
        )
    }
  }

  case class GetMigrationInfo(migrationId: Long)
      extends InternalBaseCommand[
        http.GetMigrationInfoResponse,
        Option[SourceMigrationInfo],
      ] {
    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetMigrationInfoResponse] = {
      client.getMigrationInfo(
        definitions.GetMigrationInfoRequest(migrationId),
        headers,
      )
    }

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetMigrationInfoResponse.OK(response) =>
        for {
          recordTimeRange <- response.recordTimeRange
            .foldLeft[Either[String, Map[DomainId, DomainRecordTimeRange]]](
              Right(Map.empty)
            )((res, row) =>
              for {
                result <- res
                domainId <- Codec.decode(Codec.DomainId)(row.synchronizerId)
                min <- CantonTimestamp.fromInstant(row.min.toInstant)
                max <- CantonTimestamp.fromInstant(row.max.toInstant)
              } yield result + (domainId -> DomainRecordTimeRange(min, max))
            )
        } yield Some(
          SourceMigrationInfo(
            previousMigrationId = response.previousMigrationId,
            recordTimeRange = recordTimeRange,
            complete = response.complete,
          )
        )
      case http.GetMigrationInfoResponse.NotFound(_) =>
        Right(None)
    }
  }

  case class GetUpdatesBefore(
      migrationId: Long,
      domainId: DomainId,
      before: CantonTimestamp,
      atOrAfter: Option[CantonTimestamp],
      count: Int,
  ) extends InternalBaseCommand[
        http.GetUpdatesBeforeResponse,
        Seq[LedgerClient.GetTreeUpdatesResponse],
      ] {
    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetUpdatesBeforeResponse] = {
      client.getUpdatesBefore(
        definitions
          .GetUpdatesBeforeRequest(
            migrationId,
            domainId.toProtoPrimitive,
            before.toInstant.atOffset(java.time.ZoneOffset.UTC),
            atOrAfter.map(_.toInstant.atOffset(java.time.ZoneOffset.UTC)),
            count,
          ),
        headers,
      )
    }

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetUpdatesBeforeResponse.OK(response) =>
        Right(
          response.transactions.map(http =>
            ProtobufJsonScanHttpEncodings.httpToLapiUpdate(http).update
          )
        )
    }
  }

  case object ListVoteRequests
      extends InternalBaseCommand[http.ListDsoRulesVoteRequestsResponse, Seq[
        Contract[VoteRequest.ContractId, VoteRequest]
      ]] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.ListDsoRulesVoteRequestsResponse] =
      client.listDsoRulesVoteRequests(
        headers = headers
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListDsoRulesVoteRequestsResponse.OK(response) =>
      response.dsoRulesVoteRequests
        .traverse(req => Contract.fromHttp(VoteRequest.COMPANION)(req))
        .leftMap(_.toString)
    }
  }

  case class LookupVoteRequest(trackingCid: VoteRequest.ContractId)
      extends InternalBaseCommand[
        http.LookupDsoRulesVoteRequestResponse,
        Option[Contract[VoteRequest.ContractId, VoteRequest]],
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.LookupDsoRulesVoteRequestResponse] =
      client.lookupDsoRulesVoteRequest(
        trackingCid.contractId,
        headers = headers,
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = {
      case http.LookupDsoRulesVoteRequestResponse.NotFound(_) =>
        Right(None)
      case http.LookupDsoRulesVoteRequestResponse.OK(response) =>
        Contract
          .fromHttp(VoteRequest.COMPANION)(response.dsoRulesVoteRequest)
          .map(Some(_))
          .leftMap(_.toString)
    }
  }

  case class ListVoteRequestResults(
      actionName: Option[String],
      accepted: Option[Boolean],
      requester: Option[String],
      effectiveFrom: Option[String],
      effectiveTo: Option[String],
      limit: BigInt,
  ) extends InternalBaseCommand[http.ListVoteRequestResultsResponse, Seq[
        DsoRules_CloseVoteRequestResult
      ]] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.ListVoteRequestResultsResponse] =
      client.listVoteRequestResults(
        body = definitions.ListVoteResultsRequest(
          actionName,
          accepted,
          requester,
          effectiveFrom,
          effectiveTo,
          limit,
        ),
        headers = headers,
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListVoteRequestResultsResponse.OK(response) =>
      Right(
        response.dsoRulesVoteResults
          .map(e =>
            decoder.decodeValue(
              DsoRules_CloseVoteRequestResult.valueDecoder(),
              DsoRules_CloseVoteRequestResult._packageId,
              "Splice.DsoRules",
              "DsoRules_CloseVoteRequestResult",
            )(e)
          )
          .toSeq
      )
    }
  }

  case class ListVoteRequestsByTrackingCid(
      voteRequestCids: Seq[VoteRequest.ContractId]
  ) extends InternalBaseCommand[http.ListVoteRequestsByTrackingCidResponse, Seq[
        Contract[VoteRequest.ContractId, VoteRequest]
      ]] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.ListVoteRequestsByTrackingCidResponse] =
      client.listVoteRequestsByTrackingCid(
        body = definitions.BatchListVotesByVoteRequestsRequest(
          voteRequestContractIds = voteRequestCids.map(_.contractId).toVector
        ),
        headers = headers,
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListVoteRequestsByTrackingCidResponse.OK(response) =>
      response.voteRequests
        .traverse(voteRequest => Contract.fromHttp(VoteRequest.COMPANION)(voteRequest))
        .leftMap(_.toString)
    }
  }
}
