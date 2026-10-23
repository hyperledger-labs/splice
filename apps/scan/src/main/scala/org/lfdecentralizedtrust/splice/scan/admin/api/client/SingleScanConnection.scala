// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.api.client

import cats.data.OptionT
import com.daml.metrics.api.MetricsContext
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{
  FeaturedAppRight,
  UnclaimedDevelopmentFundCoupon,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.{
  AmuletRules,
  TransferPreapproval,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.externalpartyamuletrules.{
  ExternalPartyAmuletRules,
  TransferCommandCounter,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.{
  IssuingMiningRound,
  OpenMiningRound,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.ans.AnsRules
import org.lfdecentralizedtrust.splice.config.UpgradesConfig
import org.lfdecentralizedtrust.splice.environment.{
  BaseAppConnection,
  HttpAppConnection,
  RetryProvider,
  SpliceLedgerClient,
}
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.http.v0.definitions.{
  BulkStorageBucket,
  DamlValueEncoding,
  GetBulkObjectChecksumsResponse,
  GetBulkObjectsProgressResponse,
  GetRewardAccountingActivityTotalsResponse,
  GetRewardAccountingBatchResponse,
  GetRewardAccountingRootHashResponse,
  HoldingsSummaryRequestV1,
  HoldingsSummaryResponse,
  HoldingsSummaryResponseV1,
  LookupTransferCommandStatusResponse,
  MigrationSchedule,
}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient
import org.lfdecentralizedtrust.splice.scan.config.ScanAppClientConfig
import org.lfdecentralizedtrust.splice.store.HistoryBackfilling.SourceMigrationInfo
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.BulkStorageObjects
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.scan.store.bulk.BulkObjectKind
import org.lfdecentralizedtrust.splice.store.VoteResultsFilters
import org.lfdecentralizedtrust.splice.store.UpdateHistory.UpdateHistoryResponse
import org.lfdecentralizedtrust.splice.util.{
  ChoiceContextWithDisclosures,
  Contract,
  ContractWithState,
  DsoInfo,
  DsoInfoV1,
  FactoryChoiceWithDisclosures,
  TemplateJsonDecoder,
}
import org.lfdecentralizedtrust.tokenstandard.{
  allocation,
  allocationinstruction,
  metadata,
  transferinstruction,
}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{ParticipantId, PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.google.protobuf.ByteString
import org.apache.pekko.stream.Materializer
import com.daml.metrics.api.MetricsContext.Implicits.empty

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import com.digitalasset.canton.data.CantonTimestamp
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  DsoRules_CloseVoteRequestResult,
  VoteRequest,
}
import org.apache.pekko.http.scaladsl.model.{HttpHeader, StatusCodes, Uri}
import org.lfdecentralizedtrust.splice.admin.api.client.commands.{HttpCommand, HttpCommandException}
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.transferinstructionv1
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.transferinstructionv2
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.allocationv1
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.allocationv2
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.allocationinstructionv1
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.allocationinstructionv2
import org.lfdecentralizedtrust.splice.http.v0.definitions.HoldingsSummaryRequest.RecordTimeMatch
import org.lfdecentralizedtrust.splice.metrics.ScanConnectionMetrics
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.BftSequencer
import org.lfdecentralizedtrust.splice.scan.store.db.DbVotesStore.{
  PeerVoteResult,
  VotesBackfillCursor,
}

import scala.util.{Failure, Success}

/** Connection to the admin API of CC Scan. This is used by other apps
  * to query for the DSO party id.
  */
class SingleScanConnection private[client] (
    val config: ScanAppClientConfig,
    upgradesConfig: UpgradesConfig,
    protected val clock: Clock,
    retryProvider: RetryProvider,
    outerLoggerFactory: NamedLoggerFactory,
    connectionMetrics: Option[ScanConnectionMetrics],
)(implicit
    protected val ec: ExecutionContextExecutor,
    tc: TraceContext,
    protected val mat: Materializer,
    httpClient: HttpClient,
    templateDecoder: TemplateJsonDecoder,
) extends HttpAppConnection(
      config.adminApi,
      upgradesConfig,
      "scan",
      retryProvider,
      outerLoggerFactory.append("scan-connection", config.adminApi.url.toString),
    )
    with ScanConnection
    with BackfillingScanConnection
    with HasUrl {

  override def runHttpCmd[Res, Result, Client](
      url: Uri,
      command: HttpCommand[Res, Result, Client],
      headers: List[HttpHeader] = List.empty[HttpHeader],
  )(implicit
      templateDecoder: TemplateJsonDecoder,
      httpClient: HttpClient,
      tc: TraceContext,
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[Result] = {
    connectionMetrics match {
      case Some(metrics) =>
        MetricsContext.withExtraMetricLabels(
          ("scan_connection", url.authority.host.address()),
          ("request", command.fullName),
        ) { m =>
          val timer = metrics.latencyPerConnection.startAsync()(m)
          super
            .runHttpCmd(url, command, headers)
            .andThen {
              case Failure(e) =>
                MetricsContext.withMetricLabels(
                  ("outcome", e.getClass.getSimpleName),
                  ("http_status", SingleScanConnection.httpStatusLabel(e)),
                ) { implicit ec2 =>
                  metrics.callPerConnection.mark()(m.merge(ec2))
                }
                timer.stop()(m)
              case Success(_) =>
                MetricsContext.withMetricLabels(("outcome", "ok")) { implicit ec2 =>
                  metrics.callPerConnection.mark()(m.merge(ec2))
                }
                timer.stop()(m)
            }
        }
      case None => super.runHttpCmd(url, command, headers)
    }
  }

  def url = config.adminApi.url

  // cached DSO reference. Never changes.
  private val dsoRef: AtomicReference[Option[PartyId]] = new AtomicReference(None)

  /** Query for the DSO party id. This caches the result internally so
    * clients can call this repeatedly without having to implement caching themselves.
    */
  override def getDsoPartyId()(implicit ec: ExecutionContext, tc: TraceContext): Future[PartyId] = {
    val prev = dsoRef.get()
    prev match {
      case Some(partyId) => Future.successful(partyId)
      case None =>
        for {
          partyId <- runHttpCmd(config.adminApi.url, HttpScanAppClient.GetDsoPartyId(List()))
        } yield {
          // The party id never changes so we don’t need to worry about concurrent setters writing different values.
          dsoRef.set(Some(partyId))
          partyId
        }
    }
  }

  override def getDsoInfo()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[DsoInfo] = {
    runHttpCmd(config.adminApi.url, HttpScanAppClient.GetDsoInfo(List()))
  }

  override def getDsoInfoV1()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[DsoInfoV1] = {
    runHttpCmd(config.adminApi.url, HttpScanAppClient.GetDsoInfoV1(List()))
  }

  override def getHoldingsSummaryAt(
      at: CantonTimestamp,
      migrationId: Long,
      ownerPartyIds: Vector[PartyId],
      recordTimeMatch: Option[RecordTimeMatch],
      asOfRound: Option[Long],
  )(implicit tc: TraceContext): Future[Option[HoldingsSummaryResponse]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetHoldingsSummaryAt(
        at.toInstant.atOffset(java.time.ZoneOffset.UTC),
        migrationId,
        ownerPartyIds,
        recordTimeMatch,
        asOfRound,
      ),
    )
  }

  override def getHoldingsSummaryAtV1(
      at: CantonTimestamp,
      migrationId: Long,
      ownerPartyIds: Vector[PartyId],
      recordTimeMatch: Option[HoldingsSummaryRequestV1.RecordTimeMatch],
  )(implicit tc: TraceContext): Future[Option[HoldingsSummaryResponseV1]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetHoldingsSummaryAtV1(
        at.toInstant.atOffset(java.time.ZoneOffset.UTC),
        migrationId,
        ownerPartyIds,
        recordTimeMatch,
      ),
    )
  }

  override def getAmuletRulesWithState()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ContractWithState[AmuletRules.ContractId, AmuletRules]] = {
    getAmuletRulesWithState(None)
  }

  def getAmuletRulesWithState(
      cachedAmuletRules: Option[ContractWithState[AmuletRules.ContractId, AmuletRules]]
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ContractWithState[AmuletRules.ContractId, AmuletRules]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetAmuletRules(cachedAmuletRules),
    )
  }

  override def getDsoRules(
  )(implicit
      tc: TraceContext
  ): Future[Contract[DsoRules.ContractId, DsoRules]] = {
    getDsoInfo().map(_.dsoRules.contract)
  }

  override def listVoteRequests()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Seq[Contract[VoteRequest.ContractId, VoteRequest]]] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.ListDsoRulesVoteRequests(),
    )

  override def getExternalPartyAmuletRules()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ContractWithState[ExternalPartyAmuletRules.ContractId, ExternalPartyAmuletRules]] = {
    getExternalPartyAmuletRules(None)
  }

  def getExternalPartyAmuletRules(
      cachedExternalPartyAmuletRules: Option[
        ContractWithState[ExternalPartyAmuletRules.ContractId, ExternalPartyAmuletRules]
      ]
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ContractWithState[ExternalPartyAmuletRules.ContractId, ExternalPartyAmuletRules]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetExternalPartyAmuletRules(cachedExternalPartyAmuletRules),
    )
  }

  override def getAnsRules()(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[ContractWithState[AnsRules.ContractId, AnsRules]] = {
    getAnsRules(None)
  }

  def getAnsRules(cachedAnsRules: Option[ContractWithState[AnsRules.ContractId, AnsRules]])(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[ContractWithState[AnsRules.ContractId, AnsRules]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetAnsRules(cachedAnsRules),
    )
  }

  def lookupAnsEntryByParty(
      id: PartyId
  )(implicit
      tc: TraceContext
  ): Future[Option[org.lfdecentralizedtrust.splice.http.v0.definitions.AnsEntry]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.LookupAnsEntryByParty(id),
    )
  }

  def lookupAnsEntryByName(
      name: String
  )(implicit
      tc: TraceContext
  ): Future[Option[org.lfdecentralizedtrust.splice.http.v0.definitions.AnsEntry]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.LookupAnsEntryByName(name),
    )
  }

  def listAnsEntries(namePrefix: Option[String], pageSize: Int)(implicit
      tc: TraceContext
  ): Future[Seq[org.lfdecentralizedtrust.splice.http.v0.definitions.AnsEntry]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.ListAnsEntries(namePrefix, pageSize),
    )
  }

  override def getOpenAndIssuingMiningRounds()(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[
    (
        Seq[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]],
        Seq[ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]],
    )
  ] = {
    getOpenAndIssuingMiningRounds(Seq.empty, Seq.empty).map { case (open, issuing, _) =>
      (open, issuing)
    }
  }

  def getOpenAndIssuingMiningRounds(
      cachedOpenRounds: Seq[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]],
      cachedIssuingRounds: Seq[ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]],
  )(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[
    (
        Seq[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]],
        Seq[ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]],
        BigInt,
    )
  ] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetSortedOpenAndIssuingMiningRounds(
        cachedOpenRounds,
        cachedIssuingRounds,
      ),
    )
  }

  override def lookupFeaturedAppRight(providerPartyId: PartyId)(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[Option[Contract[FeaturedAppRight.ContractId, FeaturedAppRight]]] = {
    runHttpCmd(config.adminApi.url, HttpScanAppClient.LookupFeaturedAppRight(providerPartyId))
  }

  override def listFeaturedAppRightsByProvider(providerPartyId: PartyId)(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[Seq[Contract[FeaturedAppRight.ContractId, FeaturedAppRight]]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.ListFeaturedAppRightsByProvider(providerPartyId),
    )
  }

  override def lookupFeaturedAppRightByContractId(contractId: String)(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[Option[Contract[FeaturedAppRight.ContractId, FeaturedAppRight]]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.LookupFeaturedAppRightByContractId(contractId),
    )
  }

  override def listFeaturedAppRights()(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[Seq[Contract[FeaturedAppRight.ContractId, FeaturedAppRight]]] = {
    runHttpCmd(config.adminApi.url, HttpScanAppClient.ListFeaturedAppRight)
  }

  override def listDsoSequencers()(implicit
      tc: TraceContext
  ): Future[Seq[HttpScanAppClient.DomainSequencers]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.ListDsoSequencers(),
    )
  }

  override def lookupRollForwardLsu()(implicit
      tc: TraceContext
  ): Future[Option[HttpScanAppClient.RollForwardLsu]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.LookupRollForwardLsu(),
    )
  }

  override def getLsu()(implicit
      tc: TraceContext
  ): Future[Option[HttpScanAppClient.Lsu]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetLsu(),
    )
  }

  override def getPartyToParticipant(
      synchronizerId: SynchronizerId,
      partyId: PartyId,
  )(implicit tc: TraceContext): Future[Seq[ParticipantId]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetPartyToParticipantV1(synchronizerId, partyId),
    )
  }

  override def listDsoScans()(implicit
      tc: TraceContext
  ): Future[Seq[HttpScanAppClient.DomainScans]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.ListDsoScans(),
    ).map(_.map { scans =>
      if (scans.malformed.nonEmpty) {
        logger.warn(
          s"Malformed scans found for domain ${scans.synchronizerId}: ${scans.malformed.keys}. This likely indicates malicious SVs."
        )
      }
      scans
    })
  }

  def getAcsSnapshot(partyId: PartyId, recordTime: Option[Instant])(implicit
      tc: TraceContext
  ): Future[ByteString] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetAcsSnapshot(partyId, recordTime),
    )
  }

  override def getMigrationSchedule()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): OptionT[Future, MigrationSchedule] =
    OptionT(
      runHttpCmd(
        config.adminApi.url,
        HttpScanAppClient.GetMigrationSchedule(),
      )
    )

  override def getMigrationId()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Long] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetMigrationId(),
    )

  override def lookupTransferPreapprovalByParty(receiver: PartyId)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[ContractWithState[TransferPreapproval.ContractId, TransferPreapproval]]] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.LookupTransferPreapprovalByParty(receiver),
    )

  override def lookupTransferCommandCounterByParty(receiver: PartyId)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[ContractWithState[TransferCommandCounter.ContractId, TransferCommandCounter]]] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.LookupTransferCommandCounterByParty(receiver),
    )

  override def lookupTransferCommandStatus(sender: PartyId, nonce: Long)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[LookupTransferCommandStatusResponse]] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.LookupTransferCommandStatus(sender, nonce),
    )

  override def getMigrationInfo(migrationId: Long)(implicit
      tc: TraceContext
  ): Future[Option[SourceMigrationInfo]] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetMigrationInfo(
        migrationId
      ),
    )

  override def getUpdatesBefore(
      migrationId: Long,
      synchronizerId: SynchronizerId,
      before: CantonTimestamp,
      atOrAfter: Option[CantonTimestamp],
      count: Int,
  )(implicit tc: TraceContext): Future[Seq[UpdateHistoryResponse]] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetUpdatesBefore(
        migrationId,
        synchronizerId,
        before,
        atOrAfter,
        count,
      ),
    )

  override def getImportUpdates(
      migrationId: Long,
      afterUpdateId: String,
      count: Int,
  )(implicit tc: TraceContext): Future[Seq[UpdateHistoryResponse]] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetImportUpdates(
        migrationId,
        afterUpdateId,
        count,
      ),
    )

  def getVotesAvailability(before: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[definitions.VotesAvailability] =
    runHttpCmd(config.adminApi.url, HttpScanAppClient.GetVotesBackfillingStatus(before))

  override def getVotesBefore(
      before: VotesBackfillCursor,
      count: Int,
  )(implicit tc: TraceContext): Future[Seq[PeerVoteResult]] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetVotesBefore(before, count),
    )

  override def listVoteRequestResults(
      filters: VoteResultsFilters,
      limit: Int,
      pageToken: Option[BigInt] = None,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[(Seq[DsoRules_CloseVoteRequestResult], Option[BigInt])] = runHttpCmd(
    config.adminApi.url,
    HttpScanAppClient.ListVoteRequestResults(
      filters,
      limit,
      pageToken,
    ),
  )

  override def countVoteRequestResults(
      filters: VoteResultsFilters
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Long] = runHttpCmd(
    config.adminApi.url,
    HttpScanAppClient.CountVoteRequestResults(filters),
  )

  override def getPreviousSvRewardWeight(svParty: String, effectiveBefore: Option[String])(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[Long]] = runHttpCmd(
    config.adminApi.url,
    HttpScanAppClient.GetPreviousSvRewardWeight(svParty, effectiveBefore),
  )

  override def listUnclaimedDevelopmentFundCoupons()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[
    Seq[
      ContractWithState[UnclaimedDevelopmentFundCoupon.ContractId, UnclaimedDevelopmentFundCoupon]
    ]
  ] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.ListUnclaimedDevelopmentFundCoupons(),
    )

  def getTransferInstructionAcceptContext(
      instructionCid: transferinstructionv1.TransferInstruction.ContractId
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ChoiceContextWithDisclosures] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetTransferInstructionAcceptContext(instructionCid),
    )

  def getTransferInstructionAcceptContextV2(
      instructionCid: transferinstructionv2.TransferInstruction.ContractId
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ChoiceContextWithDisclosures] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetTransferInstructionAcceptContextV2(instructionCid),
    )

  def getTransferInstructionRejectContext(
      instructionCid: transferinstructionv1.TransferInstruction.ContractId
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ChoiceContextWithDisclosures] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetTransferInstructionRejectContext(instructionCid),
    )

  def getTransferInstructionRejectContextV2(
      instructionCid: transferinstructionv2.TransferInstruction.ContractId
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ChoiceContextWithDisclosures] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetTransferInstructionRejectContextV2(instructionCid),
    )

  def getTransferInstructionWithdrawContext(
      instructionCid: transferinstructionv1.TransferInstruction.ContractId
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ChoiceContextWithDisclosures] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetTransferInstructionWithdrawContext(instructionCid),
    )

  def getTransferInstructionWithdrawContextV2(
      instructionCid: transferinstructionv2.TransferInstruction.ContractId
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ChoiceContextWithDisclosures] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetTransferInstructionWithdrawContextV2(instructionCid),
    )

  def getTransferInstructionAcceptContextRaw(
      transferInstructionCid: String,
      body: transferinstruction.v1.definitions.GetChoiceContextRequest,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[transferinstruction.v1.definitions.ChoiceContext] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetTransferInstructionTransferContextRaw(transferInstructionCid, body),
    )

  def getTransferInstructionAcceptContextV2Raw(
      transferInstructionCid: String,
      body: transferinstruction.v2.definitions.GetChoiceContextRequest,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[transferinstruction.v2.definitions.ChoiceContext] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetTransferInstructionTransferContextV2Raw(transferInstructionCid, body),
    )

  def getTransferInstructionWithdrawContextRaw(
      transferInstructionCid: String,
      body: transferinstruction.v1.definitions.GetChoiceContextRequest,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[transferinstruction.v1.definitions.ChoiceContext] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetTransferInstructionWithdrawContextRaw(transferInstructionCid, body),
    )

  def getTransferInstructionWithdrawContextV2Raw(
      transferInstructionCid: String,
      body: transferinstruction.v2.definitions.GetChoiceContextRequest,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[transferinstruction.v2.definitions.ChoiceContext] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetTransferInstructionWithdrawContextV2Raw(transferInstructionCid, body),
    )

  def getTransferInstructionRejectContextRaw(
      transferInstructionCid: String,
      body: transferinstruction.v1.definitions.GetChoiceContextRequest,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[transferinstruction.v1.definitions.ChoiceContext] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetTransferInstructionRejectContextRaw(transferInstructionCid, body),
    )

  def getTransferInstructionRejectContextV2Raw(
      transferInstructionCid: String,
      body: transferinstruction.v2.definitions.GetChoiceContextRequest,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[transferinstruction.v2.definitions.ChoiceContext] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetTransferInstructionRejectContextV2Raw(transferInstructionCid, body),
    )

  def getTransferFactory(choiceArgs: transferinstructionv1.TransferFactory_Transfer)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[
    (
        FactoryChoiceWithDisclosures[
          transferinstructionv1.TransferFactory.ContractId,
          transferinstructionv1.TransferFactory_Transfer,
        ],
        transferinstruction.v1.definitions.TransferFactoryWithChoiceContext.TransferKind,
    )
  ] =
    runHttpCmd(config.adminApi.url, HttpScanAppClient.GetTransferFactory(choiceArgs))

  def getTransferFactoryV2(choiceArgs: transferinstructionv2.TransferFactory_Transfer)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[
    (
        FactoryChoiceWithDisclosures[
          transferinstructionv2.TransferFactory.ContractId,
          transferinstructionv2.TransferFactory_Transfer,
        ],
        transferinstruction.v2.definitions.TransferFactoryWithChoiceContext.TransferKind,
    )
  ] =
    runHttpCmd(config.adminApi.url, HttpScanAppClient.GetTransferFactoryV2(choiceArgs))

  def getTransferFactoryRaw(arg: transferinstruction.v1.definitions.GetFactoryRequest)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[transferinstruction.v1.definitions.TransferFactoryWithChoiceContext] =
    runHttpCmd(config.adminApi.url, HttpScanAppClient.GetTransferFactoryRaw(arg))

  def getTransferFactoryV2Raw(arg: transferinstruction.v2.definitions.GetFactoryRequest)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[transferinstruction.v2.definitions.TransferFactoryWithChoiceContext] =
    runHttpCmd(config.adminApi.url, HttpScanAppClient.GetTransferFactoryV2Raw(arg))

  def listSvBftSequencers()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Seq[BftSequencer]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.ListBftSequencers(),
    )
  }

  def getRegistryInfo()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[metadata.v1.definitions.GetRegistryInfoResponse] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetRegistryInfo,
    )

  def lookupInstrument(instrumentId: String)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[metadata.v1.definitions.Instrument]] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.LookupInstrument(instrumentId),
    )

  def listInstruments(pageSize: Option[Int], pageToken: Option[String])(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Seq[metadata.v1.definitions.Instrument]] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.ListInstruments(pageSize, pageToken),
    )

  def getAllocationTransferContext(
      allocationCid: allocationv1.Allocation.ContractId
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ChoiceContextWithDisclosures] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetAllocationTransferContext(allocationCid),
    )

  def getAllocationTransferContextRaw(
      allocationCid: String,
      body: allocation.v1.definitions.GetChoiceContextRequest,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[allocation.v1.definitions.ChoiceContext] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetAllocationTransferContextRaw(allocationCid, body),
    )

  def getAllocationCancelContextRaw(
      allocationCid: String,
      body: allocation.v1.definitions.GetChoiceContextRequest,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[allocation.v1.definitions.ChoiceContext] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetAllocationCancelContextRaw(allocationCid, body),
    )

  def getSettlementFactoryRaw(
      body: allocation.v2.definitions.GetFactoryRequest
  )(implicit ec: ExecutionContext, tc: TraceContext) =
    runHttpCmd(config.adminApi.url, HttpScanAppClient.GetSettlementFactoryV2Raw(body))

  def getAllocationV2CancelContextRaw(
      allocationCid: String,
      body: allocation.v2.definitions.GetChoiceContextRequest,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[allocation.v2.definitions.ChoiceContext] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetAllocationV2CancelContextRaw(allocationCid, body),
    )

  def getAllocationV2WithdrawContextRaw(
      allocationCid: String,
      body: allocation.v2.definitions.GetChoiceContextRequest,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[allocation.v2.definitions.ChoiceContext] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetAllocationV2WithdrawContextRaw(allocationCid, body),
    )

  def getAllocationWithdrawContextRaw(
      allocationCid: String,
      body: allocation.v1.definitions.GetChoiceContextRequest,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[allocation.v1.definitions.ChoiceContext] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetAllocationWithdrawContextRaw(allocationCid, body),
    )

  def getAllocationCancelContext(
      allocationCid: allocationv1.Allocation.ContractId
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ChoiceContextWithDisclosures] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetAllocationCancelContext(allocationCid),
    )

  def getAllocationWithdrawContextV1(
      allocationCid: allocationv1.Allocation.ContractId
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ChoiceContextWithDisclosures] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetAllocationWithdrawContext(allocationCid),
    )

  def getAllocationWithdrawContextV2(
      allocationCid: allocationv2.Allocation.ContractId
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ChoiceContextWithDisclosures] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetAllocationWithdrawContextV2(allocationCid),
    )

  def getAllocationFactory(choiceArgs: allocationinstructionv1.AllocationFactory_Allocate)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[
    FactoryChoiceWithDisclosures[
      allocationinstructionv1.AllocationFactory.ContractId,
      allocationinstructionv1.AllocationFactory_Allocate,
    ]
  ] =
    runHttpCmd(config.adminApi.url, HttpScanAppClient.GetAllocationFactory(choiceArgs))

  def getAllocationFactoryV2(choiceArgs: allocationinstructionv2.AllocationFactory_Allocate)(
      implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[
    FactoryChoiceWithDisclosures[
      allocationinstructionv2.AllocationFactory.ContractId,
      allocationinstructionv2.AllocationFactory_Allocate,
    ]
  ] =
    runHttpCmd(config.adminApi.url, HttpScanAppClient.GetAllocationFactoryV2(choiceArgs))

  def getAllocationFactoryV2Raw(arg: allocationinstruction.v2.definitions.GetFactoryRequest)(
      implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[allocationinstruction.v2.definitions.FactoryWithChoiceContext] =
    runHttpCmd(config.adminApi.url, HttpScanAppClient.GetAllocationFactoryV2Raw(arg))

  def getAllocationFactoryRaw(arg: allocationinstruction.v1.definitions.GetFactoryRequest)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[allocationinstruction.v1.definitions.FactoryWithChoiceContext] =
    runHttpCmd(config.adminApi.url, HttpScanAppClient.GetAllocationFactoryRaw(arg))

  def getAllocationInstructionAcceptContextRaw(
      allocationInstructionCid: String,
      body: allocationinstruction.v2.definitions.GetChoiceContextRequest,
  )(implicit tc: TraceContext): Future[allocationinstruction.v2.definitions.ChoiceContext] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetAllocationInstructionAcceptContextRaw(allocationInstructionCid, body),
    )

  def getAllocationInstructionWithdrawContext(
      allocationInstructionCid: String,
      body: allocationinstruction.v2.definitions.GetChoiceContextRequest,
  )(implicit tc: TraceContext): Future[allocationinstruction.v2.definitions.ChoiceContext] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetAllocationInstructionWithdrawContextRaw(allocationInstructionCid, body),
    )

  override def getActivePhysicalSynchronizerSerial()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[NonNegativeInt] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetActivePhysicalSynchronizerSerial(),
    )

  override def getRewardAccountingActivityTotals(roundNumber: Long)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[GetRewardAccountingActivityTotalsResponse] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetRewardAccountingActivityTotals(roundNumber),
    )

  override def getRewardAccountingRootHash(roundNumber: Long)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[GetRewardAccountingRootHashResponse] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetRewardAccountingRootHash(roundNumber),
    )

  override def getRewardAccountingBatch(roundNumber: Long, batchHash: String)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[GetRewardAccountingBatchResponse]] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetRewardAccountingBatch(roundNumber, batchHash),
    )

  override def getBulkObjectChecksums(
      requiredCatchupTimestamp: CantonTimestamp,
      kind: BulkObjectKind,
      objectKeys: Seq[String],
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[GetBulkObjectChecksumsResponse] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetBulkObjectChecksums(objectKeys),
    )
  }

  def listBulkAcsSnapshotObjects(
      atOrBeforeRecordTime: CantonTimestamp,
      damlValueEncoding: Option[DamlValueEncoding],
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[BulkStorageObjects.SnapshotObjects]] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.ListBulkAcsSnapshotObjects(atOrBeforeRecordTime, damlValueEncoding),
    )
      .flatMap(response =>
        SingleScanConnection.decoded(BulkStorageObjects.snapshotObjects(response)).map(Some(_))
      )
      .recover {
        case e: HttpCommandException if e.status == StatusCodes.NotFound => None
      }

  override def listBulkUpdateHistoryObjects(
      startRecordTime: CantonTimestamp,
      endRecordTime: CantonTimestamp,
      pageSize: Int,
      nextPageToken: Option[String],
      damlValueEncoding: Option[DamlValueEncoding],
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[BulkStorageObjects.UpdateObjectsPage] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.ListBulkUpdateHistoryObjects(
        startRecordTime,
        endRecordTime,
        nextPageToken,
        pageSize,
        damlValueEncoding,
      ),
    ).flatMap(response =>
      SingleScanConnection.decoded(BulkStorageObjects.updateObjectsPage(response))
    )

  // Not intended to be called via BftScanConnection, so not defined in ScanConnection trait.
  def getBulkObjectsProgress(
      recordTime: CantonTimestamp,
      bucket: BulkStorageBucket,
      kind: BulkObjectKind,
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[GetBulkObjectsProgressResponse] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetBulkObjectsProgress(
        recordTime,
        bucket,
        Some(SingleScanConnection.toHttpObjectKind(kind)),
      ),
    )
  }
}

