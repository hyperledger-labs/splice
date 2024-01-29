package com.daml.network.sv.automation.singlesv

import com.daml.network.automation.{PollingTrigger, TriggerContext}
import com.daml.network.environment.{
  MediatorAdminConnection,
  ParticipantAdminConnection,
  SequencerAdminConnection,
}
import com.daml.network.sv.store.SvSvcStore
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.domain.sequencing.sequencer.SequencerPruningStatus
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.topology.Member
import com.digitalasset.canton.util.ShowUtil.*
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
    membersToDisable = clientsPreventingPruning(status, pruningTimestamp)
    _ <-
      // disabling member preventing pruning
      if (membersToDisable.nonEmpty) {
        filterToOurMembers(membersToDisable).flatMap { ourLaggingMembers =>
          if (ourLaggingMembers.isEmpty) {
            logger.warn(
              show"disabling ${membersToDisable.size} member clients preventing pruning to $pruningTimestamp: $membersToDisable"
            )
            Future.traverse(membersToDisable)(m => sequencerAdminConnection.disableMember(m.member))
          } else {
            throw Status.INTERNAL
              .withDescription(
                show"Failed to prune sequencer to $pruningTimestamp because our own participant or mediator have not acknowledged that timestamp: ${ourLaggingMembers}"
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

  private def filterToOurMembers(
      laggingMembers: Seq[SequencerPruningTrigger.LaggingMember]
  )(implicit traceContext: TraceContext): Future[Seq[SequencerPruningTrigger.LaggingMember]] = for {
    participantId <- participantAdminConnection.getParticipantId()
    mediatorId <- mediatorAdminConnection.getMediatorId
  } yield laggingMembers.filter(m =>
    Seq[Member](participantId.member, mediatorId.member).contains(m.member)
  )

  private def clientsPreventingPruning(
      status: SequencerPruningStatus,
      timestamp: CantonTimestamp,
  ): Seq[SequencerPruningTrigger.LaggingMember] = {
    val memberToSafePruningTimestamp: Map[Member, CantonTimestamp] =
      status.members.view.map(m => m.member -> m.safePruningTimestamp).toMap
    status
      .clientsPreventingPruning(timestamp)
      .members
      .toList
      .map(m => SequencerPruningTrigger.LaggingMember(m, memberToSafePruningTimestamp(m)))
  }
}

private object SequencerPruningTrigger {
  final case class LaggingMember(
      member: Member,
      safePruningTimestamp: CantonTimestamp,
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("member", _.member),
        param("safePruningTimestamp", _.safePruningTimestamp),
      )
  }
}
