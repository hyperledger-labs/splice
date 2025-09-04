// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.api.client.commands

import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpResponse, StatusCodes, Uri}
import org.apache.pekko.stream.Materializer
import cats.data.EitherT
import cats.syntax.either.*
import cats.syntax.traverse.*
import com.daml.ledger.api.v2.CommandsOuterClass
import com.digitalasset.canton.config.{RequireTypes, TlsClientConfig}
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
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.http.v0.{definitions, scan as http}
import org.lfdecentralizedtrust.tokenstandard.{
  allocation,
  allocationinstruction,
  metadata,
  transferinstruction,
}
import org.lfdecentralizedtrust.splice.http.v0.scan.{
  ForceAcsSnapshotNowResponse,
  GetDateOfMostRecentSnapshotBeforeResponse,
  ScanClient,
}
import org.lfdecentralizedtrust.splice.scan.admin.http.{
  CompactJsonScanHttpEncodings,
  ProtobufJsonScanHttpEncodings,
}
import org.lfdecentralizedtrust.splice.scan.store.db.ScanAggregator
import org.lfdecentralizedtrust.splice.store.HistoryBackfilling.SourceMigrationInfo
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore
import org.lfdecentralizedtrust.splice.store.UpdateHistory.UpdateHistoryResponse
import org.lfdecentralizedtrust.splice.util.{
  ChoiceContextWithDisclosures,
  Codec,
  Contract,
  ContractWithState,
  DomainRecordTimeRange,
  FactoryChoiceWithDisclosures,
  PackageQualifiedName,
  TemplateJsonDecoder,
}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.core.driver.BftBlockOrdererConfig.P2PEndpointConfig
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.core.networking.GrpcNetworking.P2PEndpoint
import com.digitalasset.canton.topology.{
  Member,
  ParticipantId,
  PartyId,
  SequencerId,
  SynchronizerId,
}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.daml.lf.data.Time.Timestamp
import com.google.protobuf.ByteString
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.{
  allocationinstructionv1,
  allocationv1,
  metadatav1,
  transferinstructionv1,
}
import org.lfdecentralizedtrust.tokenstandard.transferinstruction.v1.definitions.GetFactoryRequest as GetTransferFactoryRequest
import org.lfdecentralizedtrust.tokenstandard.allocationinstruction.v1.definitions.GetFactoryRequest as GetAllocationFactoryRequest
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

  abstract class TokenStandardTransferInstructionBaseCommand[Res, Result]
      extends HttpCommand[Res, Result] {
    override type Client = transferinstruction.v1.Client

    override def createClient(host: String)(implicit
        httpClient: HttpClient,
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      transferinstruction.v1.Client.httpClient(HttpClientBuilder().buildClient(), host)
  }

  abstract class TokenStandardAllocationInstructionBaseCommand[Res, Result]
      extends HttpCommand[Res, Result] {
    override type Client = allocationinstruction.v1.Client

    override def createClient(host: String)(implicit
        httpClient: HttpClient,
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      allocationinstruction.v1.Client.httpClient(HttpClientBuilder().buildClient(), host)
  }

  abstract class TokenStandardMetadataBaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = metadata.v1.Client

    override def createClient(host: String)(implicit
        httpClient: HttpClient,
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      metadata.v1.Client
        .httpClient(HttpClientBuilder().buildClient(Set(StatusCodes.NotFound)), host)
  }

  abstract class TokenStandardAllocationBaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = allocation.v1.Client

    override def createClient(host: String)(implicit
        httpClient: HttpClient,
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      allocation.v1.Client.httpClient(HttpClientBuilder().buildClient(), host)
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

  case class ListDsoRulesVoteRequests()
      extends InternalBaseCommand[http.ListDsoRulesVoteRequestsResponse, Seq[
        Contract[VoteRequest.ContractId, VoteRequest]
      ]] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.ListDsoRulesVoteRequestsResponse] =
      client.listDsoRulesVoteRequests(headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListDsoRulesVoteRequestsResponse.OK(response) =>
      response.dsoRulesVoteRequests
        .traverse(c => Contract.fromHttp(VoteRequest.COMPANION)(c))
        .leftMap(_.toString)
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

  case class GetMemberTrafficStatus(synchronizerId: SynchronizerId, memberId: Member)
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
      client.getMemberTrafficStatus(
        synchronizerId.toProtoPrimitive,
        memberId.toProtoPrimitive,
        headers,
      )

    override protected def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetMemberTrafficStatusResponse.OK(response) => Right(response.trafficStatus)
    }
  }

  case class GetPartyToParticipant(synchronizerId: SynchronizerId, partyId: PartyId)
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
      client.getPartyToParticipant(
        synchronizerId.toProtoPrimitive,
        partyId.toProtoPrimitive,
        headers,
      )

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
          // TODO (DACH-NY/canton-network-internal#449): malicious scans can make these decoding fail
          Codec.decode(Codec.SynchronizerId)(domain.domainId).flatMap { synchronizerId =>
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
                DomainSequencers(synchronizerId, sequencers)
              }
          }
        }
    }
  }

  final case class DomainSequencers(synchronizerId: SynchronizerId, sequencers: Seq[DsoSequencer])

  final case class DsoSequencer(
      migrationId: Long,
      id: SequencerId,
      url: String,
      svName: String,
      availableAfter: Instant,
  )
  final case class BftSequencer(
      migrationId: Long,
      id: SequencerId,
      url: String,
  ) {
    private val uri = Uri.parseAbsolute(url)
    val peerId: P2PEndpoint = {
      P2PEndpoint.fromEndpointConfig(
        P2PEndpointConfig(
          uri.authority.host.address(),
          RequireTypes.Port(uri.effectivePort),
          Option.when(uri.scheme == "https")(
            TlsClientConfig(
              None,
              None,
            )
          ),
        )
      )
    }
  }

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
          // TODO (DACH-NY/canton-network-internal#449): malicious scans can make this decoding fail
          Codec.decode(Codec.SynchronizerId)(domain.domainId).map { synchronizerId =>
            // all SVs validate the Uri, so this should only fail to parse for malicious SVs.
            val (malformed, scanList) =
              domain.scans.partitionMap(scan =>
                Try(Uri(scan.publicUrl)).toEither
                  .bimap(scan.publicUrl -> _, url => DsoScan(url, scan.svName))
              )
            DomainScans(synchronizerId, scanList, malformed.toMap)
          }
        }
    }
  }
  final case class DomainScans(
      synchronizerId: SynchronizerId,
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
      party: PartyId,
      recordTime: Option[Instant],
  ) extends InternalBaseCommand[http.GetAcsSnapshotResponse, ByteString] {
    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetAcsSnapshotResponse] = {
      client.getAcsSnapshot(
        party.toProtoPrimitive,
        recordTime.map(t => Timestamp.assertFromInstant(t).toString),
        headers,
      )
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

  case class GetUpdateHistoryV2(
      count: Int,
      after: Option[(Long, String)],
      damlValueEncoding: definitions.DamlValueEncoding,
  ) extends InternalBaseCommand[http.GetUpdateHistoryV2Response, Seq[
        definitions.UpdateHistoryItemV2
      ]] {
    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetUpdateHistoryV2Response] = {
      client.getUpdateHistoryV2(
        // the request is the same as for V1
        definitions.UpdateHistoryRequestV2(
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
      case http.GetUpdateHistoryV2Response.OK(response) =>
        Right(response.transactions)
    }
  }

  @deprecated(message = "Use GetUpdateHistory instead", since = "0.4.2")
  case class GetUpdateHistoryV1(
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

  case class GetEventHistory(
      count: Int,
      after: Option[(Long, String)],
      damlValueEncoding: definitions.DamlValueEncoding,
  ) extends InternalBaseCommand[
        http.GetEventHistoryResponse,
        Seq[definitions.EventHistoryItem],
      ] {
    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetEventHistoryResponse] = {
      client.getEventHistory(
        definitions.EventHistoryRequest(
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
      case http.GetEventHistoryResponse.OK(response) =>
        Right(response.events)
    }
  }

  case class GetEventById(
      updateId: String,
      damlValueEncoding: Option[definitions.DamlValueEncoding],
  ) extends InternalBaseCommand[
        http.GetEventByIdResponse,
        definitions.EventHistoryItem,
      ] {
    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetEventByIdResponse] = {
      client.getEventById(
        updateId = updateId,
        damlValueEncoding = damlValueEncoding,
        headers,
      )
    }

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetEventByIdResponse.OK(response) =>
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
            .foldLeft[Either[String, Map[SynchronizerId, DomainRecordTimeRange]]](
              Right(Map.empty)
            )((res, row) =>
              for {
                result <- res
                synchronizerId <- Codec.decode(Codec.SynchronizerId)(row.synchronizerId)
                min <- CantonTimestamp.fromInstant(row.min.toInstant)
                max <- CantonTimestamp.fromInstant(row.max.toInstant)
              } yield result + (synchronizerId -> DomainRecordTimeRange(min, max))
            )
        } yield Some(
          SourceMigrationInfo(
            previousMigrationId = response.previousMigrationId,
            recordTimeRange = recordTimeRange,
            lastImportUpdateId = response.lastImportUpdateId,
            complete = response.complete,
            // This field was introduced in a later version of the API, consider all old remotes as not complete
            importUpdatesComplete = response.importUpdatesComplete.getOrElse(false),
          )
        )
      case http.GetMigrationInfoResponse.NotFound(_) =>
        Right(None)
    }
  }

  case class GetUpdatesBefore(
      migrationId: Long,
      synchronizerId: SynchronizerId,
      before: CantonTimestamp,
      atOrAfter: Option[CantonTimestamp],
      count: Int,
  ) extends InternalBaseCommand[
        http.GetUpdatesBeforeResponse,
        Seq[UpdateHistoryResponse],
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
            synchronizerId.toProtoPrimitive,
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

  case class GetBackfillingStatus()
      extends InternalBaseCommand[
        http.GetBackfillingStatusResponse,
        definitions.GetBackfillingStatusResponse,
      ] {
    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetBackfillingStatusResponse] = {
      client.getBackfillingStatus(headers)
    }

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetBackfillingStatusResponse.OK(response) =>
        Right(response)
    }
  }

  case class GetTransferFactory(choiceArgs: transferinstructionv1.TransferFactory_Transfer)
      extends TokenStandardTransferInstructionBaseCommand[
        transferinstruction.v1.GetTransferFactoryResponse,
        (
            FactoryChoiceWithDisclosures[
              transferinstructionv1.TransferFactory.ContractId,
              transferinstructionv1.TransferFactory_Transfer,
            ],
            transferinstruction.v1.definitions.TransferFactoryWithChoiceContext.TransferKind,
        ),
      ] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], transferinstruction.v1.GetTransferFactoryResponse] = {
      val json = choiceArgs.toJson
      val circeChoiceArgs = io.circe.parser
        .parse(json)
        .getOrElse(
          throw new RuntimeException(
            s"Failed to parse a just-encoded json: $json. This is not supposed to happen."
          )
        )
      client.getTransferFactory(GetTransferFactoryRequest(circeChoiceArgs, Some(true)), headers)
    }

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[
      transferinstruction.v1.GetTransferFactoryResponse,
      Either[
        String,
        (
            FactoryChoiceWithDisclosures[
              transferinstructionv1.TransferFactory.ContractId,
              transferinstructionv1.TransferFactory_Transfer,
            ],
            transferinstruction.v1.definitions.TransferFactoryWithChoiceContext.TransferKind,
        ),
      ],
    ] = { case transferinstruction.v1.GetTransferFactoryResponse.OK(factory) =>
      for {
        choiceContext <- parseAsChoiceContext(factory.choiceContext.choiceContextData)
      } yield {
        val disclosedContracts =
          factory.choiceContext.disclosedContracts.map(
            fromTransferInstructionHttpDisclosedContract
          )
        val args = new transferinstructionv1.TransferFactory_Transfer(
          choiceArgs.expectedAdmin,
          choiceArgs.transfer,
          new metadatav1.ExtraArgs(
            choiceContext,
            choiceArgs.extraArgs.meta,
          ),
        )
        (
          FactoryChoiceWithDisclosures(
            new transferinstructionv1.TransferFactory.ContractId(factory.factoryId),
            args,
            disclosedContracts,
          ),
          factory.transferKind,
        )
      }
    }
  }

  case class GetTransferFactoryRaw(arg: transferinstruction.v1.definitions.GetFactoryRequest)
      extends TokenStandardTransferInstructionBaseCommand[
        transferinstruction.v1.GetTransferFactoryResponse,
        transferinstruction.v1.definitions.TransferFactoryWithChoiceContext,
      ] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], transferinstruction.v1.GetTransferFactoryResponse] = {
      client.getTransferFactory(arg, headers)
    }
    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case transferinstruction.v1.GetTransferFactoryResponse.OK(response) =>
      Right(response)
    }
  }

  case class GetTransferInstructionAcceptContext(
      transferInstructionId: transferinstructionv1.TransferInstruction.ContractId
  ) extends TokenStandardTransferInstructionBaseCommand[
        transferinstruction.v1.GetTransferInstructionAcceptContextResponse,
        ChoiceContextWithDisclosures,
      ] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], transferinstruction.v1.GetTransferInstructionAcceptContextResponse] = {
      client.getTransferInstructionAcceptContext(
        transferInstructionId.contractId,
        body = transferinstruction.v1.definitions.GetChoiceContextRequest(meta = None),
        headers = headers,
      )
    }

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[
      transferinstruction.v1.GetTransferInstructionAcceptContextResponse,
      Either[String, ChoiceContextWithDisclosures],
    ] = { case transferinstruction.v1.GetTransferInstructionAcceptContextResponse.OK(context) =>
      val disclosedContracts =
        context.disclosedContracts.map(fromTransferInstructionHttpDisclosedContract)
      for {
        choiceContext <- parseAsChoiceContext(context.choiceContextData)
      } yield ChoiceContextWithDisclosures(disclosedContracts, choiceContext)
    }
  }

  case class GetTransferInstructionTransferContextRaw(
      transferInstructionId: String,
      body: transferinstruction.v1.definitions.GetChoiceContextRequest,
  ) extends TokenStandardTransferInstructionBaseCommand[
        transferinstruction.v1.GetTransferInstructionAcceptContextResponse,
        transferinstruction.v1.definitions.ChoiceContext,
      ] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], transferinstruction.v1.GetTransferInstructionAcceptContextResponse] = {
      client.getTransferInstructionAcceptContext(
        transferInstructionId,
        body = body,
        headers = headers,
      )
    }

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case transferinstruction.v1.GetTransferInstructionAcceptContextResponse.OK(context) =>
      Right(context)
    }
  }

  case class GetTransferInstructionRejectContextRaw(
      transferInstructionId: String,
      body: transferinstruction.v1.definitions.GetChoiceContextRequest,
  ) extends TokenStandardTransferInstructionBaseCommand[
        transferinstruction.v1.GetTransferInstructionRejectContextResponse,
        transferinstruction.v1.definitions.ChoiceContext,
      ] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], transferinstruction.v1.GetTransferInstructionRejectContextResponse] = {
      client.getTransferInstructionRejectContext(
        transferInstructionId,
        body = body,
        headers = headers,
      )
    }

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case transferinstruction.v1.GetTransferInstructionRejectContextResponse.OK(context) =>
      Right(context)
    }
  }

  case class GetTransferInstructionRejectContext(
      transferInstructionId: transferinstructionv1.TransferInstruction.ContractId
  ) extends TokenStandardTransferInstructionBaseCommand[
        transferinstruction.v1.GetTransferInstructionRejectContextResponse,
        ChoiceContextWithDisclosures,
      ] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], transferinstruction.v1.GetTransferInstructionRejectContextResponse] = {
      client.getTransferInstructionRejectContext(
        transferInstructionId.contractId,
        body = transferinstruction.v1.definitions.GetChoiceContextRequest(meta = None),
        headers = headers,
      )
    }

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[
      transferinstruction.v1.GetTransferInstructionRejectContextResponse,
      Either[String, ChoiceContextWithDisclosures],
    ] = { case transferinstruction.v1.GetTransferInstructionRejectContextResponse.OK(context) =>
      val disclosedContracts =
        context.disclosedContracts.map(fromTransferInstructionHttpDisclosedContract)
      for {
        choiceContext <- parseAsChoiceContext(context.choiceContextData)
      } yield ChoiceContextWithDisclosures(disclosedContracts, choiceContext)
    }
  }

  case class GetTransferInstructionWithdrawContextRaw(
      transferInstructionId: String,
      body: transferinstruction.v1.definitions.GetChoiceContextRequest,
  ) extends TokenStandardTransferInstructionBaseCommand[
        transferinstruction.v1.GetTransferInstructionWithdrawContextResponse,
        transferinstruction.v1.definitions.ChoiceContext,
      ] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], transferinstruction.v1.GetTransferInstructionWithdrawContextResponse] = {
      client.getTransferInstructionWithdrawContext(
        transferInstructionId,
        body = body,
        headers = headers,
      )
    }

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case transferinstruction.v1.GetTransferInstructionWithdrawContextResponse.OK(context) =>
      Right(context)
    }
  }

  case class GetTransferInstructionWithdrawContext(
      transferInstructionId: transferinstructionv1.TransferInstruction.ContractId
  ) extends TokenStandardTransferInstructionBaseCommand[
        transferinstruction.v1.GetTransferInstructionWithdrawContextResponse,
        ChoiceContextWithDisclosures,
      ] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], transferinstruction.v1.GetTransferInstructionWithdrawContextResponse] = {
      client.getTransferInstructionWithdrawContext(
        transferInstructionId.contractId,
        body = transferinstruction.v1.definitions.GetChoiceContextRequest(meta = None),
        headers = headers,
      )
    }

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[
      transferinstruction.v1.GetTransferInstructionWithdrawContextResponse,
      Either[String, ChoiceContextWithDisclosures],
    ] = { case transferinstruction.v1.GetTransferInstructionWithdrawContextResponse.OK(context) =>
      val disclosedContracts =
        context.disclosedContracts.map(fromTransferInstructionHttpDisclosedContract)
      for {
        choiceContext <- parseAsChoiceContext(context.choiceContextData)
      } yield ChoiceContextWithDisclosures(disclosedContracts, choiceContext)
    }
  }

  case class GetAllocationFactoryRaw(arg: allocationinstruction.v1.definitions.GetFactoryRequest)
      extends TokenStandardAllocationInstructionBaseCommand[
        allocationinstruction.v1.GetAllocationFactoryResponse,
        allocationinstruction.v1.definitions.FactoryWithChoiceContext,
      ] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], allocationinstruction.v1.GetAllocationFactoryResponse] = {
      client.getAllocationFactory(arg, headers)
    }
    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case allocationinstruction.v1.GetAllocationFactoryResponse.OK(response) =>
      Right(response)
    }
  }

  case class GetAllocationFactory(choiceArgs: allocationinstructionv1.AllocationFactory_Allocate)
      extends TokenStandardAllocationInstructionBaseCommand[
        allocationinstruction.v1.GetAllocationFactoryResponse,
        FactoryChoiceWithDisclosures[
          allocationinstructionv1.AllocationFactory.ContractId,
          allocationinstructionv1.AllocationFactory_Allocate,
        ],
      ] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], allocationinstruction.v1.GetAllocationFactoryResponse] = {
      val json = choiceArgs.toJson
      val circeChoiceArgs = io.circe.parser
        .parse(json)
        .getOrElse(
          throw new RuntimeException(
            s"Failed to parse a just-encoded json: $json. This is not supposed to happen."
          )
        )
      client.getAllocationFactory(GetAllocationFactoryRequest(circeChoiceArgs, Some(true)), headers)
    }

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[
      allocationinstruction.v1.GetAllocationFactoryResponse,
      Either[String, FactoryChoiceWithDisclosures[
        allocationinstructionv1.AllocationFactory.ContractId,
        allocationinstructionv1.AllocationFactory_Allocate,
      ]],
    ] = { case allocationinstruction.v1.GetAllocationFactoryResponse.OK(factory) =>
      for {
        choiceContext <- parseAsChoiceContext(factory.choiceContext.choiceContextData)
      } yield {
        val disclosedContracts =
          factory.choiceContext.disclosedContracts.map(
            fromAllocationInstructionHttpDisclosedContract
          )
        val args = new allocationinstructionv1.AllocationFactory_Allocate(
          choiceArgs.expectedAdmin,
          choiceArgs.allocation,
          choiceArgs.requestedAt,
          choiceArgs.inputHoldingCids,
          new metadatav1.ExtraArgs(
            choiceContext,
            choiceArgs.extraArgs.meta,
          ),
        )
        FactoryChoiceWithDisclosures(
          new allocationinstructionv1.AllocationFactory.ContractId(factory.factoryId),
          args,
          disclosedContracts,
        )
      }
    }
  }

  case class GetAllocationTransferContextRaw(
      allocationId: String,
      body: allocation.v1.definitions.GetChoiceContextRequest,
  ) extends TokenStandardAllocationBaseCommand[
        allocation.v1.GetAllocationTransferContextResponse,
        allocation.v1.definitions.ChoiceContext,
      ] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], allocation.v1.GetAllocationTransferContextResponse] = {
      client.getAllocationTransferContext(
        allocationId,
        body = body,
        headers = headers,
      )
    }

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[
      allocation.v1.GetAllocationTransferContextResponse,
      Either[String, allocation.v1.definitions.ChoiceContext],
    ] = { case allocation.v1.GetAllocationTransferContextResponse.OK(context) => Right(context) }
  }

  case class GetAllocationTransferContext(allocationId: allocationv1.Allocation.ContractId)
      extends TokenStandardAllocationBaseCommand[
        allocation.v1.GetAllocationTransferContextResponse,
        ChoiceContextWithDisclosures,
      ] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], allocation.v1.GetAllocationTransferContextResponse] = {
      client.getAllocationTransferContext(
        allocationId.contractId,
        body = allocation.v1.definitions.GetChoiceContextRequest(None),
        headers = headers,
      )
    }

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[
      allocation.v1.GetAllocationTransferContextResponse,
      Either[String, ChoiceContextWithDisclosures],
    ] = { case allocation.v1.GetAllocationTransferContextResponse.OK(context) =>
      val disclosedContracts =
        context.disclosedContracts.map(fromAllocationHttpDisclosedContract)
      for {
        choiceContext <- parseAsChoiceContext(context.choiceContextData)
      } yield ChoiceContextWithDisclosures(disclosedContracts, choiceContext)
    }
  }

  case class GetAllocationCancelContextRaw(
      allocationId: String,
      body: allocation.v1.definitions.GetChoiceContextRequest,
  ) extends TokenStandardAllocationBaseCommand[
        allocation.v1.GetAllocationCancelContextResponse,
        allocation.v1.definitions.ChoiceContext,
      ] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], allocation.v1.GetAllocationCancelContextResponse] = {
      client.getAllocationCancelContext(
        allocationId,
        body = body,
        headers = headers,
      )
    }

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[
      allocation.v1.GetAllocationCancelContextResponse,
      Either[String, allocation.v1.definitions.ChoiceContext],
    ] = { case allocation.v1.GetAllocationCancelContextResponse.OK(context) => Right(context) }
  }

  case class GetAllocationCancelContext(allocationId: allocationv1.Allocation.ContractId)
      extends TokenStandardAllocationBaseCommand[
        allocation.v1.GetAllocationCancelContextResponse,
        ChoiceContextWithDisclosures,
      ] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], allocation.v1.GetAllocationCancelContextResponse] = {
      client.getAllocationCancelContext(
        allocationId.contractId,
        body = allocation.v1.definitions.GetChoiceContextRequest(None),
        headers = headers,
      )
    }

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[
      allocation.v1.GetAllocationCancelContextResponse,
      Either[String, ChoiceContextWithDisclosures],
    ] = { case allocation.v1.GetAllocationCancelContextResponse.OK(context) =>
      val disclosedContracts =
        context.disclosedContracts.map(fromAllocationHttpDisclosedContract)
      for {
        choiceContext <- parseAsChoiceContext(context.choiceContextData)
      } yield ChoiceContextWithDisclosures(disclosedContracts, choiceContext)
    }
  }

  case class GetAllocationWithdrawContextRaw(
      allocationId: String,
      body: allocation.v1.definitions.GetChoiceContextRequest,
  ) extends TokenStandardAllocationBaseCommand[
        allocation.v1.GetAllocationWithdrawContextResponse,
        allocation.v1.definitions.ChoiceContext,
      ] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], allocation.v1.GetAllocationWithdrawContextResponse] = {
      client.getAllocationWithdrawContext(
        allocationId,
        body = body,
        headers = headers,
      )
    }

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[
      allocation.v1.GetAllocationWithdrawContextResponse,
      Either[String, allocation.v1.definitions.ChoiceContext],
    ] = { case allocation.v1.GetAllocationWithdrawContextResponse.OK(context) => Right(context) }
  }

  case class GetAllocationWithdrawContext(allocationId: allocationv1.Allocation.ContractId)
      extends TokenStandardAllocationBaseCommand[
        allocation.v1.GetAllocationWithdrawContextResponse,
        ChoiceContextWithDisclosures,
      ] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], allocation.v1.GetAllocationWithdrawContextResponse] = {
      client.getAllocationWithdrawContext(
        allocationId.contractId,
        body = allocation.v1.definitions.GetChoiceContextRequest(None),
        headers = headers,
      )
    }

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[
      allocation.v1.GetAllocationWithdrawContextResponse,
      Either[String, ChoiceContextWithDisclosures],
    ] = { case allocation.v1.GetAllocationWithdrawContextResponse.OK(context) =>
      val disclosedContracts =
        context.disclosedContracts.map(fromAllocationHttpDisclosedContract)
      for {
        choiceContext <- parseAsChoiceContext(context.choiceContextData)
      } yield ChoiceContextWithDisclosures(disclosedContracts, choiceContext)
    }
  }

  final case object GetRegistryInfo
      extends TokenStandardMetadataBaseCommand[
        metadata.v1.GetRegistryInfoResponse,
        metadata.v1.definitions.GetRegistryInfoResponse,
      ] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], metadata.v1.GetRegistryInfoResponse] =
      client.getRegistryInfo(headers)

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[
      metadata.v1.GetRegistryInfoResponse,
      Either[String, metadata.v1.definitions.GetRegistryInfoResponse],
    ] = { case metadata.v1.GetRegistryInfoResponse.OK(response) =>
      Right(response)
    }
  }

  final case class LookupInstrument(instrumentId: String)
      extends TokenStandardMetadataBaseCommand[metadata.v1.GetInstrumentResponse, Option[
        metadata.v1.definitions.Instrument
      ]] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], metadata.v1.GetInstrumentResponse] =
      client.getInstrument(instrumentId, headers)

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[
      metadata.v1.GetInstrumentResponse,
      Either[String, Option[metadata.v1.definitions.Instrument]],
    ] = {
      case metadata.v1.GetInstrumentResponse.OK(response) =>
        Right(Some(response))
      case metadata.v1.GetInstrumentResponse.NotFound(response) =>
        Right(None)
    }
  }

  final case class ListInstruments(pageSize: Option[Int], pageToken: Option[String])
      extends TokenStandardMetadataBaseCommand[metadata.v1.ListInstrumentsResponse, Seq[
        metadata.v1.definitions.Instrument
      ]] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], metadata.v1.ListInstrumentsResponse] =
      client.listInstruments(pageSize, pageToken, headers)

    override protected def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[
      metadata.v1.ListInstrumentsResponse,
      Either[String, Seq[metadata.v1.definitions.Instrument]],
    ] = { case metadata.v1.ListInstrumentsResponse.OK(response) =>
      Right(response.instruments)
    }
  }

  private def parseAsChoiceContext(
      contextJsonString: io.circe.Json
  ): Either[String, metadatav1.ChoiceContext] =
    Either
      .fromTry(Try(metadatav1.ChoiceContext.fromJson(contextJsonString.noSpaces)))
      .leftMap(_.toString)

  private def fromAllocationInstructionHttpDisclosedContract(
      disclosedContract: allocationinstruction.v1.definitions.DisclosedContract
  ): CommandsOuterClass.DisclosedContract = {
    CommandsOuterClass.DisclosedContract
      .newBuilder()
      .setContractId(disclosedContract.contractId)
      .setCreatedEventBlob(
        ByteString.copyFrom(
          java.util.Base64.getDecoder.decode(disclosedContract.createdEventBlob)
        )
      )
      .setSynchronizerId(disclosedContract.synchronizerId)
      .setTemplateId(
        CompactJsonScanHttpEncodings.parseTemplateId(disclosedContract.templateId).toProto
      )
      .build()
  }

  private def fromAllocationHttpDisclosedContract(
      disclosedContract: allocation.v1.definitions.DisclosedContract
  ): CommandsOuterClass.DisclosedContract = {
    CommandsOuterClass.DisclosedContract
      .newBuilder()
      .setContractId(disclosedContract.contractId)
      .setCreatedEventBlob(
        ByteString.copyFrom(
          java.util.Base64.getDecoder.decode(disclosedContract.createdEventBlob)
        )
      )
      .setSynchronizerId(disclosedContract.synchronizerId)
      .setTemplateId(
        CompactJsonScanHttpEncodings.parseTemplateId(disclosedContract.templateId).toProto
      )
      .build()
  }

  private def fromTransferInstructionHttpDisclosedContract(
      disclosedContract: transferinstruction.v1.definitions.DisclosedContract
  ): CommandsOuterClass.DisclosedContract = {
    CommandsOuterClass.DisclosedContract
      .newBuilder()
      .setContractId(disclosedContract.contractId)
      .setCreatedEventBlob(
        ByteString.copyFrom(
          java.util.Base64.getDecoder.decode(disclosedContract.createdEventBlob)
        )
      )
      .setSynchronizerId(disclosedContract.synchronizerId)
      .setTemplateId(
        CompactJsonScanHttpEncodings.parseTemplateId(disclosedContract.templateId).toProto
      )
      .build()
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

  case class ListBftSequencers()
      extends InternalBaseCommand[
        http.ListSvBftSequencersResponse,
        Seq[BftSequencer],
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.ListSvBftSequencersResponse] =
      client.listSvBftSequencers(headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.ListSvBftSequencersResponse.OK(response) =>
        response.bftSequencers.traverse { sequencer =>
          Codec.decode(Codec.Sequencer)(sequencer.id).map { sequencerId =>
            BftSequencer(
              sequencer.migrationId,
              sequencerId,
              sequencer.p2pUrl,
            )
          }
        }
    }

  }

  case class GetImportUpdates(
      migrationId: Long,
      afterUpdateId: String,
      limit: Int,
  ) extends InternalBaseCommand[
        http.GetImportUpdatesResponse,
        Seq[UpdateHistoryResponse],
      ] {
    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetImportUpdatesResponse] = {
      client.getImportUpdates(
        definitions
          .GetImportUpdatesRequest(
            migrationId,
            afterUpdateId,
            limit,
          ),
        headers,
      )
    }

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetImportUpdatesResponse.OK(response) =>
        Right(
          response.transactions.map(http =>
            ProtobufJsonScanHttpEncodings.httpToLapiUpdate(http).update
          )
        )
    }
  }
}
