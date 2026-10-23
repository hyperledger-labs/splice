// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.votesbackfilling

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.pattern.after
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.lfdecentralizedtrust.splice.{PekkoRetryableService, PekkoRetryingService}
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.scan.store.db.DbVotesStore.VotesBackfillCursor

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/** Backfills `votes_store_results` with the vote results from before the first transaction that
  * the votes store ingested (see `DbVotesStore.getFirstIngestedRecordTime`).
  *
  * - A Scan that already has a txlog is migrated by the Flyway migration `V079__votes_store_from_txlog.sql`,
  *   which copies the vote results of the txlog (and sets the backfilling cursor to where the txlog starts),
  *   and marks backfilling as complete if the txlog is.
  * - The Scan of the founding SV ingests everything from the start of the network, so there is nothing
  *   to backfill: it is complete right away.
  * - All other Scans read from peer Scans, until the peers say that there is nothing older.
  *
  * One Pekko stream, walking backwards in time one page at a time; modeled on
  * [[org.lfdecentralizedtrust.splice.scan.store.bulk.backfilling.BulkStorageBackfilling]].
  */
class VotesStoreBackfilling(
    pollingInterval: NonNegativeFiniteDuration,
    pageSize: Int,
    isFirstSv: Boolean,
    peers: PeerVotesSource,
    votesStore: VotesBackfillingStore,
    override val loggerFactory: NamedLoggerFactory,
)(implicit actorSystem: ActorSystem, ec: ExecutionContext)
    extends NamedLogging
    with Spanning
    with PekkoRetryableService[VotesStoreBackfilling.Step] {

  import VotesStoreBackfilling.*

  private[votesbackfilling] def serviceSource()(implicit tc: TraceContext): Source[Step, NotUsed] =
    mksrc().concat(Source.never)

  private[votesbackfilling] def mksrc()(implicit tc: TraceContext): Source[Step, NotUsed] =
    Source.future(votesStore.isBackfillingComplete()).flatMapConcat { complete =>
      if (complete) {
        logger.info("Vote store backfilling is already complete, nothing to do")
        Source.empty
      } else if (isFirstSv) {
        logger.info(
          "This is the Scan of the founding SV, which ingested all votes itself: nothing to backfill"
        )
        Source.future(votesStore.markBackfillingComplete().map(_ => Completed))
      } else {
        logger.info("Starting vote store backfilling")
        walk[State](NotStarted)(step)
      }
    }

  private def step(state: State)(implicit tc: TraceContext): Future[Emitted[State]] =
    state match {
      case NotStarted => initialize()
      case Backfilling(cursor) => peerStep(cursor)
    }

  /** Resumes from the persisted cursor, or starts at the first transaction the votes store ingested.
    *
    * Backfilling covers everything strictly before that transaction. Record times are only ordered
    * within a synchronizer, so this relies on votes only existing on the main synchronizer: votes
    * are signed by the SVs, which only exist there.
    */
  private def initialize()(implicit tc: TraceContext): Future[Emitted[State]] =
    votesStore.getBackfillingCursor().flatMap {
      case Some(cursor) =>
        logger.info(s"Resuming vote store backfilling from $cursor")
        continueWith(Backfilling(cursor), Resumed(cursor))
      case None =>
        votesStore.getFirstIngestedRecordTime().flatMap {
          case Some(firstIngested) =>
            val cursor = VotesBackfillCursor(firstIngested, 0)
            logger.info(s"Starting vote store backfilling from $cursor")
            continueWith(Backfilling(cursor), Started(cursor))
          case None =>
            logger.debug(
              "The vote store has not ingested any transaction yet, waiting before starting backfilling"
            )
            waitThen(NotStarted, WaitingForLiveIngestion)
        }
    }

  private def peerStep(
      cursor: VotesBackfillCursor
  )(implicit tc: TraceContext): Future[Emitted[State]] =
    peers
      .votesBefore(cursor, pageSize)
      .transformWith {
        case Success(votes) if votes.isEmpty =>
          logger.info(
            "Peers report no votes before the current cursor: genesis is reached, vote store backfilling is complete"
          )
          votesStore.markBackfillingComplete().flatMap(_ => lastStep(Completed))
        case Success(votes) =>
          val newCursor = votes
            .minByOption(row => (row.recordTime, row.eventNumber))
            .map(row => VotesBackfillCursor(row.recordTime, row.eventNumber))
            .getOrElse(cursor)
          votesStore
            .insertPeerVoteResults(votes, newCursor)
            .map { _ =>
              logger.info(
                s"Backfilled ${votes.size} vote results from peers, down to $newCursor"
              )
              Emitted(PeerPageBackfilled(votes.size, newCursor), Some(Backfilling(newCursor)))
            }
        case Failure(ex) =>
          logger.info(
            s"Could not backfill votes from peers yet, retrying in ${pollingInterval}: ${ex.getMessage}"
          )
          waitThen(Backfilling(cursor), WaitingForPeers(cursor))
      }

  private def walk[S](initial: S)(stepFrom: S => Future[Emitted[S]]): Source[Step, NotUsed] =
    Source.unfoldAsync[Option[S], Step](Some(initial)) {
      case Some(state) => stepFrom(state).map(emitted => Some((emitted.nextState, emitted.step)))
      case None => Future.successful(None)
    }

  private def continueWith[S](state: S, step: Step): Future[Emitted[S]] =
    Future.successful(Emitted(step, Some(state)))

  private def lastStep[S](step: Step): Future[Emitted[S]] =
    Future.successful(Emitted(step, None))

  private def waitThen[S](state: S, step: Step): Future[Emitted[S]] =
    after(pollingInterval.underlying, actorSystem.scheduler)(continueWith(state, step))

  override def asPekkoRetryingService(
      automationConfig: AutomationConfig,
      backoffClock: Clock,
      retryProvider: RetryProvider,
  )(implicit tracer: Tracer): PekkoRetryingService[Step] =
    withNewTrace(description) { implicit traceContext => _ =>
      new PekkoRetryingService(
        serviceSource(),
        Sink.ignore,
        automationConfig,
        backoffClock,
        description,
        retryProvider,
        loggerFactory,
      )
    }
}

object VotesStoreBackfilling {
  val description = "VotesStoreBackfilling"

  private sealed trait State extends Product with Serializable
  private case object NotStarted extends State
  private final case class Backfilling(cursor: VotesBackfillCursor) extends State

  private final case class Emitted[+S](step: Step, nextState: Option[S])

  sealed trait Step extends Product with Serializable
  case object WaitingForLiveIngestion extends Step
  final case class Started(cursor: VotesBackfillCursor) extends Step
  final case class Resumed(cursor: VotesBackfillCursor) extends Step
  final case class PeerPageBackfilled(count: Int, cursor: VotesBackfillCursor) extends Step
  final case class WaitingForPeers(cursor: VotesBackfillCursor) extends Step
  case object Completed extends Step
}
