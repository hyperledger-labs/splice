// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.api.client

import cats.data.{NonEmptyList, OptionT}
import cats.implicits.*
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{
  FeaturedAppRight,
  UnclaimedDevelopmentFundCoupon,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.{
  AmuletRules,
  TransferPreapproval,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.externalpartyamuletrules.{
  ExternalPartyAmuletRules,
  TransferCommandCounter,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.{
  IssuingMiningRound,
  OpenMiningRound,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.ans.AnsRules
import org.lfdecentralizedtrust.splice.config.{NetworkAppClientConfig, Thresholds, UpgradesConfig}
import org.lfdecentralizedtrust.splice.environment.PackageIdResolver.HasAmuletRules
import org.lfdecentralizedtrust.splice.environment.{RetryFor, RetryProvider, SpliceLedgerClient}
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.http.v0.definitions.{
  AnsEntry,
  BulkObjectsAvailability,
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
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection.{
  BftCallConfig,
  ScanList,
}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.DsoScan
import org.lfdecentralizedtrust.splice.scan.config.{ScanAppClientConfig, ScanStorageConfig}
import org.lfdecentralizedtrust.splice.scan.store.ScanStore
import org.lfdecentralizedtrust.splice.scan.store.bulk.BulkObjectKind
import org.lfdecentralizedtrust.splice.store.{DsoRulesStore, VoteResultsFilters}
import org.lfdecentralizedtrust.splice.store.HistoryBackfilling.SourceMigrationInfo
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.BulkStorageObjects
import org.lfdecentralizedtrust.splice.store.UpdateHistory.UpdateHistoryResponse
import org.lfdecentralizedtrust.splice.util.{
  ChoiceContextWithDisclosures,
  Contract,
  ContractWithState,
  DsoInfo,
  FactoryChoiceWithDisclosures,
  TemplateJsonDecoder,
}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.{
  AsyncOrSyncCloseable,
  FlagCloseableAsync,
  FutureUnlessShutdown,
  SyncCloseable,
}
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.{Clock, PeriodicAction}
import com.digitalasset.canton.topology.{ParticipantId, PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import io.grpc.Status
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.allocationv1
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.allocationv2
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.allocationinstructionv1
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.allocationinstructionv2
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.transferinstructionv1
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.transferinstructionv2
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  DsoRules,
  DsoRules_CloseVoteRequestResult,
  VoteRequest,
}
import org.lfdecentralizedtrust.splice.http.v0.definitions.HoldingsSummaryRequest.RecordTimeMatch
import org.lfdecentralizedtrust.splice.metrics.ScanConnectionMetrics
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftCallExecutor.{
  BftOutcome,
  DataAvailabilityResponse,
}
import org.lfdecentralizedtrust.tokenstandard.{
  allocation,
  allocationinstruction,
  metadata,
  transferinstruction,
}
import org.slf4j.event.Level

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.util.control.{NoStackTrace, NonFatal}
import scala.util.{Failure, Success, Try}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

class BftScanConnection(
    override protected val amuletLedgerClient: SpliceLedgerClient,
    override protected val amuletRulesCacheTimeToLive: NonNegativeFiniteDuration,
    val scanList: ScanList,
    protected val clock: Clock,
    val retryProvider: RetryProvider,
    val loggerFactory: NamedLoggerFactory,
    val connectionMetrics: Option[ScanConnectionMetrics] = None,
    // We added disableBackgroundRefresh flag to prevent the temporary bootstrap connection (used in bft-custom mode)
    // from automatically starting a background PeriodicAction that tries to connect to all scans.
    // In the future, before removing disableBackgroundRefresh flag, refactor the bootstrapWithSeedNodes();
    // BftCustom should not reuse the AllDsoScansBft class to fetch the initial DsoRules.
    val disableBackgroundRefresh: Boolean = false,
)(implicit protected val ec: ExecutionContextExecutor, protected val mat: Materializer)
    extends FlagCloseableAsync
    with NamedLogging
    with RetryProvider.Has
    with HasAmuletRules
    with CachingScanConnection
    with BackfillingScanConnection {

  private val refreshAction: Option[PeriodicAction] = scanList match {
    case _: BftScanConnection.TrustSingle =>
      None
    case bft: BftScanConnection.Bft if !disableBackgroundRefresh =>
      Some(
        new PeriodicAction(
          clock,
          com.digitalasset.canton.time.NonNegativeFiniteDuration
            .fromConfig(bft.scansRefreshInterval),
          loggerFactory,
          retryProvider.timeouts,
          "refresh_scan_list",
        )({ tc =>
          // refresh will throw if we're in a state where there's no BFT guarantees, in which case
          // this will retry faster than the regular `bft.scansRefreshInterval`
          FutureUnlessShutdown.outcomeF(
            retryProvider.retry(
              RetryFor.LongRunningAutomation,
              "refresh_scan_list",
              "refresh_scan_list",
              bft.refresh(this)(tc),
              logger,
            )(implicitly, TraceContext.empty, implicitly)
          )
        })
      )
    case _ => None
  }

  override def listVoteRequests()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Seq[Contract[VoteRequest.ContractId, VoteRequest]]] =
    bftCall(
      _.listVoteRequests(),
      "listVoteRequests",
    )

  override def getDsoPartyId()(implicit ec: ExecutionContext, tc: TraceContext): Future[PartyId] =
    bftCall(
      _.getDsoPartyId(),
      "getDsoPartyId",
    )

  override def getDsoInfo()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[DsoInfo] =
    bftCall(
      _.getDsoInfo(),
      "getDsoInfo",
    )

  override def getDsoRules(
  )(implicit
      tc: TraceContext
  ): Future[Contract[DsoRules.ContractId, DsoRules]] =
    bftCall(
      _.getDsoInfo().map(_.dsoRules.contract),
      "getDsoRules",
    )

  override def getHoldingsSummaryAt(
      at: CantonTimestamp,
      migrationId: Long,
      ownerPartyIds: Vector[PartyId],
      recordTimeMatch: Option[RecordTimeMatch],
      asOfRound: Option[Long],
  )(implicit tc: TraceContext): Future[Option[HoldingsSummaryResponse]] = {
    bftCall(
      _.getHoldingsSummaryAt(at, migrationId, ownerPartyIds, recordTimeMatch, asOfRound),
      "getHoldingsSummaryAt",
    )
  }

  override def getHoldingsSummaryAtV1(
      at: CantonTimestamp,
      migrationId: Long,
      ownerPartyIds: Vector[PartyId],
      recordTimeMatch: Option[HoldingsSummaryRequestV1.RecordTimeMatch],
  )(implicit tc: TraceContext): Future[Option[HoldingsSummaryResponseV1]] = {
    bftCall(
      _.getHoldingsSummaryAtV1(at, migrationId, ownerPartyIds, recordTimeMatch),
      "getHoldingsSummaryAtV1",
    )
  }

  override protected def runGetAmuletRulesWithState(
      cachedAmuletRules: Option[ContractWithState[AmuletRules.ContractId, AmuletRules]]
  )(implicit tc: TraceContext): Future[ContractWithState[AmuletRules.ContractId, AmuletRules]] =
    bftCall(
      _.getAmuletRulesWithState(cachedAmuletRules),
      "getAmuletRulesWithState",
    )

  override protected def runGetExternalPartyAmuletRules(
      cachedExternalPartyAmuletRules: Option[
        ContractWithState[ExternalPartyAmuletRules.ContractId, ExternalPartyAmuletRules]
      ]
  )(implicit
      tc: TraceContext
  ): Future[ContractWithState[ExternalPartyAmuletRules.ContractId, ExternalPartyAmuletRules]] =
    bftCall(
      _.getExternalPartyAmuletRules(cachedExternalPartyAmuletRules),
      "getExternalPartyAmuletRules",
    )

  override protected def runGetAnsRules(
      cachedAnsRules: Option[ContractWithState[AnsRules.ContractId, AnsRules]]
  )(implicit tc: TraceContext): Future[ContractWithState[AnsRules.ContractId, AnsRules]] = bftCall(
    _.getAnsRules(cachedAnsRules),
    "getAnsRules",
  )

  def lookupAnsEntryByParty(id: PartyId)(implicit
      tc: TraceContext
  ): Future[Option[AnsEntry]] =
    bftCall(_.lookupAnsEntryByParty(id), "lookupAnsEntryByParty")

  def lookupAnsEntryByName(name: String)(implicit
      tc: TraceContext
  ): Future[Option[AnsEntry]] =
    bftCall(_.lookupAnsEntryByName(name), "lookupAnsEntryByName")

  def listAnsEntries(namePrefix: Option[String], pageSize: Int)(implicit
      tc: TraceContext
  ): Future[Seq[AnsEntry]] =
    bftCall(_.listAnsEntries(namePrefix, pageSize), "listAnsEntries")

  override protected def runGetOpenAndIssuingMiningRounds(
      cachedOpenRounds: Seq[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]],
      cachedIssuingRounds: Seq[ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]],
  )(implicit ec: ExecutionContext, mat: Materializer, tc: TraceContext): Future[
    (
        Seq[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]],
        Seq[ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]],
        BigInt,
    )
  ] = bftCall(
    _.getOpenAndIssuingMiningRounds(cachedOpenRounds, cachedIssuingRounds),
    "getOpenAndIssuingMiningRounds",
  )

  override def listDsoSequencers()(implicit
      tc: TraceContext
  ): Future[Seq[HttpScanAppClient.DomainSequencers]] = {
    bftCall(_.listDsoSequencers(), "listDsoSequencers")
  }

  override def lookupRollForwardLsu()(implicit
      tc: TraceContext
  ): Future[Option[HttpScanAppClient.RollForwardLsu]] = {
    bftCall(_.lookupRollForwardLsu(), "lookupRollForwardLsu")
  }

  override def getLsu()(implicit
      tc: TraceContext
  ): Future[Option[HttpScanAppClient.Lsu]] = {
    bftCall(_.getLsu(), "getLsu")
  }

  override def getPartyToParticipant(
      synchronizerId: SynchronizerId,
      partyId: PartyId,
  )(implicit tc: TraceContext): Future[Seq[ParticipantId]] = {
    bftCall(_.getPartyToParticipant(synchronizerId, partyId), "getPartyToParticipant")
  }

  override def listDsoScans()(implicit
      tc: TraceContext
  ): Future[Seq[HttpScanAppClient.DomainScans]] = {
    bftCall(_.listDsoScans(), "listDsoScans")
  }

  override def lookupFeaturedAppRight(providerPartyId: PartyId)(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[Option[Contract[FeaturedAppRight.ContractId, FeaturedAppRight]]] = {
    bftCall(_.lookupFeaturedAppRight(providerPartyId), "lookupFeaturedAppRight")
  }

  override def listFeaturedAppRightsByProvider(providerPartyId: PartyId)(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[Seq[Contract[FeaturedAppRight.ContractId, FeaturedAppRight]]] = {
    bftCall(_.listFeaturedAppRightsByProvider(providerPartyId), "listFeaturedAppRightsByProvider")
  }

  override def lookupFeaturedAppRightByContractId(contractId: String)(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[Option[Contract[FeaturedAppRight.ContractId, FeaturedAppRight]]] = {
    bftCall(_.lookupFeaturedAppRightByContractId(contractId), "lookupFeaturedAppRightByContractId")
  }

  override def listFeaturedAppRights()(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[Seq[Contract[FeaturedAppRight.ContractId, FeaturedAppRight]]] = {
    bftCall(_.listFeaturedAppRights(), "listFeaturedAppRights")
  }

  override def getMigrationSchedule()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): OptionT[Future, MigrationSchedule] = OptionT(
    bftCall(_.getMigrationSchedule().value, "getMigrationSchedule")
  )

  override def getMigrationId()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Long] =
    bftCall(_.getMigrationId(), "getMigrationId")

  override def getMigrationInfo(migrationId: Long)(implicit
      tc: TraceContext
  ): Future[Option[SourceMigrationInfo]] =
    BftCallExecutor.getMigrationInfo(
      scanList.scanConnections,
      connectionMetrics,
      retryProvider,
      logger,
      migrationId,
    )

  override def lookupTransferCommandCounterByParty(receiver: PartyId)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[ContractWithState[TransferCommandCounter.ContractId, TransferCommandCounter]]] =
    bftCall(_.lookupTransferCommandCounterByParty(receiver), "lookupTransferCommandCounterByParty")

  override def lookupTransferCommandStatus(sender: PartyId, nonce: Long)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[LookupTransferCommandStatusResponse]] =
    bftCall(_.lookupTransferCommandStatus(sender, nonce), "lookupTransferCommandStatus")

  override def lookupTransferPreapprovalByParty(receiver: PartyId)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[ContractWithState[TransferPreapproval.ContractId, TransferPreapproval]]] =
    bftCall(_.lookupTransferPreapprovalByParty(receiver), "lookupTransferPreapprovalByParty")

  override def listVoteRequestResults(
      filters: VoteResultsFilters,
      limit: Int,
      pageToken: Option[BigInt] = None,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[(Seq[DsoRules_CloseVoteRequestResult], Option[BigInt])] = bftCall(
    _.listVoteRequestResults(
      filters,
      limit,
      pageToken,
    ),
    "listVoteRequestResults",
  )

  override def countVoteRequestResults(
      filters: VoteResultsFilters
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Long] = bftCall(
    _.countVoteRequestResults(filters),
    "countVoteRequestResults",
  )

  override def getPreviousSvRewardWeight(svParty: String, effectiveBefore: Option[String])(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[Long]] = bftCall(
    _.getPreviousSvRewardWeight(svParty, effectiveBefore),
    "getPreviousSvRewardWeight",
  )

  override def getImportUpdates(
      migrationId: Long,
      afterUpdateId: String,
      count: Int,
  )(implicit tc: TraceContext): Future[Seq[UpdateHistoryResponse]] = {
    val connections = scanList.scanConnections
    for {
      // Ask ALL scans for the migration info so that we can figure out who has the data
      responses <- BftCallExecutor.getMigrationInfoResponses(connections, migrationId)
      // Filter out connections that don't have any data
      withData = responses.withData.toList.filter { case (_, info) =>
        info.importUpdatesComplete
      }
      connectionsWithData = withData.map(_._1)
      // Make a BFT call to connections that have the data
      result <- bftCall(
        connection => connection.getImportUpdates(migrationId, afterUpdateId, count),
        "getImportUpdates",
        BftCallConfig.forAvailableData(connections, connectionsWithData.contains),
        // This method is very sensitive to unavailable SVs.
        // Do not log warnings for failures to reach consensus, as this would be too noisy,
        // and instead rely on metrics to situations when backfilling is not progressing.
        Level.INFO,
        // This call returns up to 100 full daml transaction trees. It's not feasible to log them all,
        // so we only print their update ids. This is enough to investigate consensus failures if different
        // scans return different updates. In the more unlikely case where scans disagree on the payload of
        // a given update, we would need to fetch the update payload from the update history database.
        shortenResponsesForLog =
          (responses: Seq[UpdateHistoryResponse]) => responses.map(_.update.updateId),
      )
    } yield {
      result
    }
  }

  override def getUpdatesBefore(
      migrationId: Long,
      synchronizerId: SynchronizerId,
      before: CantonTimestamp,
      atOrAfter: Option[CantonTimestamp],
      count: Int,
  )(implicit tc: TraceContext): Future[Seq[UpdateHistoryResponse]] = {
    require(atOrAfter.isEmpty, "atOrAfter is chosen by BftScanConnection")
    val connections = scanList.scanConnections
    for {
      // Ask ALL scans for the migration info so that we can figure out who has the data
      responses <- BftCallExecutor.getMigrationInfoResponses(connections, migrationId)
      // Filter out connections that don't have any data
      withData = responses.withData.toList.filter { case (_, info) =>
        info.recordTimeRange.get(synchronizerId).exists(_.min < before)
      }
      connectionsWithData = withData.map(_._1)
      // Find the record time range for which all remaining connections have the data
      atOrAfter = withData.flatMap { case (_, info) =>
        info.recordTimeRange.get(synchronizerId).map(_.min)
      }.maxOption
      // Make a BFT call to connections that have the data
      result <- bftCall(
        connection =>
          connection.getUpdatesBefore(migrationId, synchronizerId, before, atOrAfter, count),
        "getUpdatesBefore",
        BftCallConfig.forAvailableData(connections, connectionsWithData.contains),
        // This method is very sensitive to unavailable SVs.
        // Do not log warnings for failures to reach consensus, as this would be too noisy,
        // and instead rely on metrics to situations when backfilling is not progressing.
        Level.INFO,
        // This call returns up to 100 full daml transaction trees. It's not feasible to log them all,
        // so we only print their update ids. This is enough to investigate consensus failures if different
        // scans return different updates. In the more unlikely case where scans disagree on the payload of
        // a given update, we would need to fetch the update payload from the update history database.
        shortenResponsesForLog =
          (responses: Seq[UpdateHistoryResponse]) => responses.map(_.update.updateId),
      )
    } yield {
      result
    }
  }

  def getTransferFactory(choiceArgs: transferinstructionv1.TransferFactory_Transfer)(implicit
      tc: TraceContext
  ): Future[
    (
        FactoryChoiceWithDisclosures[
          transferinstructionv1.TransferFactory.ContractId,
          transferinstructionv1.TransferFactory_Transfer,
        ],
        transferinstruction.v1.definitions.TransferFactoryWithChoiceContext.TransferKind,
    )
  ] =
    bftCall(_.getTransferFactory(choiceArgs), "getTransferFactory")

  def getTransferFactoryV2(choiceArgs: transferinstructionv2.TransferFactory_Transfer)(implicit
      tc: TraceContext
  ): Future[
    (
        FactoryChoiceWithDisclosures[
          transferinstructionv2.TransferFactory.ContractId,
          transferinstructionv2.TransferFactory_Transfer,
        ],
        transferinstruction.v2.definitions.TransferFactoryWithChoiceContext.TransferKind,
    )
  ] =
    bftCall(_.getTransferFactoryV2(choiceArgs), "getTransferFactoryV2")

  def getTransferFactoryRaw(arg: transferinstruction.v1.definitions.GetFactoryRequest)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[transferinstruction.v1.definitions.TransferFactoryWithChoiceContext] =
    bftCall(_.getTransferFactoryRaw(arg), "getTransferFactoryRaw")

  def getTransferFactoryV2Raw(arg: transferinstruction.v2.definitions.GetFactoryRequest)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[transferinstruction.v2.definitions.TransferFactoryWithChoiceContext] =
    bftCall(_.getTransferFactoryV2Raw(arg), "getTransferFactoryV2Raw")

  def getTransferInstructionAcceptContextV2(
      instructionCid: transferinstructionv1.TransferInstruction.ContractId
  )(implicit tc: TraceContext): Future[ChoiceContextWithDisclosures] = bftCall(
    _.getTransferInstructionAcceptContext(instructionCid),
    "getTransferInstructionAcceptContext",
  )

  def getTransferInstructionAcceptContextV2(
      instructionCid: transferinstructionv2.TransferInstruction.ContractId
  )(implicit tc: TraceContext): Future[ChoiceContextWithDisclosures] = bftCall(
    _.getTransferInstructionAcceptContextV2(instructionCid),
    "getTransferInstructionAcceptContextV2",
  )

  def getTransferInstructionRejectContext(
      instructionCid: transferinstructionv1.TransferInstruction.ContractId
  )(implicit tc: TraceContext): Future[ChoiceContextWithDisclosures] = bftCall(
    _.getTransferInstructionRejectContext(instructionCid),
    "getTransferInstructionRejectContext",
  )

  def getTransferInstructionRejectContextV2(
      instructionCid: transferinstructionv2.TransferInstruction.ContractId
  )(implicit tc: TraceContext): Future[ChoiceContextWithDisclosures] = bftCall(
    _.getTransferInstructionRejectContextV2(instructionCid),
    "getTransferInstructionRejectContextV2",
  )

  def getTransferInstructionWithdrawContext(
      instructionCid: transferinstructionv1.TransferInstruction.ContractId
  )(implicit tc: TraceContext): Future[ChoiceContextWithDisclosures] = bftCall(
    _.getTransferInstructionWithdrawContext(instructionCid),
    "getTransferInstructionWithdrawContext",
  )

  def getTransferInstructionWithdrawContextV2(
      instructionCid: transferinstructionv2.TransferInstruction.ContractId
  )(implicit tc: TraceContext): Future[ChoiceContextWithDisclosures] = bftCall(
    _.getTransferInstructionWithdrawContextV2(instructionCid),
    "getTransferInstructionWithdrawContextV2",
  )

  def getTransferInstructionAcceptContextRaw(
      instructionCid: String,
      body: transferinstruction.v1.definitions.GetChoiceContextRequest,
  )(implicit tc: TraceContext): Future[transferinstruction.v1.definitions.ChoiceContext] = bftCall(
    _.getTransferInstructionAcceptContextRaw(instructionCid, body),
    "getTransferInstructionAcceptContextRaw",
  )

  def getTransferInstructionAcceptContextV2Raw(
      instructionCid: String,
      body: transferinstruction.v2.definitions.GetChoiceContextRequest,
  )(implicit tc: TraceContext): Future[transferinstruction.v2.definitions.ChoiceContext] = bftCall(
    _.getTransferInstructionAcceptContextV2Raw(instructionCid, body),
    "getTransferInstructionAcceptContextV2Raw",
  )

  def getTransferInstructionRejectContextRaw(
      instructionCid: String,
      body: transferinstruction.v1.definitions.GetChoiceContextRequest,
  )(implicit tc: TraceContext): Future[transferinstruction.v1.definitions.ChoiceContext] = bftCall(
    _.getTransferInstructionRejectContextRaw(instructionCid, body),
    "getTransferInstructionRejectContextRaw",
  )

  def getTransferInstructionRejectContextV2Raw(
      instructionCid: String,
      body: transferinstruction.v2.definitions.GetChoiceContextRequest,
  )(implicit tc: TraceContext): Future[transferinstruction.v2.definitions.ChoiceContext] = bftCall(
    _.getTransferInstructionRejectContextV2Raw(instructionCid, body),
    "getTransferInstructionRejectContextV2Raw",
  )

  def getTransferInstructionWithdrawContextRaw(
      instructionCid: String,
      body: transferinstruction.v1.definitions.GetChoiceContextRequest,
  )(implicit tc: TraceContext): Future[transferinstruction.v1.definitions.ChoiceContext] = bftCall(
    _.getTransferInstructionWithdrawContextRaw(instructionCid, body),
    "getTransferInstructionWithdrawContextRaw",
  )

  def getTransferInstructionWithdrawContextV2Raw(
      instructionCid: String,
      body: transferinstruction.v2.definitions.GetChoiceContextRequest,
  )(implicit tc: TraceContext): Future[transferinstruction.v2.definitions.ChoiceContext] = bftCall(
    _.getTransferInstructionWithdrawContextV2Raw(instructionCid, body),
    "getTransferInstructionWithdrawContextV2Raw",
  )

  def getRegistryInfo()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[metadata.v1.definitions.GetRegistryInfoResponse] =
    bftCall(_.getRegistryInfo(), "getRegistryInfo")

  def lookupInstrument(instrumentId: String)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[metadata.v1.definitions.Instrument]] =
    bftCall(
      _.lookupInstrument(instrumentId),
      "lookupInstrument",
    )

  def listInstruments(pageSize: Option[Int], pageToken: Option[String])(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Seq[metadata.v1.definitions.Instrument]] =
    bftCall(_.listInstruments(pageSize, pageToken), "listInstruments")

  def getAllocationTransferContext(
      allocationCid: allocationv1.Allocation.ContractId
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ChoiceContextWithDisclosures] =
    bftCall(_.getAllocationTransferContext(allocationCid), "getAllocationTransferContext")

  def getAllocationTransferContextRaw(
      allocationId: String,
      body: allocation.v1.definitions.GetChoiceContextRequest,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[allocation.v1.definitions.ChoiceContext] =
    bftCall(
      _.getAllocationTransferContextRaw(allocationId, body),
      "getAllocationTransferContextRaw",
    )

  def getAllocationCancelContextRaw(
      allocationId: String,
      body: allocation.v1.definitions.GetChoiceContextRequest,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[allocation.v1.definitions.ChoiceContext] =
    bftCall(_.getAllocationCancelContextRaw(allocationId, body), "getAllocationCancelContextRaw")

  def getSettlementFactoryRaw(
      body: allocation.v2.definitions.GetFactoryRequest
  )(implicit ec: ExecutionContext, tc: TraceContext) =
    bftCall(_.getSettlementFactoryRaw(body), "getSettlementFactoryRaw")

  def getAllocationV2CancelContextRaw(
      allocationId: String,
      body: allocation.v2.definitions.GetChoiceContextRequest,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[allocation.v2.definitions.ChoiceContext] =
    bftCall(
      _.getAllocationV2CancelContextRaw(allocationId, body),
      "getAllocationV2CancelContextRaw",
    )

  def getAllocationV2WithdrawContextRaw(
      allocationId: String,
      body: allocation.v2.definitions.GetChoiceContextRequest,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[allocation.v2.definitions.ChoiceContext] =
    bftCall(
      _.getAllocationV2WithdrawContextRaw(allocationId, body),
      "getAllocationV2WithdrawContextRaw",
    )

  def getAllocationWithdrawContextRaw(
      allocationId: String,
      body: allocation.v1.definitions.GetChoiceContextRequest,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[allocation.v1.definitions.ChoiceContext] =
    bftCall(
      _.getAllocationWithdrawContextRaw(allocationId, body),
      "getAllocationWithdrawContextRaw",
    )

  def getAllocationCancelContext(
      allocationCid: allocationv1.Allocation.ContractId
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ChoiceContextWithDisclosures] =
    bftCall(_.getAllocationCancelContext(allocationCid), "getAllocationCancelContext")

  def getAllocationWithdrawContextV1(
      allocationCid: allocationv1.Allocation.ContractId
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ChoiceContextWithDisclosures] =
    bftCall(_.getAllocationWithdrawContextV1(allocationCid), "getAllocationWithdrawContextV1")

  def getAllocationWithdrawContextV2(
      allocationCid: allocationv2.Allocation.ContractId
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ChoiceContextWithDisclosures] =
    bftCall(_.getAllocationWithdrawContextV2(allocationCid), "getAllocationWithdrawContext")

  def getAllocationFactory(choiceArgs: allocationinstructionv1.AllocationFactory_Allocate)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[
    FactoryChoiceWithDisclosures[
      allocationinstructionv1.AllocationFactory.ContractId,
      allocationinstructionv1.AllocationFactory_Allocate,
    ]
  ] =
    bftCall(_.getAllocationFactory(choiceArgs), "getAllocationFactory")

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
    bftCall(_.getAllocationFactoryV2(choiceArgs), "getAllocationFactoryV2")

  def getAllocationFactoryV2Raw(body: allocationinstruction.v2.definitions.GetFactoryRequest)(
      implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[
    allocationinstruction.v2.definitions.FactoryWithChoiceContext
  ] =
    bftCall(_.getAllocationFactoryV2Raw(body), "getAllocationFactoryV2Raw")

  def getAllocationFactoryRaw(arg: allocationinstruction.v1.definitions.GetFactoryRequest)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[allocationinstruction.v1.definitions.FactoryWithChoiceContext] =
    bftCall(_.getAllocationFactoryRaw(arg), "getAllocationFactoryRaw")

  def getAllocationInstructionAcceptContextRaw(
      allocationInstructionCid: String,
      body: allocationinstruction.v2.definitions.GetChoiceContextRequest,
  )(implicit tc: TraceContext): Future[allocationinstruction.v2.definitions.ChoiceContext] =
    bftCall(
      _.getAllocationInstructionAcceptContextRaw(allocationInstructionCid, body),
      "getAllocationInstructionAcceptContextRaw",
    )

  def getAllocationInstructionWithdrawContext(
      allocationInstructionCid: String,
      body: allocationinstruction.v2.definitions.GetChoiceContextRequest,
  )(implicit tc: TraceContext): Future[allocationinstruction.v2.definitions.ChoiceContext] =
    bftCall(
      _.getAllocationInstructionWithdrawContext(allocationInstructionCid, body),
      "getAllocationInstructionWithdrawContext",
    )

  override def listUnclaimedDevelopmentFundCoupons()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Seq[
    ContractWithState[UnclaimedDevelopmentFundCoupon.ContractId, UnclaimedDevelopmentFundCoupon]
  ]] = bftCall(_.listUnclaimedDevelopmentFundCoupons(), "listUnclaimedDevelopmentFundCoupons")

  override def getActivePhysicalSynchronizerSerial()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[NonNegativeInt] =
    bftCall(_.getActivePhysicalSynchronizerSerial(), "getActivePhysicalSynchronizerSerial")

  /** Bootstrap-safe reward-accounting read using two-phase
    * probe-filter-consensus. Phase 1 asks every open scan whether it
    * can contribute to consensus; phase 2 runs a regular BFT call
    * across peers that can (`WithData`) or might still be able to
    * (`Unavailable`), reusing cached Ok responses from phase 1.
    * Undetermined and CannotProvide responses are transformed to
    * failures so they never form a consensus. Consensus failures
    * propagate as `BftCallFailed.Disagreement` — same
    * convention as `getMigrationInfo`.
    */
  override def getRewardAccountingActivityTotals(roundNumber: Long)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[GetRewardAccountingActivityTotalsResponse] =
    getRewardAccountingActivityTotalsWithScanUris(roundNumber).map(_._1)

  def getRewardAccountingActivityTotalsWithScanUris(roundNumber: Long)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[(GetRewardAccountingActivityTotalsResponse, List[Uri])] = {
    import GetRewardAccountingActivityTotalsResponse.members as RAT
    for {
      connectionsWithResponses <- BftScanConnection.ConnectionsWithProbeVerdicts.fromCall(
        scanList.scanConnections,
        _.getRewardAccountingActivityTotals(roundNumber),
      ) {
        case Success(RAT.RewardAccountingActivityTotalsCannotProvide(_)) =>
          BftScanConnection.ProbeVerdict.WithoutData
        case Success(r @ RAT.RewardAccountingActivityTotalsOk(_)) =>
          BftScanConnection.ProbeVerdict.WithData(r)
        case Success(RAT.RewardAccountingActivityTotalsUndetermined(_)) =>
          BftScanConnection.ProbeVerdict.Unavailable
        case Failure(_) => BftScanConnection.ProbeVerdict.Unavailable
      }
      result <- consensusOverProbe(
        connectionsWithResponses,
        "getRewardAccountingActivityTotals",
      )
    } yield result
  }

  /** Bootstrap-safe reward-accounting read using two-phase
    * probe-filter-consensus. Phase 1 asks every open scan whether it
    * can contribute to consensus; phase 2 runs a regular BFT call
    * across peers that can (`WithData`) or might still be able to
    * (`Unavailable`), reusing cached Ok responses from phase 1.
    * Undetermined and CannotProvide responses are transformed to
    * failures so they never form a consensus. Consensus failures
    * propagate as `BftCallFailed.Disagreement` — same
    * convention as `getMigrationInfo`.
    */
  override def getRewardAccountingRootHash(roundNumber: Long)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[GetRewardAccountingRootHashResponse] =
    getRewardAccountingRootHashWithScanUris(roundNumber).map(_._1)

  def getRewardAccountingRootHashWithScanUris(roundNumber: Long)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[(GetRewardAccountingRootHashResponse, List[Uri])] = {
    import GetRewardAccountingRootHashResponse.members as RHR
    for {
      connectionsWithResponses <- BftScanConnection.ConnectionsWithProbeVerdicts.fromCall(
        scanList.scanConnections,
        _.getRewardAccountingRootHash(roundNumber),
      ) {
        case Success(RHR.RewardAccountingRootHashCannotProvide(_)) =>
          BftScanConnection.ProbeVerdict.WithoutData
        case Success(r @ RHR.RewardAccountingRootHashOk(_)) =>
          BftScanConnection.ProbeVerdict.WithData(r)
        case Success(RHR.RewardAccountingRootHashUndetermined(_)) =>
          BftScanConnection.ProbeVerdict.Unavailable
        case Failure(_) => BftScanConnection.ProbeVerdict.Unavailable
      }
      result <- consensusOverProbe(connectionsWithResponses, "getRewardAccountingRootHash")
    } yield result
  }

  /** Second phase of a two-phase probe-filter-consensus call. Runs a BFT
    * call over the `WithData` subset of the probe result, replaying the
    * cached responses. `WithoutData` peers are dropped from `n` by
    * `forOptionalResponses`; `Unavailable` peers count in `n` but are
    * not re-queried — the retry-through-Unavailable path was found to
    * leak `IgnoreResponse` failures into caller triggers when many
    * peers stay unavailable, so this phase now sticks to cached data.
    *
    * Note: `bftCallWithScanUris` retries on `ConsensusNotReached`; retries
    * against the cache produce byte-identical results, so the extra
    * WARNs on a genuine disagreement are benign.
    */
  private def consensusOverProbe[T](
      connectionsWithResponses: BftScanConnection.ConnectionsWithProbeVerdicts[T],
      endpoint: String,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[(T, List[Uri])] = {
    val callConfig = BftCallConfig.forOptionalResponses(connectionsWithResponses)
    val cache: Map[SingleScanConnection, T] =
      connectionsWithResponses.responses.collect {
        case (c, BftScanConnection.ProbeVerdict.WithData(v)) => c -> v
      }
    bftCallWithScanUris[T](
      call = scan =>
        cache.get(scan) match {
          case Some(cached) => Future.successful(cached)
          case None =>
            // Defensive: forOptionalResponses only samples WithData scans.
            Future.failed(BftScanConnection.IgnoreResponse(scan.url))
        },
      endpoint = endpoint,
      callConfig = callConfig,
      disagreementLogLevel = Level.WARN,
      // "Not enough scans" here means the probe didn't gather enough cached
      // responses to satisfy BFT quorum — benign during bootstrap or transient
      // peer unavailability. The caller sees BftCallFailed.NotEnoughScans and retries.
      notEnoughScansLogLevel = Level.INFO,
    )
  }

  /** The batch contents are verifiable via the hash, so BFT agreement across scans is not
    * required: we query a single random scan and return its response.
    * Note: this will not do a retry at all, even if the scan being requested happens to be offline.
    */
  override def getRewardAccountingBatch(roundNumber: Long, batchHash: String)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[GetRewardAccountingBatchResponse]] = {
    val callConfig = BftCallConfig.randomSingleCall(scanList.scanConnections)
    if (!callConfig.enoughAvailableScans) Future.successful(None)
    else
      bftCall[GetRewardAccountingBatchResponse](
        call = scan =>
          scan.getRewardAccountingBatch(roundNumber, batchHash).flatMap {
            case Some(batch) => Future.successful(batch)
            case None => Future.failed(BftScanConnection.IgnoreResponse(scan.url))
          },
        endpoint = "getRewardAccountingBatch",
        callConfig = callConfig,
      )
        .transform(tryBatch => Success(tryBatch.toOption))
  }

  override def getBulkObjectChecksums(
      requiredCatchupTimestamp: CantonTimestamp,
      kind: BulkObjectKind,
      objectKeys: Seq[String],
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[GetBulkObjectChecksumsResponse] =
    getBulkObjectChecksumsOutcome(requiredCatchupTimestamp, kind, objectKeys)
      .flatMap(_.toFuture)
      .map(_._1)

  def getBulkObjectChecksumsOutcome(
      requiredCatchupTimestamp: CantonTimestamp,
      kind: BulkObjectKind,
      objectKeys: Seq[String],
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[BftOutcome[GetBulkObjectChecksumsResponse]] =
    BftCallExecutor.bftCallForEventualConsistencyEndpoints(
      scanList.scanConnections,
      connectionMetrics,
      retryProvider,
      logger,
      hasData = _.getBulkObjectsProgress(requiredCatchupTimestamp, BulkStorageBucket.Staging, kind)
        .map(BftScanConnection.dataAvailability),
      getData = _.getBulkObjectChecksums(requiredCatchupTimestamp, kind, objectKeys),
      endpoint = "getBulkObjectChecksums",
      callConfig = BftCallConfig.default(scanList.scanConnections),
    )

  override def listBulkAcsSnapshotObjects(
      atOrBeforeRecordTime: CantonTimestamp,
      damlValueEncoding: Option[DamlValueEncoding],
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[BulkStorageObjects.SnapshotObjects]] =
    bftCallOnScansCommittedUpTo(
      atOrBeforeRecordTime,
      BulkObjectKind.Snapshots,
      "listBulkAcsSnapshotObjects",
    )(
      _.listBulkAcsSnapshotObjects(atOrBeforeRecordTime, damlValueEncoding)
    ).flatMap(_.toFuture).map(_._1)

  override def listBulkUpdateHistoryObjects(
      startRecordTime: CantonTimestamp,
      endRecordTime: CantonTimestamp,
      pageSize: Int,
      nextPageToken: Option[String],
      damlValueEncoding: Option[DamlValueEncoding],
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[BulkStorageObjects.UpdateObjectsPage] =
    bftCallOnScansCommittedUpTo(
      endRecordTime,
      BulkObjectKind.Updates,
      "listBulkUpdateHistoryObjects",
    )(
      _.listBulkUpdateHistoryObjects(
        startRecordTime,
        endRecordTime,
        pageSize,
        nextPageToken,
        damlValueEncoding,
      )
    ).flatMap(_.toFuture).map(_._1)

  def listBulkAcsSnapshotObjectsWithPeers(
      atOrBeforeRecordTime: CantonTimestamp,
      encoding: ScanStorageConfig.Encoding,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[BftOutcome[Option[BulkStorageObjects.SnapshotObjects]]] =
    bftCallOnScansCommittedUpTo(
      atOrBeforeRecordTime,
      BulkObjectKind.Snapshots,
      "listBulkAcsSnapshotObjects",
    )(
      _.listBulkAcsSnapshotObjects(atOrBeforeRecordTime, Some(encoding.damlValueEncoding))
    )

  def listBulkUpdateHistoryObjectsWithPeers(
      startRecordTime: CantonTimestamp,
      endRecordTime: CantonTimestamp,
      pageSize: Int,
      availableAt: CantonTimestamp,
      encoding: ScanStorageConfig.Encoding,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[BftOutcome[BulkStorageObjects.UpdateObjectsPage]] =
    bftCallOnScansCommittedUpTo(
      availableAt,
      BulkObjectKind.Updates,
      "listBulkUpdateHistoryObjects",
    )(
      _.listBulkUpdateHistoryObjects(
        startRecordTime,
        endRecordTime,
        pageSize,
        None,
        Some(encoding.damlValueEncoding),
      )
    )

  private def bftCallOnScansCommittedUpTo[T](
      availableAt: CantonTimestamp,
      kind: BulkObjectKind,
      endpoint: String,
  )(
      call: SingleScanConnection => Future[T]
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[BftOutcome[T]] =
    BftCallExecutor.bftCallForEventualConsistencyEndpoints(
      scanList.scanConnections,
      connectionMetrics,
      retryProvider,
      logger,
      hasData = _.getBulkObjectsProgress(availableAt, BulkStorageBucket.Committed, kind)
        .map(BftScanConnection.dataAvailability),
      getData = call,
      endpoint = endpoint,
      callConfig = BftCallConfig.default(scanList.scanConnections),
      consensusFailureLogLevel = Level.DEBUG,
      disagreementLogLevel = Level.INFO,
      notEnoughScansLogLevel = Level.DEBUG,
    )

  private def bftCall[T](
      call: SingleScanConnection => Future[T],
      endpoint: String,
      callConfig: BftCallConfig = BftCallConfig.default(scanList.scanConnections),
      consensusFailureLogLevel: Level = Level.WARN,
      shortenResponsesForLog: T => Any = identity[T],
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[T] = bftCallWithScanUris(
    call,
    endpoint,
    callConfig,
    consensusFailureLogLevel,
    notEnoughScansLogLevel = consensusFailureLogLevel,
    shortenResponsesForLog = shortenResponsesForLog,
  )
    .map(_._1)

  private def bftCallWithScanUris[T](
      call: SingleScanConnection => Future[T],
      endpoint: String,
      callConfig: BftCallConfig,
      consensusFailureLogLevel: Level = Level.WARN,
      disagreementLogLevel: Level = Level.INFO,
      notEnoughScansLogLevel: Level,
      shortenResponsesForLog: T => Any = identity[T],
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[(T, List[Uri])] =
    BftCallExecutor
      .bftCallWithScanUris(
        scanList.scanConnections,
        connectionMetrics,
        retryProvider,
        logger,
        call,
        endpoint,
        callConfig,
        consensusFailureLogLevel,
        disagreementLogLevel,
        notEnoughScansLogLevel,
        shortenResponsesForLog,
      )
      .flatMap(_.toFuture)

  override def closeAsync(): Seq[AsyncOrSyncCloseable] = {
    refreshAction.map(r => SyncCloseable("refresh_scan_list", r.close())).toList ++
      Seq[AsyncOrSyncCloseable](
        SyncCloseable("scan_list", scanList.close())
      )
  }
}

object BftScanConnection {

  def dataAvailability(response: GetBulkObjectsProgressResponse): DataAvailabilityResponse =
    response.availability match {
      case BulkObjectsAvailability.members.Available => DataAvailabilityResponse.Available
      case BulkObjectsAvailability.members.Processing => DataAvailabilityResponse.NotYet
      case BulkObjectsAvailability.members.Backfilling => DataAvailabilityResponse.Never
    }

  /** Configuration for a BFT call.
    * Normally a BFT call requires f+1 agreeing responses from 2f+1 requests,
    * but in some special cases we need to adjust these numbers.
    *
    * @param connections   The pool of connections that are available for making requests.
    * @param requestsToDo  Number of requests to make.
    * @param targetSuccess Number of agreeing responses required to reach consensus.
    */
  case class BftCallConfig(
      connections: Seq[SingleScanConnection],
      requestsToDo: Int,
      targetSuccess: Int,
  ) {
    def enoughAvailableScans: Boolean = connections.size >= targetSuccess && targetSuccess > 0
  }

  object BftCallConfig {
    def default(connections: ScanConnections): BftCallConfig = {
      connections.targetTotalNumber match {
        case Some(n) =>
          val threshold =
            connections.threshold.getOrElse(Thresholds.requiredNumScanThreshold(n).value)
          BftCallConfig(
            connections = connections.open,
            requestsToDo = connections.open.size,
            targetSuccess = threshold,
          )
        case None =>
          val f = connections.f
          BftCallConfig(
            connections = connections.open,
            // Play it safe wrt availability in case we have no fault tolerance.
            requestsToDo = if (f == 0) connections.open.size else 2 * f + 1,
            targetSuccess = f + 1,
          )
      }
    }

    // By setting the targetSuccess and requestsToDo to 1, bftCall would
    // select a random scan for call.
    // Caution: this will *always* reach consensus on the first bftCall, hence
    // the caller need to retry if the response was not received.
    def randomSingleCall(connections: ScanConnections): BftCallConfig =
      default(connections).copy(requestsToDo = 1, targetSuccess = 1)

    /** BFT config for the second phase of a probe-filter-consensus call.
      * Peers with `ProbeVerdict.WithoutData` (explicit "cannot provide")
      * are dropped from `n` — they opted out. Peers with `Unavailable`
      * (probe timed out or returned inconclusive) count toward `n` (so
      * `f` and `targetSuccess` reflect the possibility they could have
      * disagreed), but are NOT sampled in the second phase — only
      * `WithData` peers have cached responses to contribute.
      *
      * `n = withData.size + unavailable + scanConnections.failed`.
      * `requestsToDo = withData.size` because every sampled peer has
      * a cached response; there is nothing to fetch live.
      */
    def forOptionalResponses[T](
        connectionsWithProbeVerdicts: ConnectionsWithProbeVerdicts[T]
    )(implicit loggingContext: ErrorLoggingContext): BftCallConfig = {
      val withData = connectionsWithProbeVerdicts.responses.toList.collect {
        case (c, ProbeVerdict.WithData(_)) => c
      }
      val unavailable = connectionsWithProbeVerdicts.responses.count {
        case (_, ProbeVerdict.Unavailable) => true
        case _ => false
      }
      val abstaining = connectionsWithProbeVerdicts.responses.collect {
        case (c, ProbeVerdict.WithoutData) => c.url
      }.toSet
      if (abstaining.nonEmpty) {
        loggingContext.info(
          s"Making a BFT call with a modified config, because some connections are abstaining." +
            s" Connections ${abstaining} are abstaining from consensus, " +
            s" proceeding with the remaining ${withData.map(_.url)} connections."
        )
      }

      val n = withData.size + unavailable + connectionsWithProbeVerdicts.failed
      val f = (n - 1) / 3 max 0

      BftCallConfig(
        connections = withData,
        requestsToDo = withData.size,
        targetSuccess = f + 1,
      )
    }

    /** A configuration for a BFT call where some peers cannot provide a response and wish
      * to be excluded from the consensus. Only peers that successfully respond that they
      * cannot provide data are excluded, unavailable peers are treated as if responding with disagreeing data.
      *
      * A standard BFT call (see [[BftCallConfig.default]]) tolerates up to `f` Byzantine/faulty
      * peers out of `3f+1` total and thus requires `f+1` matching responses out of `2f+1` requests.
      *
      * This method keeps the same fault-tolerance intent as a standard call.
      * I.e., it still requires `f+1` matching responses, only decreasing that number if it
      * is lower than the number of peers that did not opt out of consensus.
      *
      * E.g., in a network with 4 peers (f=1), it will still require `f+1 = 2` matching responses,
      * unless only 1 peer has the data and all other peers have opted out.
      *
      * Note that keeping the same fault-tolerance intent means that as the number of peers with data decreases,
      * the BFT call becomes increasingly sensitive to failing or unavailable peers.
      * In the extreme case, if the number of peers with data is smaller than `f+1`, it will require
      * ALL peers to successfully respond with the same data.
      */
    def forAvailableData(
        connections: ScanConnections,
        dataAvailable: SingleScanConnection => Boolean,
    )(implicit loggingContext: ErrorLoggingContext): BftCallConfig = {
      val f = connections.f
      val connectionsWithData = connections.open.filter(dataAvailable)
      val requestsToDo = (2 * f + 1) min (connectionsWithData.size + connections.failed)
      val targetSuccess = (f + 1) min (connectionsWithData.size + connections.failed)
      if (2 * f + 1 > requestsToDo) {
        loggingContext.debug(
          s"Making a BFT call with a modified config." +
            s" Out of ${connections.open.map(_.url)} connections, only ${connectionsWithData
                .map(_.url)} have data and ${connections.failed} are failed, " +
            s"requiring $targetSuccess out of $requestsToDo matching responses."
        )
      }
      BftCallConfig(
        connections = connectionsWithData,
        requestsToDo = requestsToDo,
        targetSuccess = targetSuccess,
      )
    }
  }

  case class ScanConnections(
      open: Seq[SingleScanConnection],
      failed: Int,
      threshold: Option[Int] = Some(0),
      targetTotalNumber: Option[Int] = None,
  ) {
    val totalNumber: Int = open.size + failed
    val f: Int = (totalNumber - 1) / 3
  }

  private[BftScanConnection] sealed trait ScanList
      extends FlagCloseableAsync
      with NamedLogging
      with RetryProvider.Has {
    def scanConnections: ScanConnections
  }
  class TrustSingle(
      scanConnection: SingleScanConnection,
      val retryProvider: RetryProvider,
      val loggerFactory: NamedLoggerFactory,
  ) extends ScanList {
    override def scanConnections: ScanConnections =
      ScanConnections(Seq(scanConnection), 0)

    override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = Seq(
      SyncCloseable("scan_connection", scanConnection.close())
    )
  }

  type SvName = String
  case class BftState(
      openConnections: Map[Uri, (SingleScanConnection, SvName)],
      failedConnections: Map[Uri, (Throwable, SvName)],
  ) {
    def scanConnections: ScanConnections =
      ScanConnections(openConnections.values.map(_._1).toSeq, failedConnections.size)
  }

  sealed trait Bft extends ScanList {
    protected val initialScanConnections: Seq[SingleScanConnection]
    protected val initialFailedConnections: Map[Uri, Throwable]
    protected val connectionBuilder: Uri => Future[SingleScanConnection]
    protected val scanUrlsChangedCallback: Seq[(String, String)] => Future[Unit]
    protected val getScans: BftScanConnection => Future[Seq[DsoScan]]
    val scansRefreshInterval: NonNegativeFiniteDuration
    val retryProvider: RetryProvider
    val loggerFactory: NamedLoggerFactory
    implicit protected val ec: ExecutionContext

    protected def filterScans(allScans: Seq[DsoScan]): Seq[DsoScan]
    protected def getRequiredConnections(state: BftState): Int

    protected def validateState(state: BftState)(implicit tc: TraceContext): Unit = {
      val successfulCount = state.openConnections.size
      val requiredCount = getRequiredConnections(state)

      if (successfulCount < requiredCount) {
        val successfulNames = state.openConnections.values.map(_._2).mkString(", ")
        val failedNames = state.failedConnections.values.map(_._2).mkString(", ")

        throw Status.UNAVAILABLE
          .withDescription(
            s"Failed to connect to the required number of scans. " +
              s"Required: $requiredCount, Connected: $successfulCount. " +
              s"Connected to: [$successfulNames]. Failed or missing: [$failedNames]."
          )
          .asRuntimeException()
      } else {
        logger.debug(
          s"Successfully connected to $successfulCount scan(s), meeting the threshold of $requiredCount."
        )
      }
    }

    private val currentScanConnectionsRef: AtomicReference[BftState] =
      new AtomicReference(
        BftState(
          initialScanConnections.zipWithIndex.map { case (conn, n) =>
            (conn.config.adminApi.url, (conn, s"Seed URL #$n"))
          }.toMap,
          initialFailedConnections.zipWithIndex.map { case ((uri, err), n) =>
            uri -> (err, s"FAILED Seed URL #$n")
          }.toMap,
        )
      )

    /** Updates the scan list according to the scans present in the DsoRules.
      * Additionally, if any previous connections to a Scan failed, they're retried.
      * This method should only be called once when creating a BftScanConnection and periodically by PeriodicAction,
      *  which ensures that there's never two concurrent calls to it.
      */
    def refresh(
        connection: BftScanConnection
    )(implicit tc: TraceContext): Future[ScanConnections] = {
      val currentState @ BftState(currentScanConnections, currentFailed) =
        currentScanConnectionsRef.get()
      logger.info(s"Started refreshing scan list from $currentState")

      for {
        // if the previous state had too many failed scans, we cannot fetch the new list of scans.
        // thus, we retry all failed connections first.
        (retriedScansFailedConnections, retriedScansSuccessfulConnections) <- attemptConnections(
          currentFailed.map { case (uri, (_, svName)) =>
            DsoScan(uri, svName)
          }.toSeq
        )
        retriedCurrentState = BftState(
          currentScanConnections ++ retriedScansSuccessfulConnections,
          retriedScansFailedConnections.toMap,
        )
        // this state will be used to fetch the scans in the next call
        _ = currentScanConnectionsRef.set(retriedCurrentState)
        // these will be BFT-read, failing if there's no consensus
        scansInDsoRules <- getScans(connection)

        filteredScans = filterScans(scansInDsoRules)

        dsoScanSeq: Seq[(String, String)] = filteredScans.map(scan =>
          (scan.svName, scan.publicUrl.toString)
        )

        _ = scanUrlsChangedCallback(dsoScanSeq)

        newState <- computeNewState(retriedCurrentState, filteredScans)
      } yield {
        currentScanConnectionsRef.set(newState)
        logger.info(s"Updated scan list with ${dsoScanSeq.length} scans: $newState")

        val connections = newState.scanConnections
        validateState(newState)
        connections
      }
    }

    private def computeNewState(
        currentState: BftState,
        scansInDsoRules: Seq[DsoScan],
    )(implicit tc: TraceContext): Future[BftState] = {
      val BftState(currentScanConnections, currentFailed) = currentState
      val currentScans = (currentScanConnections.keys ++ currentFailed.keys).toSet

      val newScans = scansInDsoRules.filter(scan => !currentScans.contains(scan.publicUrl))
      val removedScans = currentScans.filter(url => !scansInDsoRules.exists(_.publicUrl == url))
      if (scansInDsoRules.isEmpty) {
        // This is expected on app init, and is retried when building the BftScanConnection
        Future.failed(
          io.grpc.Status.FAILED_PRECONDITION
            .withDescription(
              s"Scan list in DsoRules is empty. Last known list: $currentState"
            )
            .asRuntimeException()
        )
      } else {
        for {
          (newScansFailedConnections, newScansSuccessfulConnections) <- attemptConnections(
            newScans
          )
        } yield {
          logger.info(
            s"New successful scans: ${newScansSuccessfulConnections.map(_._1)}, " +
              s"new failed scans: ${newScansFailedConnections.map(_._1)}, " +
              s"removed scans: $removedScans"
          )

          removedScans.foreach { url =>
            currentScanConnections.get(url).foreach { case (connection, svName) =>
              logger.info(
                s"Closing connection to scan of $svName ($url) as it's been removed from the DsoRules scan list."
              )
              attemptToClose(connection)
            }
          }
          BftState(
            (currentScanConnections -- removedScans) ++ newScansSuccessfulConnections,
            (currentFailed -- removedScans) ++ newScansFailedConnections,
          )
        }
      }
    }

    /** Attempts to connect to all passed scans, returning two tuples containing the ones that failed to connect
      * and the ones that succeeded, respectively.
      */
    private def attemptConnections(
        scans: Seq[DsoScan]
    )(implicit
        tc: TraceContext
    ): Future[(Seq[(Uri, (Throwable, SvName))], Seq[(Uri, (SingleScanConnection, SvName))])] = {
      logger.info(s"Attempting to connect to scans: ${scans.map(_.svName)}")
      MonadUtil
        .sequentialTraverse(scans) { scan =>
          logger.debug(s"Attempting to connect to Scan: $scan.")
          connectionBuilder(scan.publicUrl)
            .transformWith {
              case Success(conn) =>
                logger.info(
                  s"Successfully connected to scan of ${scan.svName} (${scan.publicUrl})."
                )
                Future.successful(Right(scan.publicUrl -> (conn, scan.svName)))
              case Failure(err) =>
                if (retryProvider.isClosing)
                  logger.info(
                    s"Suppressed warning, as we're shutting down: Failed to connect to scan of ${scan.svName} (${scan.publicUrl}).",
                    err,
                  )
                else
                  logger.warn(
                    s"Failed to connect to scan of ${scan.svName} (${scan.publicUrl}).",
                    err,
                  )
                Future.successful(Left(scan.publicUrl -> (err, scan.svName)))
            }
        }
        .map(_.partitionEither(identity))
    }

    private def attemptToClose(
        connection: SingleScanConnection
    )(implicit tc: TraceContext): Unit = {
      try {
        connection.close()
      } catch {
        case NonFatal(ex) =>
          logger.warn(s"Failed to close connection to scan ${connection.config.adminApi.url}", ex)
      }
    }

    protected def currentConnectionsState: ScanConnections =
      currentScanConnectionsRef.get().scanConnections

    def scanConnections: ScanConnections

    override protected def closeAsync(): Seq[AsyncOrSyncCloseable] =
      initialScanConnections.zipWithIndex.map { case (connection, i) =>
        SyncCloseable(s"scan_connection_$i", connection.close())
      }
  }

  class AllDsoScansBft(
      override val initialScanConnections: Seq[SingleScanConnection],
      override val initialFailedConnections: Map[Uri, Throwable],
      override val connectionBuilder: Uri => Future[SingleScanConnection],
      protected val scanUrlsChangedCallback: Seq[(String, String)] => Future[Unit],
      override val getScans: BftScanConnection => Future[Seq[DsoScan]],
      override val scansRefreshInterval: NonNegativeFiniteDuration,
      override val retryProvider: RetryProvider,
      override val loggerFactory: NamedLoggerFactory,
  )(implicit override val ec: ExecutionContext)
      extends Bft {

    override protected def filterScans(allScans: Seq[DsoScan]): Seq[DsoScan] = allScans

    override protected def getRequiredConnections(state: BftState): Int = {
      val totalNumber = state.openConnections.size + state.failedConnections.size
      Thresholds.requiredNumScanThreshold(totalNumber).value
    }

    override def scanConnections: ScanConnections = {
      val connections = currentConnectionsState
      connections.copy(targetTotalNumber = None, threshold = None)
    }
  }

  class ConfigurationProvidedScansBft(
      svNames: NonEmptyList[String],
      threshold: Option[Int],
      override val initialScanConnections: Seq[SingleScanConnection],
      override val initialFailedConnections: Map[Uri, Throwable],
      override val connectionBuilder: Uri => Future[SingleScanConnection],
      protected val scanUrlsChangedCallback: Seq[(String, String)] => Future[Unit],
      override val getScans: BftScanConnection => Future[Seq[DsoScan]],
      override val scansRefreshInterval: NonNegativeFiniteDuration,
      override val retryProvider: RetryProvider,
      override val loggerFactory: NamedLoggerFactory,
  )(implicit override val ec: ExecutionContext, tc: TraceContext)
      extends Bft {

    private val svNamesSet = svNames.toList.toSet

    override protected def filterScans(allScans: Seq[DsoScan]): Seq[DsoScan] = {
      val targetScans = allScans.filter(scan => svNamesSet.contains(scan.svName))
      val foundSvs = targetScans.map(_.svName).toSet
      val missingSvs = svNamesSet -- foundSvs

      logger.trace(s"Discovered the following trusted scans from the network: ${targetScans
          .map(s => s"Name=${s.svName}, URL=${s.publicUrl}")
          .mkString("; ")}")

      if (missingSvs.nonEmpty) {
        logger.debug(
          s"Configured trusted SVs not found in the DSO rules: ${missingSvs.mkString(", ")}"
        )
      }

      targetScans
    }

    override protected def getRequiredConnections(state: BftState): Int = {
      threshold.getOrElse(Thresholds.requiredNumScanThreshold(svNames.size).value)
    }

    override def scanConnections: ScanConnections = {
      val connections = currentConnectionsState
      connections.copy(
        targetTotalNumber = Some(svNames.size),
        threshold = threshold,
      )
    }
  }

  object Bft {
    def getScansInDsoRules(
        connection: BftScanConnection
    )(implicit tc: TraceContext, ec: ExecutionContext): Future[Seq[DsoScan]] = {
      for {
        decentralizedSynchronizerId <- connection.getAmuletRulesDomain()(tc)
        scans <- connection.listDsoScans()
        domainScans <- scans
          .find(_.synchronizerId == decentralizedSynchronizerId)
          .map(e => Future.successful(e.scans))
          .getOrElse(
            Future.failed(
              new IllegalStateException(
                s"The global domain $decentralizedSynchronizerId is not present in the scans response: $scans"
              )
            )
          )
      } yield domainScans
    }

    def getPeerScansFromStore(store: ScanStore, ownSvName: String)(implicit
        tc: TraceContext,
        ec: ExecutionContext,
    ): Future[Seq[DsoScan]] = {
      for {
        decentralizedSynchronizerId <- store.getDecentralizedSynchronizerId()
        scans <- store.listDsoScans()
        domainScans <- scans
          .find(_._1 == decentralizedSynchronizerId.toProtoPrimitive)
          .map(e => Future.successful(e._2.filter(_.svName != ownSvName)))
          .getOrElse(
            Future.failed(
              new IllegalStateException(
                s"The global domain $decentralizedSynchronizerId is not present in the scans response: $scans"
              )
            )
          )
      } yield domainScans.map(scanInfo => DsoScan(scanInfo.publicUrl, scanInfo.svName))
    }

    def getPeerScansFromDsoRules(store: DsoRulesStore, ownSvParty: PartyId)(implicit
        tc: TraceContext,
        ec: ExecutionContext,
    ): Future[Seq[DsoScan]] =
      store.getDsoRulesWithSvNodeStates().map { rulesAndStates =>
        rulesAndStates.svNodeStates.values
          .filterNot(_.payload.sv == ownSvParty.toProtoPrimitive)
          .flatMap { nodeState =>
            nodeState.payload.state.synchronizerNodes.asScala.values
              .flatMap(
                _.scan.toScala.toList
                  .map(scan => DsoScan(Uri(scan.publicUrl), nodeState.payload.sv))
              )
          }
          .toList
          .sortBy(_.publicUrl.toString)
      }
  }

  private def bootstrapWithSeedNodes(
      seedUrls: NonEmptyList[Uri],
      amuletRulesCacheTimeToLive: NonNegativeFiniteDuration,
      spliceLedgerClient: SpliceLedgerClient,
      scansRefreshInterval: NonNegativeFiniteDuration,
      clock: Clock,
      retryProvider: RetryProvider,
      loggerFactory: NamedLoggerFactory,
      builder: (Uri, NonNegativeFiniteDuration) => Future[SingleScanConnection],
      refreshScanUrlsCallback: Seq[(String, String)] => Future[Unit],
      connectionMetrics: Option[ScanConnectionMetrics],
      disableBackgroundRefresh: Boolean = false,
  )(implicit
      ec: ExecutionContextExecutor,
      tc: TraceContext,
      mat: Materializer,
  ): Future[BftScanConnection] = {
    val logger = loggerFactory.getTracedLogger(getClass)

    logger.info(s"Validator bootstrapping with ${seedUrls.size} seed URLs: ${seedUrls.toList}")

    for {
      initialSeedConnections <- seedUrls.traverse(uri =>
        builder(uri, amuletRulesCacheTimeToLive).transformWith {
          case Success(conn) => Future.successful(Right(conn))
          case Failure(err) => Future.successful(Left(uri -> err))
        }
      )
      (failedSeeds, successfulSeedConnections) = initialSeedConnections.toList.partitionEither(
        identity
      )

      bftConnection <- {
        if (successfulSeedConnections.isEmpty) {
          Future.failed(
            Status.UNAVAILABLE
              .withDescription(
                s"Failed to connect to any seed URLs for bootstrapping: ${seedUrls.toList}"
              )
              .asRuntimeException()
          )
        } else {
          val tempScanList = new AllDsoScansBft(
            successfulSeedConnections,
            failedSeeds.toMap,
            uri => builder(uri, amuletRulesCacheTimeToLive),
            refreshScanUrlsCallback,
            Bft.getScansInDsoRules,
            scansRefreshInterval,
            retryProvider,
            loggerFactory,
          )
          val connection = new BftScanConnection(
            spliceLedgerClient,
            amuletRulesCacheTimeToLive,
            tempScanList,
            clock,
            retryProvider,
            loggerFactory,
            connectionMetrics,
            disableBackgroundRefresh,
          )
          logger.info(s"Bootstrapping with seed nodes to fetch the full network scan list.")
          Future.successful(connection)
        }
      }
    } yield bftConnection
  }

  def apply(
      spliceLedgerClient: SpliceLedgerClient,
      config: BftScanClientConfig,
      upgradesConfig: UpgradesConfig,
      clock: Clock,
      retryProvider: RetryProvider,
      loggerFactory: NamedLoggerFactory,
      connectionMetrics: Option[ScanConnectionMetrics] = None,
      lastPersistedScanUrlList: () => Future[Option[List[(String, String)]]] = () =>
        Future.successful(None),
      persistScanUrlsCallback: Seq[(String, String)] => Future[Unit] = _ => Future.unit,
  )(implicit
      ec: ExecutionContextExecutor,
      tc: TraceContext,
      mat: Materializer,
      httpClient: HttpClient,
      templateDecoder: TemplateJsonDecoder,
  ): Future[BftScanConnection] = {

    val builder =
      buildScanConnection(upgradesConfig, clock, retryProvider, loggerFactory, connectionMetrics)
    val logger = loggerFactory.getTracedLogger(getClass)

    config match {
      case BftScanClientConfig.TrustSingle(url, ttl) =>
        for {
          conn <- builder(url, ttl)
          scanList = new TrustSingle(conn, retryProvider, loggerFactory)
        } yield new BftScanConnection(
          spliceLedgerClient,
          ttl,
          scanList,
          clock,
          retryProvider,
          loggerFactory,
          connectionMetrics,
        )

      case ts @ BftScanClientConfig.BftCustom(_, _, _, _, _, _) =>
        // We bootstrap with the set of provided seed-urls.
        // Since not all trusted SV seeds are provided (most likely), they will not be used in the initial scan connection checking.
        // In the future, add a new threshold for how many trusted seed-urls should be there.
        for {
          lastPersistedScans <- lastPersistedScanUrlList()
          bootstrapUris: NonEmptyList[Uri] =
            if (ts.useLastKnownConnectionsForInitialization) {
              lastPersistedScans match {
                case Some(list) if list.nonEmpty =>
                  val urlStrings: List[String] = list.map(_._2)
                  val uris: List[Uri] = urlStrings.map(u => Uri(u))
                  NonEmptyList.fromList(uris).getOrElse {
                    ts.seedUrls
                  }
                case _ =>
                  ts.seedUrls
              }
            } else {
              ts.seedUrls
            }
          tempBftConnection <- bootstrapWithSeedNodes(
            bootstrapUris,
            ts.amuletRulesCacheTimeToLive,
            spliceLedgerClient,
            ts.scansRefreshInterval,
            clock,
            retryProvider,
            loggerFactory,
            builder,
            if (ts.useLastKnownConnectionsForInitialization) { persistScanUrlsCallback }
            else { _ => Future.unit },
            connectionMetrics,
            disableBackgroundRefresh = true,
          )

          // Use the temporary connection to get a consensus on the full list of scans.
          // Future.delegate turns a synchronous throw into a failed future, so the
          // andThen cleanup always runs.
          allScans <- Future.delegate(Bft.getScansInDsoRules(tempBftConnection)).andThen { case _ =>
            tempBftConnection.close()
          }

          trustedScans = allScans.filter(scan => ts.svNames.toList.contains(scan.svName))

          trustedScanDetails = trustedScans
            .map(s => s"  - Name: ${s.svName}, URL: ${s.publicUrl}")
            .mkString("\n")
          _ = logger.info(s"all available trusted scans on booststrap:\n$trustedScanDetails")

          initialConnections <- Future.traverse(trustedScans)(scan =>
            builder(scan.publicUrl, ts.amuletRulesCacheTimeToLive).transformWith {
              case Success(conn) =>
                logger.info(
                  s"Successfully established initial connection to trusted scan: ${scan.svName}"
                )
                Future.successful(Right(conn))
              case Failure(err) => Future.successful(Left(scan.publicUrl -> err))
            }
          )
          (failed, connections) = initialConnections.toList.partitionEither(identity)

          successfulConnectionDetails = connections
            .map(c => s"  - ${c.config.adminApi.url}")
            .mkString("\n")
          failedConnectionDetails = failed
            .map { case (uri, err) => s"  - $uri (${err.getMessage})" }
            .mkString("\n")
          _ = logger.info(
            s"initial connection attempts complete. Successful (${connections.size}):\n$successfulConnectionDetails\nFailed (${failed.size}):\n$failedConnectionDetails"
          )

          scanList = new ConfigurationProvidedScansBft(
            ts.svNames,
            ts.threshold,
            connections,
            failed.toMap,
            uri => builder(uri, ts.amuletRulesCacheTimeToLive),
            if (ts.useLastKnownConnectionsForInitialization) { persistScanUrlsCallback }
            else { _ => Future.unit },
            Bft.getScansInDsoRules,
            ts.scansRefreshInterval,
            retryProvider,
            loggerFactory,
          )

          bftConnection = new BftScanConnection(
            spliceLedgerClient,
            ts.amuletRulesCacheTimeToLive,
            scanList,
            clock,
            retryProvider,
            loggerFactory,
            connectionMetrics,
          )

          _ <- retryProvider
            .waitUntil(
              RetryFor.WaitingOnInitDependency,
              "refresh_initial_scan_list",
              "Scan list is refreshed.",
              scanList
                .refresh(bftConnection)
                .recoverWith { case NonFatal(ex) =>
                  Future.failed(
                    Status.UNAVAILABLE
                      .withDescription("Failed to refresh scan list on init")
                      .withCause(ex)
                      .asException()
                  )
                }
                .map(_ => ()),
              loggerFactory.getTracedLogger(classOf[BftScanConnection]),
            )
            .recoverWith { case NonFatal(ex) =>
              // do not leak the scan connections when initialization ultimately fails
              bftConnection.close()
              Future.failed(ex)
            }
        } yield bftConnection

      case bft @ BftScanClientConfig.Bft(_, _, _, _) =>
        for {
          lastPersistedScans <- lastPersistedScanUrlList()
          bootstrapUris: NonEmptyList[Uri] =
            if (bft.useLastKnownConnectionsForInitialization) {
              lastPersistedScans match {
                case Some(list) if list.nonEmpty =>
                  val urlStrings: List[String] = list.map(_._2)
                  val uris: List[Uri] = urlStrings.map(u => Uri(u))
                  NonEmptyList.fromList(uris).getOrElse {
                    bft.seedUrls
                  }
                case _ =>
                  bft.seedUrls
              }
            } else {
              bft.seedUrls
            }

          bftConnection <- bootstrapWithSeedNodes(
            bootstrapUris,
            bft.amuletRulesCacheTimeToLive,
            spliceLedgerClient,
            bft.scansRefreshInterval,
            clock,
            retryProvider,
            loggerFactory,
            builder,
            if (bft.useLastKnownConnectionsForInitialization) { persistScanUrlsCallback }
            else { _ => Future.unit },
            connectionMetrics,
          )
          _ <- retryProvider
            .waitUntil(
              RetryFor.WaitingOnInitDependency,
              "refresh_initial_scan_list",
              "Scan list is refreshed.",
              bftConnection.scanList
                .asInstanceOf[AllDsoScansBft]
                .refresh(bftConnection)
                .recoverWith { case NonFatal(ex) =>
                  Future.failed(
                    Status.UNAVAILABLE
                      .withDescription("Failed to refresh scan list on init")
                      .withCause(ex)
                      .asException()
                  )
                }
                .map(_ => ()),
              loggerFactory.getTracedLogger(classOf[BftScanConnection]),
            )
            .recoverWith { case NonFatal(ex) =>
              // do not leak the seed scan connections when initialization ultimately fails
              bftConnection.close()
              Future.failed(ex)
            }
        } yield bftConnection
    }
  }

  def peerScanConnection(
      getPeerScans: () => Future[Seq[DsoScan]],
      spliceLedgerClient: SpliceLedgerClient,
      scansRefreshInterval: NonNegativeFiniteDuration,
      amuletRulesCacheTimeToLive: NonNegativeFiniteDuration,
      upgradesConfig: UpgradesConfig,
      clock: Clock,
      retryProvider: RetryProvider,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      ec: ExecutionContextExecutor,
      tc: TraceContext,
      mat: Materializer,
      httpClient: HttpClient,
      templateDecoder: TemplateJsonDecoder,
  ): Future[BftScanConnection] = {
    val builder = buildScanConnection(upgradesConfig, clock, retryProvider, loggerFactory, None)

    for {
      scans <- retryProvider.retry(
        RetryFor.WaitingOnInitDependency,
        "fetch_peer_scan_list",
        "Peer scans found.",
        getPeerScans().flatMap {
          case Nil =>
            Future.failed(
              Status.UNAVAILABLE
                .withDescription("No peer scans found")
                .asException()
            )
          case scans => Future.successful(scans)
        },
        loggerFactory.getTracedLogger(classOf[BftScanConnection]),
      )
      initialConnections <- MonadUtil
        .sequentialTraverse(scans)(scan =>
          builder(scan.publicUrl, amuletRulesCacheTimeToLive).transformWith {
            case Success(conn) => Future.successful(Right(conn))
            case Failure(err) => Future.successful(Left(scan.publicUrl -> err))
          }
        )
      (failed, connections) = initialConnections.toList.partitionEither(identity)

      scanList = new AllDsoScansBft(
        connections,
        failed.toMap,
        uri => builder(uri, amuletRulesCacheTimeToLive),
        _ => Future.unit,
        _ => getPeerScans(),
        scansRefreshInterval,
        retryProvider,
        loggerFactory,
      )
    } yield new BftScanConnection(
      spliceLedgerClient,
      amuletRulesCacheTimeToLive,
      scanList,
      clock,
      retryProvider,
      loggerFactory,
    )
  }

  private def buildScanConnection(
      upgradesConfig: UpgradesConfig,
      clock: Clock,
      retryProvider: RetryProvider,
      loggerFactory: NamedLoggerFactory,
      connectionMetrics: Option[ScanConnectionMetrics],
  )(implicit
      ec: ExecutionContextExecutor,
      tc: TraceContext,
      mat: Materializer,
      httpClient: HttpClient,
      templateDecoder: TemplateJsonDecoder,
  ): (Uri, NonNegativeFiniteDuration) => Future[SingleScanConnection] =
    (uri: Uri, amuletRulesCacheTimeToLive: NonNegativeFiniteDuration) =>
      ScanConnection
        .singleUncached( // BFTScanConnection caches itself so that caches don't desync
          ScanAppClientConfig(
            NetworkAppClientConfig(
              uri
            ),
            amuletRulesCacheTimeToLive,
          ),
          upgradesConfig,
          clock,
          retryProvider,
          loggerFactory,
          // We only need f+1 Scans to be available, so as long as those are connected we don't need to slow init down.
          // Furthermore, the refresh (either on init, or periodically) will retry anyway.
          retryConnectionOnInitialFailure = false,
          connectionMetrics,
        )

  sealed trait BftScanClientConfig {
    def amuletRulesCacheTimeToLive: NonNegativeFiniteDuration
    def setAmuletRulesCacheTimeToLive(ttl: NonNegativeFiniteDuration): BftScanClientConfig
  }
  object BftScanClientConfig {
    case class TrustSingle(
        url: Uri,
        amuletRulesCacheTimeToLive: NonNegativeFiniteDuration =
          ScanAppClientConfig.DefaultAmuletRulesCacheTimeToLive,
    ) extends BftScanClientConfig {
      def setAmuletRulesCacheTimeToLive(ttl: NonNegativeFiniteDuration): TrustSingle =
        copy(amuletRulesCacheTimeToLive = ttl)
    }

    case class BftCustom(
        seedUrls: NonEmptyList[Uri], // by default only one seed_url is provided
        threshold: Option[Int] = None, // default to len(seedUrls)/3+1
        svNames: NonEmptyList[String], // should be at least 1
        amuletRulesCacheTimeToLive: NonNegativeFiniteDuration =
          ScanAppClientConfig.DefaultAmuletRulesCacheTimeToLive,
        scansRefreshInterval: NonNegativeFiniteDuration =
          ScanAppClientConfig.DefaultScansRefreshInterval,
        useLastKnownConnectionsForInitialization: Boolean = true,
    ) extends BftScanClientConfig {
      def setAmuletRulesCacheTimeToLive(ttl: NonNegativeFiniteDuration): BftCustom =
        copy(amuletRulesCacheTimeToLive = ttl)
    }

    case class Bft(
        seedUrls: NonEmptyList[Uri],
        scansRefreshInterval: NonNegativeFiniteDuration =
          ScanAppClientConfig.DefaultScansRefreshInterval,
        amuletRulesCacheTimeToLive: NonNegativeFiniteDuration =
          ScanAppClientConfig.DefaultAmuletRulesCacheTimeToLive,
        useLastKnownConnectionsForInitialization: Boolean = true,
    ) extends BftScanClientConfig {
      def setAmuletRulesCacheTimeToLive(ttl: NonNegativeFiniteDuration): Bft =
        copy(amuletRulesCacheTimeToLive = ttl)
    }
  }

  // url makes each abstention a distinct key, so abstentions never group into a deciding quorum
  final case class IgnoreResponse(url: Uri)
      extends RuntimeException(s"Scan $url has no answer to contribute to consensus")
      with NoStackTrace

  /** Classification of a probe response for two-phase BFT reads.
    * `WithData` = usable data. `WithoutData` = definite "cannot
    * provide" (dropped from quorum). `Unavailable` = no response or
    * still processing (kept in quorum as possibly disagreeing).
    */
  sealed trait ProbeVerdict[+V] extends Product with Serializable
  object ProbeVerdict {
    final case class WithData[V](value: V) extends ProbeVerdict[V]
    case object WithoutData extends ProbeVerdict[Nothing]
    case object Unavailable extends ProbeVerdict[Nothing]
  }

  /** Result of the first phase of a two-phase BFT call, where every open scan
    * is probed to classify what it can contribute to consensus.
    *
    * @param responses  One verdict per open connection: [[ProbeVerdict.WithData]]
    *                   for cached responses, [[ProbeVerdict.WithoutData]] for
    *                   explicit opt-outs, [[ProbeVerdict.Unavailable]] for probes
    *                   that failed or returned inconclusive.
    * @param failed     Number of scan connections that could not be opened at
    *                   the transport layer (from `ScanConnections.failed`);
    *                   never probed.
    */
  case class ConnectionsWithProbeVerdicts[T](
      responses: Map[SingleScanConnection, ProbeVerdict[T]],
      failed: Int,
  )
  object ConnectionsWithProbeVerdicts {
    def fromCall[T](connections: ScanConnections, call: SingleScanConnection => Future[T])(
        verdict: Try[T] => ProbeVerdict[T]
    )(implicit ec: ExecutionContext): Future[ConnectionsWithProbeVerdicts[T]] =
      for {
        responses <- Future.traverse(connections.open)(connection =>
          call(connection)
            .transformWith(r => Future.successful(verdict(r)))
            .map(result => connection -> result)
        )
      } yield ConnectionsWithProbeVerdicts(
        responses.toMap,
        connections.failed,
      )
  }
}
