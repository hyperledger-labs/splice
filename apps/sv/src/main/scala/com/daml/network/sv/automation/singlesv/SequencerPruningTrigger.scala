package com.daml.network.sv.automation.singlesv

import com.daml.network.automation.{PollingTrigger, TriggerContext}
import com.daml.network.environment.{
  MediatorAdminConnection,
  ParticipantAdminConnection,
  SequencerAdminConnection,
}
import com.daml.network.sv.store.SvSvcStore
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.jdk.DurationConverters.*
import io.grpc.Status

import scala.concurrent.{ExecutionContext, Future}

/** A trigger to periodically call the sequencer pruning command
  */
class SequencerPruningTrigger(
    override protected val context: TriggerContext,
    store: SvSvcStore,
    sequencerAdminConnection: SequencerAdminConnection,
    mediatorAdminConnection: MediatorAdminConnection,
    clock: Clock,
    retentionPeriod: NonNegativeFiniteDuration,
    participantAdminConnection: ParticipantAdminConnection,
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

  // This method is replicating the force_prune command defined in `com.digitalasset.canton.console.commands.SequencerAdministrationGroupCommon`
  // Which will prunes the member preventing pruning
  private def prune()(implicit traceContext: TraceContext) = for {
    status <- sequencerAdminConnection.getSequencerPruningStatus()
    pruningTimestamp = status.now.minus(retentionPeriod.underlying.toJava)
    membersToDisable = status.clientsPreventingPruning(pruningTimestamp).members
    _ <-
      // disabling member preventing pruning
      if (membersToDisable.nonEmpty) {
        val disabled = membersToDisable.map(_.toProtoPrimitive)
        disablingParticipantAndMediator(disabled).flatMap { isParticipantOrMediator =>
          if (isParticipantOrMediator) {
            logger.warn(
              show"disabling ${disabled.size} member clients. $disabled"
            )
            Future.traverse(membersToDisable)(sequencerAdminConnection.disableMember)
          } else {
            throw Status.INTERNAL
              .withDescription(
                "Failed to prune sequencer because members to be disabled contains our own participant or mediator"
              )
              .asRuntimeException()
          }
        }
      } else Future.unit
    statusAfterDisabling <- sequencerAdminConnection.getSequencerPruningStatus()
    safeTimestamp = statusAfterDisabling.safePruningTimestamp
    res <-
      if (safeTimestamp < pruningTimestamp) {
        val message = (
          s"We disabled all clients preventing pruning at $pruningTimestamp however the safe timestamp is set to $safeTimestamp"
        )
        Future.failed(Status.INTERNAL.withDescription(message).asRuntimeException())
      } else
        sequencerAdminConnection
          .prune(pruningTimestamp)
          .transform(
            identity,
            err => {
              val lastAcknowledged =
                statusAfterDisabling.members
                  .map(m => m.member.toProtoPrimitive -> m.safePruningTimestamp)
              logger.warn(s"failed to prune with sequencer pruning status: $lastAcknowledged")
              err
            },
          )

  } yield res

  private def disablingParticipantAndMediator(
      disableMembers: Set[String]
  )(implicit traceContext: TraceContext): Future[Boolean] = for {
    participantId <- participantAdminConnection.getParticipantId()
    mediatorId <- mediatorAdminConnection.getMediatorId
  } yield disableMembers.exists(member =>
    Set(participantId.toProtoPrimitive, mediatorId.toProtoPrimitive).contains(member)
  )
}