object SingleScanConnection {

  private def toHttpObjectKind(kind: BulkObjectKind): definitions.BulkObjectKind =
    kind match {
      case BulkObjectKind.Updates => definitions.BulkObjectKind.Updates
      case BulkObjectKind.Snapshots => definitions.BulkObjectKind.Snapshots
    }

  private def decoded[T](result: Either[String, T]): Future[T] =
    result.fold(err => Future.failed(new IllegalStateException(err)), Future.successful)

  private[client] def httpStatusLabel(error: Throwable): String =
    error match {
      case e: BaseAppConnection.UnexpectedHttpJsonResponse => e.statusCode.intValue.toString
      case e: BaseAppConnection.UnexpectedHttpMalformedJsonResponse =>
        e.statusCode.intValue.toString
      case e: BaseAppConnection.UnexpectedHttpTextResponse => e.statusCode.intValue.toString
      case e: BaseAppConnection.UnexpectedHttpNonJsonResponse => e.statusCode.intValue.toString
      case e: HttpCommandException => e.status.intValue.toString
      case _ => "none"
    }

  def withSingleScanConnection[T](
      scanConfig: ScanAppClientConfig,
      upgradesConfig: UpgradesConfig,
      clock: Clock,
      retryProvider: RetryProvider,
      loggerFactory: NamedLoggerFactory,
      connectionMetrics: Option[ScanConnectionMetrics] = None,
  )(f: SingleScanConnection => Future[T])(implicit
      ec: ExecutionContextExecutor,
      traceContext: TraceContext,
      mat: Materializer,
      httpClient: HttpClient,
      templateDecoder: TemplateJsonDecoder,
  ): Future[T] =
    for {
      scanConnection <- ScanConnection.singleUncached(
        scanConfig,
        upgradesConfig,
        clock,
        retryProvider,
        loggerFactory,
        retryConnectionOnInitialFailure = true,
        connectionMetrics,
      )
      r <- f(scanConnection).andThen { _ => scanConnection.close() }
    } yield r
}

