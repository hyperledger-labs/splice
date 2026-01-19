// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import cats.data.NonEmptyList
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.{
  RetryProvider,
  SpliceLedgerConnection,
  SpliceLedgerSubscription,
}
import org.lfdecentralizedtrust.splice.environment.ledger.api.LedgerClient.GetTreeUpdatesResponse
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.IngestionSink.IngestionStart

import scala.concurrent.{ExecutionContext, Future, Promise, blocking}

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
      ingestionStart <- ingestionSink.initialize()
      subscribeFrom <- ingestionStart match {
        case IngestionStart.ResumeAtOffset(offset) =>
          logger.debug(s"Resuming ingestion from offset $offset")
          Future.successful(offset)
        case IngestionStart.InitializeAcsAtOffset(offset) =>
          logger.debug(s"Initializing ACS and starting ingestion from offset $offset")
          for {
            _ <- ingestAcsAndInFlight(offset)
          } yield offset
        case IngestionStart.InitializeAcsAtLatestOffset =>
          for {
            offset <-
              if (ingestFromParticipantBegin) {
                for {
                  participantBegin <- connection.latestPrunedOffset()
                  _ = logger.debug(
                    s"Starting ingestion from participant begin at $participantBegin"
                  )
                  _ <- ingestionSink
                    .ingestAcs(
                      participantBegin,
                      Seq.empty,
                      Seq.empty,
                      Seq.empty,
                    )
                } yield participantBegin
              } else
                for {
                  acsOffset <- connection.ledgerEnd()
                  _ = logger.debug(s"Starting ingestion from ledger end at $acsOffset")
                  _ <- ingestAcsAndInFlight(acsOffset)
                } yield acsOffset
          } yield offset
      }
    } yield new SpliceLedgerSubscription(
      source = connection
        .updates(subscribeFrom, filter)
        .batch(config.ingestion.maxBatchSize.toLong, Vector(_))(_ :+ _),
      map = process,
      retryProvider = retryProvider,
      loggerFactory = baseLoggerFactory.append("subsClient", this.getClass.getSimpleName),
    )

  private def process(
      msgs: Seq[GetTreeUpdatesResponse]
  )(implicit traceContext: TraceContext): Future[Unit] = {
    // if paused, this step will backpressure the source
    waitForResumePromise.future.flatMap { _ =>
      NonEmptyList.fromFoldable(msgs) match {
        case Some(batch) =>
          logger.debug(s"Processing batch of ${batch.size} elements")
          ingestionSink.ingestUpdateBatch(batch.map(_.updateOrCheckpoint))
        case None =>
          logger.error(
            "Received empty batch of updates to ingest. This is never supposed to happen."
          )
          Future.unit
      }
    }
  }

  private def ingestAcsAndInFlight(
      offset: Long
  )(implicit traceContext: TraceContext): Future[Unit] = {
    for {
      // TODO(#863): stream contracts instead of ingesting them as a single Seq
      (acs, incompleteOut, incompleteIn) <- connection.activeContracts(filter, offset)
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

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  @volatile
  private var waitForResumePromise = Promise.successful(())

  /** Note that any in-flight events being processed when `pause` is called will still be processed.
    * For test purposes.
    */
  def pause(): Future[Unit] = blocking {
    withNewTrace(this.getClass.getSimpleName) { implicit traceContext => _ =>
      logger.info("Pausing UpdateIngestionService.")
      blocking {
        synchronized {
          if (waitForResumePromise.isCompleted) {
            waitForResumePromise = Promise()
          }
          Future.successful(())
        }
      }
    }
  }

  def resume(): Unit = blocking {
    withNewTrace(this.getClass.getSimpleName) { implicit traceContext => _ =>
      logger.info("Resuming UpdateIngestionService.")
      blocking {
        synchronized {
          val _ = waitForResumePromise.trySuccess(())
        }
      }
    }
  }
}
