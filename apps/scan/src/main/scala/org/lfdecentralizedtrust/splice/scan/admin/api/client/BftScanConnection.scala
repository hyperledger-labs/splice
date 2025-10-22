// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.api.client

import cats.data.{NonEmptyList, OptionT}
import cats.implicits.*
import org.lfdecentralizedtrust.splice.admin.http.HttpErrorWithHttpCode
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.FeaturedAppRight
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
import org.lfdecentralizedtrust.splice.config.{NetworkAppClientConfig, UpgradesConfig}
import org.lfdecentralizedtrust.splice.environment.PackageIdResolver.HasAmuletRules
import org.lfdecentralizedtrust.splice.environment.{
  BaseAppConnection,
  RetryFor,
  RetryProvider,
  SpliceLedgerClient,
}
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.http.v0.definitions.{
  AnsEntry,
  GetDsoInfoResponse,
  LookupTransferCommandStatusResponse,
  MigrationSchedule,
}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection.{
  BftCallConfig,
  ConsensusNotReached,
  ConsensusNotReachedRetryable,
  ScanConnections,
  ScanList,
}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.DsoScan
import org.lfdecentralizedtrust.splice.scan.config.ScanAppClientConfig
import org.lfdecentralizedtrust.splice.scan.store.ScanStore
import org.lfdecentralizedtrust.splice.store.HistoryBackfilling.SourceMigrationInfo
import org.lfdecentralizedtrust.splice.store.UpdateHistory.UpdateHistoryResponse
import org.lfdecentralizedtrust.splice.util.{
  ChoiceContextWithDisclosures,
  Contract,
  ContractWithState,
  FactoryChoiceWithDisclosures,
  TemplateJsonDecoder,
}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.{
  AsyncOrSyncCloseable,
  FlagCloseableAsync,
  FutureUnlessShutdown,
  SyncCloseable,
}
import com.digitalasset.canton.logging.{
  ErrorLoggingContext,
  NamedLoggerFactory,
  NamedLogging,
  TracedLogger,
}
import com.digitalasset.canton.time.{Clock, PeriodicAction}
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.LoggerUtil
import com.digitalasset.canton.util.MonadUtil
import com.digitalasset.canton.util.retry.{ErrorKind, ExceptionRetryPolicy}
import io.circe.Json
import io.grpc.Status
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.apache.pekko.stream.Materializer
import org.apache.pekko.util.ByteString
import org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpCommandException
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.allocationv1.Allocation
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.allocationinstructionv1
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.transferinstructionv1
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.transferinstructionv1.TransferInstruction
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  DsoRules_CloseVoteRequestResult,
  VoteRequest,
}
import org.lfdecentralizedtrust.tokenstandard.{
  allocation,
  allocationinstruction,
  metadata,
  transferinstruction,
}
import org.lfdecentralizedtrust.tokenstandard.transferinstruction.v1.definitions.TransferFactoryWithChoiceContext
import org.slf4j.event.Level

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future, Promise}
import scala.util.control.NonFatal
import scala.util.{Failure, Random, Success, Try}
import scala.jdk.CollectionConverters.*