class CachedScanConnection private[client] (
    protected val amuletLedgerClient: SpliceLedgerClient,
    config: ScanAppClientConfig,
    upgradesConfig: UpgradesConfig,
    clock: Clock,
    retryProvider: RetryProvider,
    outerLoggerFactory: NamedLoggerFactory,
    connectionMetrics: Option[ScanConnectionMetrics],
)(implicit
    ec: ExecutionContextExecutor,
    tc: TraceContext,
    mat: Materializer,
    httpClient: HttpClient,
    templateDecoder: TemplateJsonDecoder,
) extends SingleScanConnection(
      config,
      upgradesConfig,
      clock,
      retryProvider,
      outerLoggerFactory,
      connectionMetrics,
    )
    with CachingScanConnection {

  override protected val amuletRulesCacheTimeToLive: NonNegativeFiniteDuration =
    config.amuletRulesCacheTimeToLive

  override protected def runGetAmuletRulesWithState(
      cachedAmuletRules: Option[ContractWithState[AmuletRules.ContractId, AmuletRules]]
  )(implicit tc: TraceContext): Future[ContractWithState[AmuletRules.ContractId, AmuletRules]] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetAmuletRules(cachedAmuletRules),
    )

  override protected def runGetExternalPartyAmuletRules(
      cachedExternalPartyAmuletRules: Option[
        ContractWithState[ExternalPartyAmuletRules.ContractId, ExternalPartyAmuletRules]
      ]
  )(implicit
      tc: TraceContext
  ): Future[ContractWithState[ExternalPartyAmuletRules.ContractId, ExternalPartyAmuletRules]] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetExternalPartyAmuletRules(cachedExternalPartyAmuletRules),
    )

  override protected def runGetAnsRules(
      cachedAnsRules: Option[ContractWithState[AnsRules.ContractId, AnsRules]]
  )(implicit tc: TraceContext): Future[ContractWithState[AnsRules.ContractId, AnsRules]] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetAnsRules(cachedAnsRules),
    )

  override protected def runGetOpenAndIssuingMiningRounds(
      cachedOpenRounds: Seq[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]],
      cachedIssuingRounds: Seq[ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]],
  )(implicit ec: ExecutionContext, mat: Materializer, tc: TraceContext): Future[
    (
        Seq[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]],
        Seq[ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]],
        BigInt,
    )
  ] = runHttpCmd(
    config.adminApi.url,
    HttpScanAppClient.GetSortedOpenAndIssuingMiningRounds(
      cachedOpenRounds,
      cachedIssuingRounds,
    ),
  )

  override def getMigrationSchedule()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): OptionT[Future, MigrationSchedule] = OptionT(
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetMigrationSchedule(),
    )
  )
}
