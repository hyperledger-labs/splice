package com.daml.network.sv.onboarding

import com.daml.network.codegen.java.cn.svc.globaldomain.{
  DomainNodeConfig,
  MediatorConfig,
  SequencerConfig,
}
import com.daml.network.environment.{CNLedgerConnection, RetryFor, RetryProvider}
import com.daml.network.sv.LocalDomainNode
import com.daml.network.sv.onboarding.DomainNodeReconciler.DomainNodeState
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.sv.util.SvUtil
import com.daml.network.sv.util.SvUtil.LocalSequencerConfig
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.MapHasAsScala
import scala.jdk.OptionConverters.{RichOption, RichOptional}

class DomainNodeReconciler(
    svcStore: SvSvcStore,
    connection: CNLedgerConnection,
    clock: Clock,
    retryProvider: RetryProvider,
    logger: TracedLogger,
) {

  def reconcileDomainNodeConfigIfRequired(
      localDomainNode: Option[LocalDomainNode],
      domainId: DomainId,
      state: DomainNodeState,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Unit] = {
    val svParty = svcStore.key.svParty
    val svcParty = svcStore.key.svcParty

    def setConfigIfRequired() = for {
      localSequencerConfig <- SvUtil.getSequencerConfig(localDomainNode)
      localMediatorConfig <- SvUtil.getMediatorConfig(localDomainNode)
      svcRules <- svcStore.getSvcRules()
      // TODO(#4901): do not use default, but reconcile all configured domains
      memberInfo = Option(svcRules.payload.members.get(svParty.toProtoPrimitive))
        .getOrElse(throw new IllegalArgumentException(s"SV $svParty is not party of the SVC"))
      domainNodes = memberInfo.domainNodes.asScala
      sequencerConfig = domainNodes
        .get(domainId.toProtoPrimitive)
        .flatMap(_.sequencer.toScala)
      existingConfig = sequencerConfig.map(c => LocalSequencerConfig(c.sequencerId, c.url))
      shouldMarkSequencerAsOnboarded = state match {
        case DomainNodeState.Onboarded => sequencerConfig.exists(_.availableAfter.isEmpty)
        case DomainNodeState.Onboarding =>
          false
      }
      _ <-
        if (existingConfig != localSequencerConfig || shouldMarkSequencerAsOnboarded) {
          val nodeConfig = new DomainNodeConfig(
            domainNodes
              .get(domainId.toProtoPrimitive)
              .map(_.cometBft)
              .getOrElse(SvUtil.emptyCometBftConfig),
            localSequencerConfig.map { c =>
              val sequencerAvailabilityDelay =
                localDomainNode
                  .map(_.sequencerAvailabilityDelay)
                  .getOrElse(
                    sys.error(
                      "localDomainNode is not expected to be empty."
                    )
                  )
              new SequencerConfig(
                c.sequencerId,
                c.url,
                (state match {
                  case DomainNodeState.Onboarded =>
                    Some(clock.now.toInstant.plus(sequencerAvailabilityDelay))
                  case DomainNodeState.Onboarding =>
                    None
                }).toJava,
              )
            }.toJava,
            localMediatorConfig
              .map(c =>
                new MediatorConfig(
                  c.mediatorId
                )
              )
              .toJava,
          )
          logger.info(show"Setting domain node config to $nodeConfig")
          val cmd = svcRules.exercise(
            _.exerciseSvcRules_SetDomainNodeConfig(
              svParty.toProtoPrimitive,
              domainId.toProtoPrimitive,
              nodeConfig,
            )
          )
          connection
            .submit(Seq(svParty), Seq(svcParty), cmd)
            .noDedup
            .yieldResult()
        } else {
          logger.info(s"Not setting domain node config because it is the same as the existing one.")
          Future.unit
        }
    } yield ()

    retryProvider
      .retry(
        RetryFor.WaitingOnInitDependency,
        s"setting domain config for $svParty",
        setConfigIfRequired(),
        logger,
      )
  }

}

object DomainNodeReconciler {

  sealed trait DomainNodeState

  object DomainNodeState {

    case object Onboarded extends DomainNodeState

    case object Onboarding extends DomainNodeState

  }

}
