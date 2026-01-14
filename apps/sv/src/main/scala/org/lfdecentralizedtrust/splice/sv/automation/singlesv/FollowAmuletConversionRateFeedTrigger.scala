// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  ScheduledTaskTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import ScheduledTaskTrigger.ReadyTask
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.amuletprice.AmuletPriceVote
import org.lfdecentralizedtrust.splice.codegen.java.splice.ans.amuletconversionratefeed.AmuletConversionRateFeed
import org.lfdecentralizedtrust.splice.sv.config.AmuletConversionRateFeedConfig
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.util.Contract

import java.time.Duration
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.jdk.OptionConverters.*

class FollowAmuletConversionRateFeedTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    connection: SpliceLedgerConnection,
    config: AmuletConversionRateFeedConfig,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
    // This is a polling trigger as it plays better with the vote cool down.
) extends ScheduledTaskTrigger[FollowAmuletConversionRateFeedTrigger.Task]() {

  import FollowAmuletConversionRateFeedTrigger.*

  override protected def listReadyTasks(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[Task]] =
    store.lookupAmuletConversionRateFeed(config.publisher).flatMap {
      _ match {
        case None =>
          logger.warn(s"No AmuletConversionRateFeed for publisher ${config.publisher}")
          Future.successful(Seq.empty)
        case Some(feed) =>
          val followedFeedRate = BigDecimal(feed.payload.amuletConversionRate)
          val publishedFeedRate =
            if (
              followedFeedRate < config.acceptedRange.min || followedFeedRate > config.acceptedRange.max
            ) {
              val rate =
                followedFeedRate.max(config.acceptedRange.min).min(config.acceptedRange.max)
              logger.warn(
                s"Rate from publisher ${config.publisher} is ${followedFeedRate} which is outside of the configured accepted range ${config.acceptedRange}, clamping to ${rate}"
              )
              rate
            } else followedFeedRate
          for {
            existingVote <- store
              .lookupAmuletPriceVoteByThisSv()
              .map(
                _.getOrElse(
                  // This can happen after a hard migration or when reingesting from
                  // ledger begin for other reasons so we don't make this INTERNAL.
                  throw Status.NOT_FOUND
                    .withDescription("No price vote for this SV found")
                    .asRuntimeException
                )
              )
            dsoRules <- store.getDsoRules()
            voteCooldown = dsoRules.contract.payload.config.voteCooldownTime.toScala
              .fold(Duration.ofMinutes(1))(t => Duration.ofNanos(t.microseconds * 1000))
          } yield {
            val earliestVoteTimestamp = CantonTimestamp
              .tryFromInstant(existingVote.payload.lastUpdatedAt.plus(voteCooldown))
            if (
              earliestVoteTimestamp < now && existingVote.payload.amuletPrice.toScala
                .map(BigDecimal(_)) != Some(publishedFeedRate)
            ) {
              Seq(
                Task(
                  existingVote,
                  feed,
                  publishedFeedRate,
                )
              )
            } else {
              Seq.empty
            }
          }
      }
    }

  override protected def completeTask(
      task: ReadyTask[Task]
  )(implicit tc: TraceContext): Future[TaskOutcome] =
    for {
      dsoRules <- store.getDsoRules()
      cmd = dsoRules.exercise(
        _.exerciseDsoRules_UpdateAmuletPriceVote(
          store.key.svParty.toProtoPrimitive,
          task.work.existingVote.contractId,
          task.work.publishedFeedRate.bigDecimal,
        )
      )
      _ <- connection
        .submit(Seq(store.key.svParty), Seq(store.key.dsoParty), cmd)
        .noDedup
        .yieldResult()
    } yield TaskSuccess(
      s"Updated amulet conversion rate to ${task.work.publishedFeedRate}"
    )

  override protected def isStaleTask(
      task: ReadyTask[Task]
  )(implicit tc: TraceContext): Future[Boolean] =
    store.multiDomainAcsStore.containsArchived(
      Seq(task.work.existingVote.contractId, task.work.feed.contractId)
    )
}

object FollowAmuletConversionRateFeedTrigger {
  final case class Task(
      existingVote: Contract[AmuletPriceVote.ContractId, AmuletPriceVote],
      // mainly included for logging, the actual value to publish is publishedFeedRate which can be clamped to the boundaries.
      feed: Contract[AmuletConversionRateFeed.ContractId, AmuletConversionRateFeed],
      publishedFeedRate: BigDecimal,
  ) extends PrettyPrinting {

    override protected def pretty: Pretty[this.type] = prettyOfClass(
      param("existingVote", _.existingVote),
      param("feed", _.feed),
      param("publishedFeedRate", _.publishedFeedRate),
    )
  }
}
