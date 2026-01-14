// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.api.client

import cats.data.OptionT
import cats.syntax.either.*
import com.daml.metrics.api.MetricsContext
import com.daml.metrics.api.MetricsContext.Implicits.empty
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
import org.lfdecentralizedtrust.splice.config.UpgradesConfig
import org.lfdecentralizedtrust.splice.environment.{
  HttpAppConnection,
  RetryProvider,
  SpliceLedgerClient,
}
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.http.v0.definitions.{
  LookupTransferCommandStatusResponse,
  MigrationSchedule,
}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient
import org.lfdecentralizedtrust.splice.scan.config.ScanAppClientConfig
import org.lfdecentralizedtrust.splice.scan.store.db.ScanAggregator
import org.lfdecentralizedtrust.splice.store.HistoryBackfilling.SourceMigrationInfo
import org.lfdecentralizedtrust.splice.store.UpdateHistory.UpdateHistoryResponse
import org.lfdecentralizedtrust.splice.util.{
  ChoiceContextWithDisclosures,
  Codec,
  Contract,
  ContractWithState,
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
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.google.protobuf.ByteString
import org.apache.pekko.stream.Materializer

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import com.digitalasset.canton.data.CantonTimestamp
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  DsoRules_CloseVoteRequestResult,
  VoteRequest,
}
import io.grpc.Status
import org.apache.pekko.http.scaladsl.model.{HttpHeader, Uri}
import org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpCommand
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.transferinstructionv1
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.transferinstructionv1.TransferInstruction
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.allocationv1.Allocation
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.allocationinstructionv1
import org.lfdecentralizedtrust.splice.metrics.ScanConnectionMetrics
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.BftSequencer
import org.lfdecentralizedtrust.tokenstandard.transferinstruction.v1.definitions.TransferFactoryWithChoiceContext

import scala.util.{Failure, Success}

/** Connection to the admin API of CC Scan. This is used by other apps
  * to query for the DSO party id.
  */
