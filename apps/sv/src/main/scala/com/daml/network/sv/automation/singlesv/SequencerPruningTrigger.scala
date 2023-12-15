package com.daml.network.sv.automation.singlesv

import com.daml.network.automation.{PollingTrigger, TriggerContext}
import com.daml.network.environment.SequencerAdminConnection
import com.daml.network.sv.store.SvSvcStore
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.jdk.DurationConverters.*
import com.digitalasset.canton.time.EnrichedDurations.*

import scala.concurrent.{ExecutionContext, Future}

/** A trigger to periodically call the sequencer pruning command
  */
class SequencerPruningTrigger(
    override protected val context: TriggerContext,
    store: SvSvcStore,
    sequencerAdminConnection: SequencerAdminConnection,
    clock: Clock,
    retentionPeriod: NonNegativeFiniteDuration,
    unauthenticatedMembersRetentionPeriod: NonNegativeFiniteDuration,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingTrigger {
  private val svParty = store.key.svParty
  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] =
    for {
      svcRules <- store.getSvcRules()
      globalDomainId <- store.getCoinRulesDomain()(traceContext)
      svcRulesActiveSequencerConfig = getAvailableSequencerConfigFromSvcRules(
        svParty,
        svcRules,
        clock.now.toInstant,
        globalDomainId,
      )
      _ <- svcRulesActiveSequencerConfig.fold {
        logger.debug(
          s"sequencer for svc member ${store.key.svParty} not found or not yet available, skipping"
        )
        Future.unit
      } { _ =>
        prune().map { prunedResult =>
          logger.debug(s"pruned sequencer: $prunedResult")
        }
      }
    } yield false

  // This method is replicating the prune command defined in `com.digitalasset.canton.console.commands.SequencerAdministrationGroupCommon`
  // which also prunes the unauthenticated members indicating by the `SequencerPruningStatus`
  // https://github.com/DACH-NY/canton/issues/7064
  private def prune()(implicit traceContext: TraceContext) = for {
    status <- sequencerAdminConnection.getSequencerPruningStatus()
    pruningTimestamp = status.now.minus(retentionPeriod.underlying.toJava)
    unauthenticatedMembers = status.unauthenticatedMembersToDisable(
      unauthenticatedMembersRetentionPeriod.toInternal
    )
    _ <- Future.traverse(unauthenticatedMembers)(sequencerAdminConnection.disableMember)
    _ = if (unauthenticatedMembers.nonEmpty)
      logger.warn(
        s"Automatically disabled ${unauthenticatedMembers.size} unauthenticated member clients."
      )
    res <- sequencerAdminConnection.prune(pruningTimestamp)
  } yield res
}