class BftScanConnection(
    override protected val amuletLedgerClient: SpliceLedgerClient,
    override protected val amuletRulesCacheTimeToLive: NonNegativeFiniteDuration,
    val scanList: ScanList,
    protected val clock: Clock,
    val retryProvider: RetryProvider,
    val loggerFactory: NamedLoggerFactory,
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
    case ts: BftScanConnection.TrustSpecificScanList =>
      Some(
        new PeriodicAction(
          clock,
          com.digitalasset.canton.time.NonNegativeFiniteDuration
            .fromConfig(ts.scansRefreshInterval),
          loggerFactory,
          retryProvider.timeouts,
          "refresh_scan_list_trust_specific",
        )({ tc =>
          FutureUnlessShutdown.outcomeF(
            retryProvider.retry(
              RetryFor.LongRunningAutomation,
              "refresh_trust_specific_scan_list",
              "refresh_trust_specific_scan_list",
              ts.refresh(amuletLedgerClient, clock)(ec, tc, mat),
              logger,
            )(implicitly, TraceContext.empty, implicitly)
          )
        })
      )
    case bft: BftScanConnection.Bft =>
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
  }

  override def listVoteRequests()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Seq[Contract[VoteRequest.ContractId, VoteRequest]]] =
    bftCall(
      _.listVoteRequests()
    )

  override def getDsoPartyId()(implicit ec: ExecutionContext, tc: TraceContext): Future[PartyId] =
    bftCall(
      _.getDsoPartyId()
    )

  override def getDsoInfo()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[GetDsoInfoResponse] =
    bftCall(
      _.getDsoInfo()
    )

  override protected def runGetAmuletRulesWithState(
      cachedAmuletRules: Option[ContractWithState[AmuletRules.ContractId, AmuletRules]]
  )(implicit tc: TraceContext): Future[ContractWithState[AmuletRules.ContractId, AmuletRules]] =
    bftCall(
      _.getAmuletRulesWithState(cachedAmuletRules)
    )

  override def getDsoRules(
  )(implicit
      tc: TraceContext
  ): Future[Contract[DsoRules.ContractId, DsoRules]] =
    bftCall(_.getDsoRules())

  override protected def runGetExternalPartyAmuletRules(
      cachedExternalPartyAmuletRules: Option[
        ContractWithState[ExternalPartyAmuletRules.ContractId, ExternalPartyAmuletRules]
      ]
  )(implicit
      tc: TraceContext
  ): Future[ContractWithState[ExternalPartyAmuletRules.ContractId, ExternalPartyAmuletRules]] =
    bftCall(
      _.getExternalPartyAmuletRules(cachedExternalPartyAmuletRules)
    )

  override protected def runGetAnsRules(
      cachedAnsRules: Option[ContractWithState[AnsRules.ContractId, AnsRules]]
  )(implicit tc: TraceContext): Future[ContractWithState[AnsRules.ContractId, AnsRules]] = bftCall(
    _.getAnsRules(cachedAnsRules)
  )

  def lookupAnsEntryByParty(id: PartyId)(implicit
      tc: TraceContext
  ): Future[Option[AnsEntry]] =
    bftCall(_.lookupAnsEntryByParty(id))

  def lookupAnsEntryByName(name: String)(implicit
      tc: TraceContext
  ): Future[Option[AnsEntry]] =
    bftCall(_.lookupAnsEntryByName(name))

  def listAnsEntries(namePrefix: Option[String], pageSize: Int)(implicit
      tc: TraceContext
  ): Future[Seq[AnsEntry]] =
    bftCall(_.listAnsEntries(namePrefix, pageSize))

  override protected def runGetOpenAndIssuingMiningRounds(
      cachedOpenRounds: Seq[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]],
      cachedIssuingRounds: Seq[ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]],
  )(implicit ec: ExecutionContext, mat: Materializer, tc: TraceContext): Future[
    (
        Seq[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]],
        Seq[ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]],
        BigInt,
    )
  ] = bftCall(_.getOpenAndIssuingMiningRounds(cachedOpenRounds, cachedIssuingRounds))

  override def listDsoSequencers()(implicit
      tc: TraceContext
  ): Future[Seq[HttpScanAppClient.DomainSequencers]] = {
    bftCall(_.listDsoSequencers())
  }

  override def listDsoScans()(implicit
      tc: TraceContext
  ): Future[Seq[HttpScanAppClient.DomainScans]] = {
    bftCall(_.listDsoScans())
  }

  override def lookupFeaturedAppRight(providerPartyId: PartyId)(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[Option[Contract[FeaturedAppRight.ContractId, FeaturedAppRight]]] = {
    bftCall(_.lookupFeaturedAppRight(providerPartyId))
  }

  override def getMigrationSchedule()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): OptionT[Future, MigrationSchedule] = OptionT(bftCall(_.getMigrationSchedule().value))

  private case class MigrationInfoResponses(
      withData: Map[SingleScanConnection, SourceMigrationInfo],
      withoutData: Set[SingleScanConnection],
      unknownStatus: Set[SingleScanConnection],
  )
  private def getMigrationInfoResponses(connections: ScanConnections, migrationId: Long)(implicit
      tc: TraceContext
  ): Future[MigrationInfoResponses] = for {
    results <- Future.traverse(connections.open)(connection =>
      connection
        .getMigrationInfo(migrationId)
        .transformWith(BftScanConnection.keyToGroupResponses)
        .map(result => connection -> result)
    )
  } yield {
    val withData = results.collect {
      case (connection, BftScanConnection.SuccessfulResponse(Some(info))) => connection -> info
    }.toMap
    val withoutData = results.collect {
      case (connection, BftScanConnection.SuccessfulResponse(None)) => connection
    }.toSet
    val unknownStatus = results.collect {
      case (connection, BftScanConnection.HttpFailureResponse(_, _)) => connection
      case (connection, BftScanConnection.ExceptionFailureResponse(_)) => connection
    }.toSet
    MigrationInfoResponses(
      withData,
      withoutData,
      unknownStatus,
    )
  }

  override def getMigrationInfo(migrationId: Long)(implicit
      tc: TraceContext
  ): Future[Option[SourceMigrationInfo]] = {
    val connections = scanList.scanConnections
    for {
      // Ask ALL scans for the migration info
      responses <- getMigrationInfoResponses(connections, migrationId)
      result <-
        if (responses.withData.nonEmpty) {
          // At least one scan reported to have some data for the given migration id
          val completeResponses = responses.withData.filter { case (_, migrationInfo) =>
            migrationInfo.complete
          }
          val importUpdatesCompleteResponses = responses.withData.filter {
            case (_, migrationInfo) =>
              migrationInfo.importUpdatesComplete
          }
          for {
            // We already have the responses, use bftCall() to avoid re-implementing the consensus logic.
            // All non-malicious scans that have backfilled the input migrationId should return
            // the same value for previousMigrationId.
            previousMigrationId <- bftCall(
              connection => Future.successful(completeResponses(connection).previousMigrationId),
              BftCallConfig.forAvailableData(connections, completeResponses.contains),
              // This method is very sensitive to unavailable SVs.
              // Do not log warnings for failures to reach consensus, as this would be too noisy,
              // and instead rely on metrics to situations when backfilling is not progressing.
              Level.INFO,
            )
            lastImportUpdateId <- bftCall(
              connection =>
                Future.successful(importUpdatesCompleteResponses(connection).lastImportUpdateId),
              BftCallConfig.forAvailableData(connections, importUpdatesCompleteResponses.contains),
              // This method is very sensitive to unavailable SVs.
              // Do not log warnings for failures to reach consensus, as this would be too noisy,
              // and instead rely on metrics to situations when backfilling is not progressing.
              Level.INFO,
            )
          } yield {
            @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
            val unionOfRecordTimeRanges =
              responses.withData.values.map(_.recordTimeRange).reduce(_ |+| _)
            Some(
              SourceMigrationInfo(
                previousMigrationId = previousMigrationId,
                recordTimeRange = unionOfRecordTimeRanges,
                lastImportUpdateId = lastImportUpdateId,
                complete = completeResponses.nonEmpty,
                importUpdatesComplete = importUpdatesCompleteResponses.nonEmpty,
              )
            )
          }
        } else if (responses.withoutData.nonEmpty) {
          // All scans reported to have no data for the given migration id
          logger.info(
            s"All ${responses.withoutData.size} available scans reported to have no data for migration ${migrationId}"
          )
          Future.successful(None)
        } else {
          // No valid response from any scan
          val httpError =
            HttpErrorWithHttpCode(
              StatusCodes.BadGateway,
              s"No valid response from any scan.",
            )
          Future.failed(httpError)
        }
    } yield result
  }

  override def lookupTransferCommandCounterByParty(receiver: PartyId)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[ContractWithState[TransferCommandCounter.ContractId, TransferCommandCounter]]] =
    bftCall(_.lookupTransferCommandCounterByParty(receiver))

  override def lookupTransferCommandStatus(sender: PartyId, nonce: Long)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[LookupTransferCommandStatusResponse]] =
    bftCall(_.lookupTransferCommandStatus(sender, nonce))

  override def lookupTransferPreapprovalByParty(receiver: PartyId)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[ContractWithState[TransferPreapproval.ContractId, TransferPreapproval]]] =
    bftCall(_.lookupTransferPreapprovalByParty(receiver))

  override def listVoteRequestResults(
      actionName: Option[String],
      accepted: Option[Boolean],
      requester: Option[String],
      effectiveFrom: Option[String],
      effectiveTo: Option[String],
      limit: Int,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Seq[DsoRules_CloseVoteRequestResult]] = bftCall(
    _.listVoteRequestResults(
      actionName,
      accepted,
      requester,
      effectiveFrom,
      effectiveTo,
      limit,
    )
  )

  override def getImportUpdates(
      migrationId: Long,
      afterUpdateId: String,
      count: Int,
  )(implicit tc: TraceContext): Future[Seq[UpdateHistoryResponse]] = {
    val connections = scanList.scanConnections
    for {
      // Ask ALL scans for the migration info so that we can figure out who has the data
      responses <- getMigrationInfoResponses(connections, migrationId)
      // Filter out connections that don't have any data
      withData = responses.withData.toList.filter { case (_, info) =>
        info.importUpdatesComplete
      }
      connectionsWithData = withData.map(_._1)
      // Make a BFT call to connections that have the data
      result <- bftCall(
        connection => connection.getImportUpdates(migrationId, afterUpdateId, count),
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
      responses <- getMigrationInfoResponses(connections, migrationId)
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
        TransferFactoryWithChoiceContext.TransferKind,
    )
  ] =
    bftCall(_.getTransferFactory(choiceArgs))

  def getTransferFactoryRaw(arg: transferinstruction.v1.definitions.GetFactoryRequest)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[transferinstruction.v1.definitions.TransferFactoryWithChoiceContext] =
    bftCall(_.getTransferFactoryRaw(arg))

  def getTransferInstructionAcceptContext(
      instructionCid: TransferInstruction.ContractId
  )(implicit tc: TraceContext): Future[ChoiceContextWithDisclosures] = bftCall(
    _.getTransferInstructionAcceptContext(instructionCid)
  )

  def getTransferInstructionRejectContext(
      instructionCid: TransferInstruction.ContractId
  )(implicit tc: TraceContext): Future[ChoiceContextWithDisclosures] = bftCall(
    _.getTransferInstructionRejectContext(instructionCid)
  )

  def getTransferInstructionWithdrawContext(
      instructionCid: TransferInstruction.ContractId
  )(implicit tc: TraceContext): Future[ChoiceContextWithDisclosures] = bftCall(
    _.getTransferInstructionWithdrawContext(instructionCid)
  )

  def getTransferInstructionAcceptContextRaw(
      instructionCid: String,
      body: transferinstruction.v1.definitions.GetChoiceContextRequest,
  )(implicit tc: TraceContext): Future[transferinstruction.v1.definitions.ChoiceContext] = bftCall(
    _.getTransferInstructionAcceptContextRaw(instructionCid, body)
  )

  def getTransferInstructionRejectContextRaw(
      instructionCid: String,
      body: transferinstruction.v1.definitions.GetChoiceContextRequest,
  )(implicit tc: TraceContext): Future[transferinstruction.v1.definitions.ChoiceContext] = bftCall(
    _.getTransferInstructionRejectContextRaw(instructionCid, body)
  )

  def getTransferInstructionWithdrawContextRaw(
      instructionCid: String,
      body: transferinstruction.v1.definitions.GetChoiceContextRequest,
  )(implicit tc: TraceContext): Future[transferinstruction.v1.definitions.ChoiceContext] = bftCall(
    _.getTransferInstructionWithdrawContextRaw(instructionCid, body)
  )

  def getRegistryInfo()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[metadata.v1.definitions.GetRegistryInfoResponse] =
    bftCall(_.getRegistryInfo())

  def lookupInstrument(instrumentId: String)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[metadata.v1.definitions.Instrument]] =
    bftCall(
      _.lookupInstrument(instrumentId)
    )

  def listInstruments(pageSize: Option[Int], pageToken: Option[String])(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Seq[metadata.v1.definitions.Instrument]] =
    bftCall(_.listInstruments(pageSize, pageToken))

  def getAllocationTransferContext(
      allocationCid: Allocation.ContractId
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ChoiceContextWithDisclosures] =
    bftCall(_.getAllocationTransferContext(allocationCid))

  def getAllocationTransferContextRaw(
      allocationId: String,
      body: allocation.v1.definitions.GetChoiceContextRequest,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[allocation.v1.definitions.ChoiceContext] =
    bftCall(_.getAllocationTransferContextRaw(allocationId, body))

  def getAllocationCancelContextRaw(
      allocationId: String,
      body: allocation.v1.definitions.GetChoiceContextRequest,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[allocation.v1.definitions.ChoiceContext] =
    bftCall(_.getAllocationCancelContextRaw(allocationId, body))

  def getAllocationWithdrawContextRaw(
      allocationId: String,
      body: allocation.v1.definitions.GetChoiceContextRequest,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[allocation.v1.definitions.ChoiceContext] =
    bftCall(_.getAllocationWithdrawContextRaw(allocationId, body))

  def getAllocationCancelContext(
      allocationCid: Allocation.ContractId
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ChoiceContextWithDisclosures] =
    bftCall(_.getAllocationCancelContext(allocationCid))

  def getAllocationWithdrawContext(
      allocationCid: Allocation.ContractId
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ChoiceContextWithDisclosures] =
    bftCall(_.getAllocationWithdrawContext(allocationCid))

  def getAllocationFactory(choiceArgs: allocationinstructionv1.AllocationFactory_Allocate)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[
    FactoryChoiceWithDisclosures[
      allocationinstructionv1.AllocationFactory.ContractId,
      allocationinstructionv1.AllocationFactory_Allocate,
    ]
  ] =
    bftCall(_.getAllocationFactory(choiceArgs))

  def getAllocationFactoryRaw(arg: allocationinstruction.v1.definitions.GetFactoryRequest)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[allocationinstruction.v1.definitions.FactoryWithChoiceContext] =
    bftCall(_.getAllocationFactoryRaw(arg))

  private def bftCall[T](
      call: SingleScanConnection => Future[T],
      callConfig: BftCallConfig = BftCallConfig.default(scanList.scanConnections),
      consensusFailureLogLevel: Level = Level.WARN,
      shortenResponsesForLog: T => Any = identity[T],
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[T] = {
    val connections = scanList.scanConnections
    if (!callConfig.enoughAvailableScans) {
      val totalNumber = connections.totalNumber
      val msg =
        s"Only ${callConfig.connections.size} scan instances can be used (out of $totalNumber configured ones), which are fewer than the necessary ${callConfig.targetSuccess} to achieve BFT guarantees."
      val exception = HttpErrorWithHttpCode(
        StatusCodes.BadGateway,
        msg,
      )
      LoggerUtil.logThrowableAtLevel(consensusFailureLogLevel, msg, exception)
      Future.failed(exception)
    } else {
      retryProvider
        .retryForClientCalls(
          "bft_call",
          s"Bft call with ${callConfig.targetSuccess} out of ${callConfig.requestsToDo} matching responses",
          BftScanConnection.executeCall(
            call,
            requestFrom = Random.shuffle(callConfig.connections).take(callConfig.requestsToDo),
            nTargetSuccess = callConfig.targetSuccess,
            logger,
            shortenResponsesForLog,
          ),
          logger,
          (_: String) => ConsensusNotReachedRetryable,
        )
        .recoverWith { case c: ConsensusNotReached =>
          val httpError = HttpErrorWithHttpCode(
            StatusCodes.BadGateway,
            s"Failed to reach consensus from ${callConfig.requestsToDo} Scan nodes, requiring ${callConfig.targetSuccess} matching responses.",
          )
          LoggerUtil.logThrowableAtLevel(consensusFailureLogLevel, s"Consensus not reached.", c)
          Future.failed(httpError)
        }
    }
  }

  override def closeAsync(): Seq[AsyncOrSyncCloseable] = {
    refreshAction.map(r => SyncCloseable("refresh_scan_list", r.close())).toList ++
      Seq[AsyncOrSyncCloseable](
        SyncCloseable("scan_list", scanList.close())
      )
  }
}
trait HasUrl {
  def url: Uri
}

object BftScanConnection {
  def executeCall[T, C <: HasUrl](
      call: C => Future[T],
      requestFrom: Seq[C],
      nTargetSuccess: Int,
      logger: TracedLogger,
      shortenResponsesForLog: T => Any = identity[T],
  )(implicit ec: ExecutionContext, tc: TraceContext, mat: Materializer): Future[T] = {
    require(requestFrom.nonEmpty, "At least one request must be made.")

    val responses =
      new ConcurrentHashMap[BftScanConnection.ScanResponse[T], List[Uri]]()
    val nResponsesDone = new AtomicInteger(0)
    val finalResponse = Promise[T]()

    requestFrom.foreach { scan =>
      call(scan)
        .transformWith(response => keyToGroupResponses(response).map(_ -> response))
        .foreach { case (key, response) =>
          val agreements =
            responses.compute(
              key,
              (_, scans) => scan.url :: Option(scans).getOrElse(List.empty),
            )

          if (agreements.size == nTargetSuccess) { // consensus has been reached
            finalResponse.tryComplete(response): Unit
          }

          if (nResponsesDone.incrementAndGet() == requestFrom.size) { // all Scans are done
            finalResponse.future.value match {
              case None =>
                val exception = ConsensusNotReached(
                  requestFrom.size,
                  responses,
                  shortenResponsesForLog,
                )
                finalResponse.tryFailure(exception): Unit
              case Some(consensusResponse) =>
                logDisagreements(logger, consensusResponse, responses)
            }
          }
        }
    }
    finalResponse.future
  }

  /** Responses are stored in a ConcurrentHashMap. Equality is defined as:
    * - Simple Scala equality when the response is successful (typically, 200 OK).
    * - Status code + response body when the response is not successful (best effort).
    * - Never equal when there's other exceptions (unless those define equality, which they typically don't).
    */
  private def keyToGroupResponses[T](
      r1: Try[T]
  )(implicit ec: ExecutionContext, mat: Materializer): Future[BftScanConnection.ScanResponse[T]] = {
    r1 match {
      case Success(value) => Future.successful(BftScanConnection.SuccessfulResponse(value))
      case Failure(unexpected: BaseAppConnection.UnexpectedHttpResponse)
          if unexpected.response.entity.contentType.mediaType == MediaTypes.`application/json` =>
        Unmarshal(unexpected.response.entity)
          .to[ByteString]
          .flatMap(s =>
            io.circe.jawn.parseByteBuffer(s.asByteBuffer) match {
              case Right(value) =>
                Future.successful(
                  BftScanConnection.HttpFailureResponse(unexpected.response.status, value)
                )
              case Left(failure) =>
                Future.successful(BftScanConnection.ExceptionFailureResponse(failure))
            }
          )
      case Failure(unexpected: HttpCommandException) =>
        Future.successful(
          BftScanConnection.HttpFailureResponse(
            unexpected.status,
            Json.obj("message" -> Json.fromString(unexpected.message)),
          )
        )
      case Failure(error) =>
        Future.successful(BftScanConnection.ExceptionFailureResponse(error))
    }
  }

  private def logDisagreements[T](
      logger: TracedLogger,
      consensusResponse: Try[T],
      responses: ConcurrentHashMap[BftScanConnection.ScanResponse[T], List[Uri]],
  )(implicit ec: ExecutionContext, mat: Materializer, tc: TraceContext): Unit = {
    keyToGroupResponses(consensusResponse).foreach { consensusResponseKey =>
      responses.remove(consensusResponseKey)
      responses.forEach { (disagreeingResponse, scanUrls) =>
        logger.info(
          s"Scans $scanUrls disagreed with the Consensus $consensusResponse and instead returned $disagreeingResponse"
        )
      }
    }
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
      connections.totalInSubset match {
        case Some(n) =>
          // Use the static total number of trusted SVs (n) for the threshold
          val threshold = connections.threshold.getOrElse((n / 3) + 1)
          BftCallConfig(
            connections = connections.open,
            requestsToDo = connections.open.size,
            targetSuccess = threshold,
          )
        case None =>
          val f = connections.f
          BftCallConfig(
            connections = connections.open,
            requestsToDo = 2 * f + 1,
            targetSuccess = f + 1,
          )
      }
    }

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
      totalInSubset: Option[Int] = None,
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

  class TrustSpecificScanList(
      val sv_names: NonEmptyList[String],
      val seedUrls: NonEmptyList[Uri],
      val threshold: Option[Int],
      val retryProvider: RetryProvider,
      val loggerFactory: NamedLoggerFactory,
      private val builder: (Uri, NonNegativeFiniteDuration) => Future[SingleScanConnection],
      val amuletRulesCacheTimeToLive: NonNegativeFiniteDuration,
      val scansRefreshInterval: NonNegativeFiniteDuration =
        ScanAppClientConfig.DefaultScansRefreshInterval,
  ) extends ScanList {

    private val stateRef =
      new AtomicReference[Map[String, Either[Throwable, SingleScanConnection]]](Map.empty)

    def initialize(
        spliceLedgerClient: SpliceLedgerClient,
        clock: Clock,
    )(implicit
        ec: ExecutionContextExecutor,
        tc: TraceContext,
        mat: Materializer,
    ): Future[Unit] = {
      val logger = loggerFactory.getTracedLogger(getClass)
      logger.info(s"Started initializing TrustSpecificScanList......")
      for {
        // bootstrap connection from one of the seed URLs.
        bootstrapConn <- _bootstrapConnection()

        // temporary wrapper connection just for the discovery API call.
        tempBftConnection = new BftScanConnection(
          spliceLedgerClient,
          amuletRulesCacheTimeToLive,
          new TrustSingle(bootstrapConn, retryProvider, loggerFactory),
          clock,
          retryProvider,
          loggerFactory,
        )

        // get all available scans from the ledger.
        allScans <- _discoverAllScans(tempBftConnection)

        // connect only to our trusted SVs.
        connection_map <- _attemptTrustedConnections(allScans)

        // verify threshold number of connections
        _ = _verifyThreshold(connection_map)

        _ = {
          stateRef.set(connection_map)
          logger.info(s"TrustSpecificScanList initialized successfully.")
        }
      } yield ()
    }

    // initial connection to a seed-url // when refresh is called

    private def _bootstrapConnection()(implicit
        ec: ExecutionContext,
        tc: TraceContext,
    ): Future[SingleScanConnection] = {

      val logger = loggerFactory.getTracedLogger(getClass)
      logger.info(
        s"Creating a bootstrap connection using one of provided svs or already existing connections"
      )

      // check for an existing healthy connection in the current state.
      stateRef.get().values.collectFirst { case Right(conn) => conn } match {
        case Some(existingConnection) =>
          // A healthy connection was found
          Future.successful(existingConnection)
        case None =>
          // no healthy connections exist.
          // connect to each seed URL, and return the first one that succeeds.
          seedUrls
            .traverse(uri => builder(uri, amuletRulesCacheTimeToLive).transform(Success(_)))
            .map(_.collectFirst { case Success(conn) => conn })
            .flatMap {
              case Some(newConnection) => {
                logger.info(s"Created a new bootstrap connection")
                Future.successful(newConnection)
              }
              case None =>
                Future.failed(
                  Status.UNAVAILABLE
                    .withDescription(
                      s"No healthy connections available and failed to connect to any seed URLs: ${seedUrls.toList}"
                    )
                    .asRuntimeException()
                )
            }
      }
    }

    private def _discoverAllScans(bootstrapConnection: BftScanConnection)(implicit
        tc: TraceContext,
        ec: ExecutionContext,
    ): Future[Seq[DsoScan]] = {
      val logger = loggerFactory.getTracedLogger(getClass)
      logger.info(s"Getting all available scans..")
      Bft.getScansInDsoRules(bootstrapConnection)
    }

    private def _attemptTrustedConnections(
        allScans: Seq[DsoScan]
    )(implicit
        ec: ExecutionContext,
        tc: TraceContext,
    ): Future[Map[String, Either[Throwable, SingleScanConnection]]] = {
      val trustedSvsSet = sv_names.toList.toSet
      val targetScans = allScans.filter(scan => trustedSvsSet.contains(scan.svName))
      val logger = loggerFactory.getTracedLogger(getClass)
      logger.info(
        s"Attempting to connect to trusted SVs. Trusted list: ${trustedSvsSet.mkString(", ")}"
      )
      logger.info(s"Discovered the following scans from the network: ${allScans
          .map(s => s"Name=${s.svName}, URL=${s.publicUrl}")
          .mkString("; ")}")
      MonadUtil
        .sequentialTraverse(targetScans) { scan =>
          builder(scan.publicUrl, amuletRulesCacheTimeToLive)
            .transform {
              case Success(conn) =>
                logger.info(s"Connection to trusted sv ${scan.svName} made.")
                Success(scan.svName -> Right(conn))
              case Failure(ex) =>
                logger.warn(s"Could not make connection to sv ${scan.svName}.", ex)
                Success(scan.svName -> Left(ex))
            }
        }
        .map(_.toMap)
    }

    private def _verifyThreshold(
        connections: Map[String, Either[Throwable, SingleScanConnection]]
    )(implicit tc: TraceContext): Unit = {
      val successfulCount = connections.values.count(_.isRight)
      val requiredCount = threshold.getOrElse((sv_names.size / 3) + 1)

      if (successfulCount < requiredCount) {
        val failedNames = connections.collect { case (name, Left(_)) => name }.mkString(", ")
        throw Status.UNAVAILABLE
          .withDescription(
            s"Failed to connect to required number of trusted scans. " +
              s"Required: $requiredCount, Connected: $successfulCount. Scan apps failed or missing from network: $failedNames."
          )
          .asRuntimeException()
      } else {
        val logger = loggerFactory.getTracedLogger(getClass)
        logger.info(s"created threshold number of scan connections.")
      }
    }

    def refresh(
        spliceLedgerClient: SpliceLedgerClient,
        clock: Clock,
    )(implicit
        ec: ExecutionContextExecutor,
        tc: TraceContext,
        mat: Materializer,
    ): Future[Unit] = {
      val logger = loggerFactory.getTracedLogger(getClass)
      logger.info("Starting refresh of TrustSpecificScanList...")

      for {
        // a fresh bootstrap connection.
        bootstrapConn <- _bootstrapConnection()

        tempBftConnection = new BftScanConnection(
          spliceLedgerClient,
          amuletRulesCacheTimeToLive,
          new TrustSingle(bootstrapConn, retryProvider, loggerFactory),
          clock,
          retryProvider,
          loggerFactory,
        )

        // current list of all scans from the ledger.
        allScans <- _discoverAllScans(tempBftConnection)

        // check current connections against the discovered list.
        newConnections <- _reconcileConnections(allScans)

        // verify the new state still meets the required threshold.
        _ = _verifyThreshold(newConnections)

        // update the connections with the refreshed connections.
        _ = {
          stateRef.set(newConnections)
          val successfulCount = newConnections.values.count(_.isRight)
          logger.info(s"Refresh complete. Active connections: $successfulCount / ${sv_names.size}.")
        }
      } yield ()
    }

    // checks if a connection is active

    private def _checkConnectionHealth(
        connection: SingleScanConnection
    )(implicit ec: ExecutionContext, tc: TraceContext): Future[Boolean] = {
      // getDsoInfo is a simple, reliable call to check for liveness.
      connection.getDsoInfo().map(_ => true).recover { case NonFatal(_) => false }
    }

    // create new connection for any that are unhealthy or missing.

    private def _reconcileConnections(
        allScans: Seq[DsoScan]
    )(implicit
        ec: ExecutionContext,
        tc: TraceContext,
    ): Future[Map[String, Either[Throwable, SingleScanConnection]]] = {
      val currentState = stateRef.get()
      val discoveredUrls = allScans.map(s => s.svName -> s.publicUrl).toMap

      val logger = loggerFactory.getTracedLogger(getClass)
      logger.info(s"Reconciliating the scan connections")

      // for every trusted SV, decide its new connection.
      MonadUtil
        .sequentialTraverse(sv_names.toList) { svName =>
          val newConnectionFuture = (currentState.get(svName), discoveredUrls.get(svName)) match {
            // case 1: connection exists. check if it's healthy.
            case (Some(Right(conn)), _) =>
              _checkConnectionHealth(conn).flatMap {
                case true => {
                  logger.info(s"Existing scan connection is healthy ${conn.url}")
                  Future.successful(Right(conn))
                } // healthy: keep it
                case false => // unhealthy: reconnect using its discovered URL.
                  {
                    logger.info(
                      s"Existing scan connection broken ${conn.url}. Attempting to reconnect"
                    )
                    discoveredUrls.get(svName) match {
                      case Some(url) => {
                        logger.info(s"Created new scan connection to ${url}")
                        builder(url, amuletRulesCacheTimeToLive).map(Right(_))
                      }
                      case None =>
                        Future.successful(
                          Left(
                            new IllegalStateException(
                              s"SV '$svName' is unhealthy and not found in DSO list."
                            )
                          )
                        )
                    }
                  }
              }
            // case 2: connection is not currently available. connect now.
            case (_, Some(url)) => {
              logger.info(s"Existing scan connection is not available, reconnecting ${url}")
              builder(url, amuletRulesCacheTimeToLive).map(Right(_))
            }
            // case 3: no connection and it's not in the DSO list.
            case (_, None) =>
              Future.successful(
                Left(new IllegalStateException(s"SV '$svName' not found in DSO list."))
              )
          }

          newConnectionFuture
            .recover { case NonFatal(ex) => Left(ex) }
            .map(result => svName -> result)
        }
        .map(_.toMap)
    }

    override def scanConnections: ScanConnections = {
      val currentConnections = stateRef.get()
      ScanConnections(
        open = currentConnections.values.collect { case Right(conn) => conn }.toSeq,
        failed = currentConnections.values.count(_.isLeft),
        threshold = threshold,
        totalInSubset = Some(sv_names.size),
      )
    }

    override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = {
      val connectionsToClose = stateRef.get().values.collect { case Right(conn) => conn }
      connectionsToClose.map { conn =>
        SyncCloseable(s"scan_connection_${conn.url}", conn.close())
      }.toSeq
    }
  }

  type SvName = String
  case class BftState(
      openConnections: Map[Uri, (SingleScanConnection, SvName)],
      failedConnections: Map[Uri, (Throwable, SvName)],
  ) {
    def scanConnections: ScanConnections =
      ScanConnections(openConnections.values.map(_._1).toSeq, failedConnections.size)
  }

  class Bft(
      initialScanConnections: Seq[SingleScanConnection],
      initialFailedConnections: Map[Uri, Throwable],
      connectionBuilder: Uri => Future[SingleScanConnection],
      getScans: BftScanConnection => Future[Seq[DsoScan]],
      val scansRefreshInterval: NonNegativeFiniteDuration,
      val retryProvider: RetryProvider,
      val loggerFactory: NamedLoggerFactory,
  )(implicit ec: ExecutionContext)
      extends ScanList {

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
        newState <- computeNewState(retriedCurrentState, scansInDsoRules)
      } yield {
        currentScanConnectionsRef.set(newState)
        logger.info(s"Updated scan list to $newState")

        val connections = newState.scanConnections
        val defaultCallConfig = BftCallConfig.default(connections)
        // Most but not all calls will use the default config.
        // Fail early if there are not enough Scans for the default config
        if (!defaultCallConfig.enoughAvailableScans) {
          throw io.grpc.Status.FAILED_PRECONDITION
            .withDescription(
              s"There are not enough Scans to satisfy f=${connections.f}. Will be retried. State: $newState"
            )
            .asRuntimeException()
        } else {
          connections
        }
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
      MonadUtil
        .sequentialTraverse(scans) { scan =>
          logger.info(s"Attempting to connect to Scan: $scan.")
          connectionBuilder(scan.publicUrl)
            .transformWith { result =>
              // logging
              result.failed.foreach { err =>
                // TODO(#815): abstract this pattern into the RetryProvider
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
              }
              // actual result
              Future.successful(
                result.toEither
                  .bimap(scan.publicUrl -> (_, scan.svName), scan.publicUrl -> (_, scan.svName))
              )
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

    override def scanConnections: ScanConnections = {
      currentScanConnectionsRef.get().scanConnections
    }

    override protected def closeAsync(): Seq[AsyncOrSyncCloseable] =
      initialScanConnections.zipWithIndex.map { case (connection, i) =>
        SyncCloseable(s"scan_connection_$i", connection.close())
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
  }

  def apply(
      spliceLedgerClient: SpliceLedgerClient,
      config: BftScanClientConfig,
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
    val builder = buildScanConnection(upgradesConfig, clock, retryProvider, loggerFactory)
    config match {
      case BftScanClientConfig.TrustSingle(url, amuletRulesCacheTimeToLive) =>
        // If this fails to connect, fail and let it retry
        val connectionF = builder(url, amuletRulesCacheTimeToLive)
        connectionF
          .map(conn =>
            new BftScanConnection(
              spliceLedgerClient,
              amuletRulesCacheTimeToLive,
              new TrustSingle(conn, retryProvider, loggerFactory),
              clock,
              retryProvider,
              loggerFactory,
            )
          )
      case config @ BftScanClientConfig.TrustSpecific(_, _, _, _) =>
        for {
          // empty ScanList instance. no connections made yet.
          scanList <- Future.successful(
            new TrustSpecificScanList(
              config.trusted_svs,
              config.seedUrls,
              config.threshold,
              retryProvider,
              loggerFactory,
              builder,
              config.amuletRulesCacheTimeToLive,
            )
          )
          _ <- scanList.initialize(
            spliceLedgerClient,
            clock,
          )
        } yield new BftScanConnection(
          spliceLedgerClient,
          config.amuletRulesCacheTimeToLive,
          scanList,
          clock,
          retryProvider,
          loggerFactory,
        )
      case BftScanClientConfig.Bft(seedUrls, scansRefreshInterval, amuletRulesCacheTimeToLive) =>
        for {
          bft <- seedUrls
            .traverse(uri =>
              builder(uri, amuletRulesCacheTimeToLive).transformWith {
                case Success(conn) => Future.successful(Right(conn))
                case Failure(err) => Future.successful(Left(uri -> err))
              }
            )
            .map { cs =>
              val (failed, connections) = cs.toList.partitionEither(identity)
              new Bft(
                connections,
                failed.toMap,
                uri => builder(uri, amuletRulesCacheTimeToLive),
                Bft.getScansInDsoRules,
                scansRefreshInterval,
                retryProvider,
                loggerFactory,
              )
            }
          bftConnection = new BftScanConnection(
            spliceLedgerClient,
            amuletRulesCacheTimeToLive,
            bft,
            clock,
            retryProvider,
            loggerFactory,
          )
          // start with the latest scan list
          _ <- retryProvider.waitUntil(
            RetryFor.WaitingOnInitDependency,
            "refresh_initial_scan_list",
            "Scan list is refreshed.",
            bft
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
        } yield bftConnection
    }
  }

  def peerScanConnection(
      store: ScanStore,
      svName: String,
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
    val builder = buildScanConnection(upgradesConfig, clock, retryProvider, loggerFactory)

    for {
      scans <- retryProvider.retry(
        RetryFor.WaitingOnInitDependency,
        "fetch_scan_list_from_store",
        "Peer scans found in store.",
        Bft
          .getPeerScansFromStore(store, svName)
          .flatMap {
            case Nil =>
              Future.failed(
                Status.UNAVAILABLE
                  .withDescription("No peer scans found in store")
                  .asException()
              )
            case scans => Future.successful(scans)
          },
        loggerFactory.getTracedLogger(classOf[BftScanConnection]),
      )
      bft <- MonadUtil
        .sequentialTraverse(scans)(scan =>
          builder(scan.publicUrl, amuletRulesCacheTimeToLive).transformWith {
            case Success(conn) => Future.successful(Right(conn))
            case Failure(err) => Future.successful(Left(scan.publicUrl -> err))
          }
        )
        .map { cs =>
          val (failed, connections) = cs.toList.partitionEither(identity)
          new Bft(
            connections,
            failed.toMap,
            uri => builder(uri, amuletRulesCacheTimeToLive),
            _ => Bft.getPeerScansFromStore(store, svName),
            scansRefreshInterval,
            retryProvider,
            loggerFactory,
          )
        }
      bftConnection = new BftScanConnection(
        spliceLedgerClient,
        amuletRulesCacheTimeToLive,
        bft,
        clock,
        retryProvider,
        loggerFactory,
      )
    } yield bftConnection
  }

  private def buildScanConnection(
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
        )

  sealed trait BftScanClientConfig {
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

    case class TrustSpecific(
        seedUrls: NonEmptyList[Uri], // by default only one seed_url is provided
        threshold: Option[Int] = None, // default to len(seedUrls)/3+1
        trusted_svs: NonEmptyList[String], // should be at least 1
        amuletRulesCacheTimeToLive: NonNegativeFiniteDuration =
          ScanAppClientConfig.DefaultAmuletRulesCacheTimeToLive,
    ) extends BftScanClientConfig {
      def setAmuletRulesCacheTimeToLive(ttl: NonNegativeFiniteDuration): TrustSpecific =
        copy(amuletRulesCacheTimeToLive = ttl)
    }

    case class Bft(
        seedUrls: NonEmptyList[Uri],
        scansRefreshInterval: NonNegativeFiniteDuration =
          ScanAppClientConfig.DefaultScansRefreshInterval,
        amuletRulesCacheTimeToLive: NonNegativeFiniteDuration =
          ScanAppClientConfig.DefaultAmuletRulesCacheTimeToLive,
    ) extends BftScanClientConfig {
      def setAmuletRulesCacheTimeToLive(ttl: NonNegativeFiniteDuration): Bft =
        copy(amuletRulesCacheTimeToLive = ttl)
    }
  }

  private sealed trait ScanResponse[+T]
  private case class SuccessfulResponse[+T](response: T) extends ScanResponse[T]
  private case class HttpFailureResponse[+T](status: StatusCode, body: Json) extends ScanResponse[T]
  private case class ExceptionFailureResponse[+T](error: Throwable) extends ScanResponse[T]

  class ConsensusNotReached(
      numRequests: Int,
      responses: Seq[(List[Uri], BftScanConnection.ScanResponse[?])],
  ) extends RuntimeException(
        s"Failed to reach consensus from $numRequests Scan nodes. Responses: $responses"
      )
  object ConsensusNotReached {
    def apply[T](
        numRequests: Int,
        responses: ConcurrentHashMap[BftScanConnection.ScanResponse[T], List[Uri]],
        shortenResponses: T => Any,
    ): ConsensusNotReached = {
      val shortResponses: Seq[(List[Uri], BftScanConnection.ScanResponse[?])] =
        responses.asScala.toSeq.map {
          case (SuccessfulResponse(response), uris) =>
            uris -> SuccessfulResponse(shortenResponses(response))
          case (HttpFailureResponse(status, body), uris) =>
            uris -> HttpFailureResponse(status, body)
          case (ExceptionFailureResponse(error), uris) => uris -> ExceptionFailureResponse(error)
        }

      new ConsensusNotReached(numRequests, shortResponses)
    }
  }

  object ConsensusNotReachedRetryable extends ExceptionRetryPolicy {
    override def determineExceptionErrorKind(exception: Throwable, logger: TracedLogger)(implicit
        tc: TraceContext
    ): ErrorKind = {
      exception match {
        case c: ConsensusNotReached =>
          logger.info("Consensus not reached. Will be retried.", c)
          ErrorKind.TransientErrorKind()
        case _ => ErrorKind.FatalErrorKind
      }
    }
  }
}