class SingleScanConnection private[client] (
    private[client] val config: ScanAppClientConfig,
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
  import ScanRoundAggregatesDecoder.*

  override def runHttpCmd[Res, Result](
      url: Uri,
      command: HttpCommand[Res, Result],
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
          ("scan_connection", url.scheme),
          ("request", command.fullName),
        ) { m =>
          val timer = metrics.latencyPerConnection.startAsync()(m)
          super
            .runHttpCmd(url, command, headers)
            .andThen {
              case Failure(_) =>
                metrics.failuresPerConnection.mark()(m)
                timer.stop()(m)
              case Success(_) =>
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
  ): Future[org.lfdecentralizedtrust.splice.http.v0.definitions.GetDsoInfoResponse] = {
    runHttpCmd(config.adminApi.url, HttpScanAppClient.GetDsoInfo(List()))
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
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetDsoInfo(headers = List()),
    ).map { dsoInfo =>
      Contract
        .fromHttp(DsoRules.COMPANION)(dsoInfo.dsoRules.contract)
        .valueOr(err =>
          throw Status.INVALID_ARGUMENT
            .withDescription(s"Failed to decode dso rules: $err")
            .asRuntimeException
        )
    }
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

  override def listDsoSequencers()(implicit
      tc: TraceContext
  ): Future[Seq[HttpScanAppClient.DomainSequencers]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.ListDsoSequencers(),
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
  def listRoundTotals(
      start: Long,
      end: Long,
  )(implicit
      tc: TraceContext
  ): Future[Seq[org.lfdecentralizedtrust.splice.http.v0.definitions.RoundTotals]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.ListRoundTotals(start, end),
    )
  }
  def listRoundPartyTotals(
      start: Long,
      end: Long,
  )(implicit
      tc: TraceContext
  ): Future[Seq[org.lfdecentralizedtrust.splice.http.v0.definitions.RoundPartyTotals]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.ListRoundPartyTotals(start, end),
    )
  }
  def getAggregatedRounds()(implicit
      tc: TraceContext
  ): Future[Option[ScanAggregator.RoundRange]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetAggregatedRounds,
    )
  }

  def getRoundAggregate(round: Long)(implicit
      tc: TraceContext
  ): Future[Option[ScanAggregator.RoundAggregate]] = {
    for {
      roundTotals <- listRoundTotals(round, round).flatMap { roundTotals =>
        roundTotals.headOption
          .map { rt =>
            decodeRoundTotal(rt).fold(
              err =>
                Future.failed(ScanAggregator.CannotAdvance(s"Failed to decode round totals: $err")),
              rt => Future.successful(Some(rt)),
            )
          }
          .getOrElse(Future.successful(None))
      }
      roundPartyTotals <- listRoundPartyTotals(round, round).flatMap { roundPartyTotals =>
        val (errors, totals) = roundPartyTotals.partitionMap { rt =>
          decodeRoundPartyTotals(rt)
        }
        if (errors.nonEmpty) {
          Future.failed(
            ScanAggregator.CannotAdvance(
              s"""Failed to decode round party totals: ${errors.mkString(", ")}"""
            )
          )
        } else {
          Future.successful(totals.toVector)
        }
      }
    } yield {
      roundTotals.map { rt =>
        ScanAggregator.RoundAggregate(roundTotals = rt, roundPartyTotals = roundPartyTotals)
      }
    }
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
  ): Future[Seq[DsoRules_CloseVoteRequestResult]] = runHttpCmd(
    config.adminApi.url,
    HttpScanAppClient.ListVoteRequestResults(
      actionName,
      accepted,
      requester,
      effectiveFrom,
      effectiveTo,
      limit,
    ),
  )

  def getTransferInstructionAcceptContext(
      instructionCid: TransferInstruction.ContractId
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ChoiceContextWithDisclosures] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetTransferInstructionAcceptContext(instructionCid),
    )

  def getTransferInstructionRejectContext(
      instructionCid: TransferInstruction.ContractId
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ChoiceContextWithDisclosures] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetTransferInstructionRejectContext(instructionCid),
    )

  def getTransferInstructionWithdrawContext(
      instructionCid: TransferInstruction.ContractId
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ChoiceContextWithDisclosures] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetTransferInstructionWithdrawContext(instructionCid),
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

  def getTransferFactory(choiceArgs: transferinstructionv1.TransferFactory_Transfer)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[
    (
        FactoryChoiceWithDisclosures[
          transferinstructionv1.TransferFactory.ContractId,
          transferinstructionv1.TransferFactory_Transfer,
        ],
        TransferFactoryWithChoiceContext.TransferKind,
    )
  ] =
    runHttpCmd(config.adminApi.url, HttpScanAppClient.GetTransferFactory(choiceArgs))

  def getTransferFactoryRaw(arg: transferinstruction.v1.definitions.GetFactoryRequest)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[transferinstruction.v1.definitions.TransferFactoryWithChoiceContext] =
    runHttpCmd(config.adminApi.url, HttpScanAppClient.GetTransferFactoryRaw(arg))

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
      allocationCid: Allocation.ContractId
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
      allocationCid: Allocation.ContractId
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ChoiceContextWithDisclosures] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetAllocationCancelContext(allocationCid),
    )

  def getAllocationWithdrawContext(
      allocationCid: Allocation.ContractId
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ChoiceContextWithDisclosures] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetAllocationWithdrawContext(allocationCid),
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

  def getAllocationFactoryRaw(arg: allocationinstruction.v1.definitions.GetFactoryRequest)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[allocationinstruction.v1.definitions.FactoryWithChoiceContext] =
    runHttpCmd(config.adminApi.url, HttpScanAppClient.GetAllocationFactoryRaw(arg))
}

