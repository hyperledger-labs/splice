// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.automation

import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.topology.store.TimeQuery
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.RetryingService
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.{
  BaseLedgerConnection,
  ParticipantAdminConnection,
  RetryProvider,
  ServiceWithGuaranteedShutdown,
}
import org.lfdecentralizedtrust.splice.environment.ledger.api.TopologyTransactionUpdate
import org.lfdecentralizedtrust.splice.scan.admin.api.client.ScanConnection
import org.lfdecentralizedtrust.splice.validator.store.PartyToParticipantStore

import scala.concurrent.{ExecutionContext, Future}

class PartyToParticipantIngestionService(
    store: PartyToParticipantStore,
    scanConnection: ScanConnection,
    connection: BaseLedgerConnection,
    participantAdminConnection: ParticipantAdminConnection,
    config: AutomationConfig,
    backoffClock: Clock,
    override protected val retryProvider: RetryProvider,
    baseLoggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends RetryingService(config, backoffClock, "party to participant ingestion") {

  override protected val loggerFactory: NamedLoggerFactory =
    baseLoggerFactory.append("ingestionLoopFor", "PartyToParticipantStore")

  override protected def instantiateService()(implicit
      traceContext: TraceContext
  ): Future[ServiceWithGuaranteedShutdown[?]] =
    for {
      synchronizerId <- scanConnection.getAmuletRulesDomain()(traceContext)
      lastIngestedOffset <- store.lastIngestedOffset()
      subscribeFrom <- lastIngestedOffset match {
        case Some(offset) =>
          logger.debug(s"Resuming party to participant ingestion from offset $offset")
          Future.successful(offset)
        case None => initializeStore(synchronizerId)
      }
    } yield new ServiceWithGuaranteedShutdown[Vector[TopologyTransactionUpdate]](
      source = connection
        .topologyTransactions(subscribeFrom)
        .filter(_.synchronizerId == synchronizerId)
        .batch(config.ingestion.maxBatchSize.toLong, Vector(_))(_ :+ _),
      map = store.ingest,
      retryProvider = retryProvider,
      loggerFactory = baseLoggerFactory.append("subsClient", this.getClass.getSimpleName),
    )

  private def initializeStore(
      synchronizerId: SynchronizerId
  )(implicit traceContext: TraceContext): Future[Long] =
    for {
      (offset, synchronizerTimes) <- connection.ledgerEndWithSynchronizerTimes(Seq(synchronizerId))
      recordTime = synchronizerTimes.getOrElse(
        synchronizerId,
        throw Status.FAILED_PRECONDITION
          .withDescription(
            s"No record time for synchronizer $synchronizerId at ledger end $offset, the participant has not yet observed any updates on it"
          )
          .asRuntimeException(),
      )
      mappings <- participantAdminConnection.listPartyToParticipant(
        store = Some(TopologyStoreId.Synchronizer(synchronizerId)),
        // Snapshot queries are exclusive so we use immediateSuccessor.
        timeQuery = TimeQuery.Snapshot(recordTime.immediateSuccessor),
      )
      _ = logger.info(
        s"Initializing party to participant store with ${mappings.size} parties at offset $offset and record time $recordTime"
      )
      _ <- store.initialize(
        offset,
        mappings
          .map(result => result.mapping.partyId -> result.mapping.participants.map(_.participantId))
          .toMap,
      )
    } yield offset

  start()
}
