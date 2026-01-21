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
import org.lfdecentralizedtrust.splice.config.{NetworkAppClientConfig, Thresholds, UpgradesConfig}
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

    val builder = buildScanConnection(upgradesConfig, clock, retryProvider, loggerFactory)
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
          )

          // Use the temporary connection to get a consensus on the full list of scans
          allScans <- Bft.getScansInDsoRules(tempBftConnection)

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
          )

          _ <- retryProvider.waitUntil(
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
          )
          _ <- retryProvider.waitUntil(
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
        _ => Bft.getPeerScansFromStore(store, svName),
        scansRefreshInterval,
        retryProvider,
        loggerFactory,
      )
      bftConnection = new BftScanConnection(
        spliceLedgerClient,
        amuletRulesCacheTimeToLive,
        scanList,
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
