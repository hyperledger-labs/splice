// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import org.apache.pekko.stream.Materializer
import com.daml.ledger.api.v2.participant_offset.ParticipantOffset
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.{
  SpliceLedgerConnection,
  SpliceLedgerSubscription,
  RetryProvider,
}
import org.lfdecentralizedtrust.splice.environment.ledger.api.LedgerClient.GetTreeUpdatesResponse
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

/** Ingestion for ACS and transfer stores.
  * We ingest them independently but we ensure that the acs store
  * is never caught up further than the transfer store to avoid losing
  * track of contracts on a unassign.
  */
class UpdateIngestionService(
    ingestionTargetName: String,
    ingestionSink: MultiDomainAcsStore.IngestionSink,
    connection: SpliceLedgerConnection,
    config: AutomationConfig,
    backoffClock: Clock,
    override protected val retryProvider: RetryProvider,
    baseLoggerFactory: NamedLoggerFactory,
    ingestFromParticipantBegin: Boolean,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends LedgerIngestionService(config, backoffClock) {

  private val filter = ingestionSink.ingestionFilter

  override protected val loggerFactory: NamedLoggerFactory =
    baseLoggerFactory.append("ingestionLoopFor", ingestionTargetName)

  override protected def newLedgerSubscription()(implicit
      traceContext: TraceContext
  ): Future[SpliceLedgerSubscription[?]] =
    for {
      lastIngestedOffset <- ingestionSink.initialize()
      subscribeFrom <- lastIngestedOffset match {
        case None =>
          for {
            offset <-
              if (ingestFromParticipantBegin) {
                logger.debug(s"Starting ingestion from participant begin")
                val participantBegin = ParticipantOffset(
                  ParticipantOffset.Value.Boundary(
                    ParticipantOffset.ParticipantBoundary.PARTICIPANT_BOUNDARY_BEGIN
                  )
                )
                ingestionSink
                  .ingestAcs(
                    MultiDomainAcsStore.fromParticipantOffset(participantBegin),
                    Seq.empty,
                    Seq.empty,
                    Seq.empty,
                  )
                  .map(_ => participantBegin)
              } else
                for {
                  acsOffset <- connection.ledgerEnd()
                  _ = logger.debug(s"Starting ingestion from ledger end: $acsOffset")
                  _ <- ingestAcsAndInFlight(acsOffset.value)
                } yield ParticipantOffset(acsOffset)
          } yield offset
        case Some(offset) =>
          logger.debug(s"Resuming ingestion from offset: $offset")
          Future.successful(MultiDomainAcsStore.toParticipantOffset(offset))
      }
    } yield new SpliceLedgerSubscription(
      source = connection.updates(subscribeFrom, filter),
      map = process,
      retryProvider = retryProvider,
      loggerFactory = baseLoggerFactory.append("subsClient", this.getClass.getSimpleName),
    )

  private def process(
      msg: GetTreeUpdatesResponse
  )(implicit traceContext: TraceContext) = ingestionSink.ingestUpdate(msg.domainId, msg.update)

  private def ingestAcsAndInFlight(
      offset: String
  )(implicit traceContext: TraceContext): Future[Unit] = {
    val javaOffset = ParticipantOffset.Value.Absolute(offset)
    for {
      // TODO(#5534): stream contracts instead of ingesting them as a single Seq
      (acs, incompleteOut, incompleteIn) <- connection.activeContracts(filter, javaOffset)
      _ <- ingestionSink.ingestAcs(
        offset,
        acs,
        incompleteOut,
        incompleteIn,
      )
    } yield ()
  }

  // Kick-off the ingestion
  startIngestion()
}