object SingleScanConnection {
  def withSingleScanConnection[T](
      scanConfig: ScanAppClientConfig,
      upgradesConfig: UpgradesConfig,
      clock: Clock,
      retryProvider: RetryProvider,
      loggerFactory: NamedLoggerFactory,
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

object ScanRoundAggregatesDecoder {
  def decodeRoundTotal(
      rt: org.lfdecentralizedtrust.splice.http.v0.definitions.RoundTotals
  ): Either[String, ScanAggregator.RoundTotals] = {
    (for {
      closedRoundEffectiveAt <- CantonTimestamp.fromInstant(rt.closedRoundEffectiveAt.toInstant)
      appRewards <- Codec.decode(Codec.BigDecimal)(rt.appRewards)
      validatorRewards <- Codec.decode(Codec.BigDecimal)(rt.validatorRewards)
      cumulativeAppRewards <- Codec.decode(Codec.BigDecimal)(rt.cumulativeAppRewards)
      cumulativeValidatorRewards <- Codec
        .decode(Codec.BigDecimal)(rt.cumulativeValidatorRewards)
    } yield {
      // changeToInitialAmountAsOfRoundZero, changeToHoldingFeesRate, cumulativeChangeToInitialAmountAsOfRoundZero,
      // cumulativeChangeToHoldingFeesRate and totalAmuletBalance are intentionally left out
      // since these do not match up anymore because amulet expires are attributed to the closed round at a later stage
      // in scan_txlog_store, at a time that can easily differ between SVs.
      ScanAggregator.RoundTotals(
        closedRound = rt.closedRound,
        closedRoundEffectiveAt = closedRoundEffectiveAt,
        appRewards = appRewards,
        validatorRewards = validatorRewards,
        cumulativeAppRewards = cumulativeAppRewards,
        cumulativeValidatorRewards = cumulativeValidatorRewards,
      )
    })
  }

  def decodeRoundPartyTotals(
      rt: org.lfdecentralizedtrust.splice.http.v0.definitions.RoundPartyTotals
  ): Either[String, ScanAggregator.RoundPartyTotals] = {
    (for {
      appRewards <- Codec.decode(Codec.BigDecimal)(rt.appRewards)
      validatorRewards <- Codec.decode(Codec.BigDecimal)(rt.validatorRewards)
      trafficPurchasedCcSpent <- Codec.decode(Codec.BigDecimal)(rt.trafficPurchasedCcSpent)
      cumulativeAppRewards <- Codec.decode(Codec.BigDecimal)(rt.cumulativeAppRewards)
      cumulativeValidatorRewards <- Codec.decode(Codec.BigDecimal)(rt.cumulativeValidatorRewards)
      cumulativeTrafficPurchasedCcSpent <- Codec
        .decode(Codec.BigDecimal)(rt.cumulativeTrafficPurchasedCcSpent)
    } yield {
      // cumulativeChangeToInitialAmountAsOfRoundZero and cumulativeChangeToHoldingFeesRate are intentionally left out
      // since these do not match up anymore because amulet expires are attributed to the closed round at a later stage
      // in scan_txlog_store, at a time that can easily differ between SVs.
      ScanAggregator.RoundPartyTotals(
        closedRound = rt.closedRound,
        party = rt.party,
        appRewards = appRewards,
        validatorRewards = validatorRewards,
        trafficPurchased = rt.trafficPurchased,
        trafficPurchasedCcSpent = trafficPurchasedCcSpent,
        trafficNumPurchases = rt.trafficNumPurchases,
        cumulativeAppRewards = cumulativeAppRewards,
        cumulativeValidatorRewards = cumulativeValidatorRewards,
        cumulativeTrafficPurchased = rt.cumulativeTrafficPurchased,
        cumulativeTrafficPurchasedCcSpent = cumulativeTrafficPurchasedCcSpent,
        cumulativeTrafficNumPurchases = rt.cumulativeTrafficNumPurchases,
      )
    })
  }
}
