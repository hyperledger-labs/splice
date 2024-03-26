package com.daml.network.sv.onboarding

import com.daml.network.codegen.java.cn.dso.globaldomain.{
  DomainNodeConfig,
  MediatorConfig,
  SequencerConfig,
}
import com.daml.network.environment.{CNLedgerConnection, RetryFor, RetryProvider}
import com.daml.network.sv.LocalDomainNode
import com.daml.network.sv.onboarding.DomainNodeReconciler.DomainNodeState
import com.daml.network.sv.store.SvDsoStore
import com.daml.network.sv.store.SvDsoStore.DsoRulesWithSvNodeState
import com.daml.network.sv.util.SvUtil
import com.daml.network.sv.util.SvUtil.{LocalMediatorConfig, LocalSequencerConfig}
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
    dsoStore: SvDsoStore,
    connection: CNLedgerConnection,
    clock: Clock,
    retryProvider: RetryProvider,
    logger: TracedLogger,
) {

  private val svParty = dsoStore.key.svParty
  private val dsoParty = dsoStore.key.dsoParty

  private def setConfig(
      domainId: DomainId,
      rulesAndState: DsoRulesWithSvNodeState,
      nodeConfig: DomainNodeConfig,
  )(implicit tc: TraceContext) = {
    logger.info(show"Setting domain node config to $nodeConfig")
    val cmd = rulesAndState.dsoRules.exercise(
      _.exerciseDsoRules_SetDomainNodeConfig(
        svParty.toProtoPrimitive,
        domainId.toProtoPrimitive,
        nodeConfig,
        rulesAndState.svNodeState.contractId,
      )
    )
    connection
      .submit(Seq(svParty), Seq(dsoParty), cmd)
      .noDedup
      .yieldResult()
  }

  def reconcileDomainNodeConfigIfRequired(
      localDomainNode: Option[LocalDomainNode],
      domainId: DomainId,
      state: DomainNodeState,
      migrationId: Long,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Unit] = {
    def setConfigIfRequired() = for {
      localSequencerConfig <- SvUtil.getSequencerConfig(localDomainNode, migrationId)
      localMediatorConfig <- SvUtil.getMediatorConfig(localDomainNode)
      rulesAndState <- dsoStore.getDsoRulesWithSvNodeState(svParty)
      nodeState = rulesAndState.svNodeState.payload
      // TODO(#4901): do not use default, but reconcile all configured domains
      domainNodeConfig = nodeState.state.domainNodes.asScala.get(domainId.toProtoPrimitive)
      sequencerConfig = domainNodeConfig.flatMap(_.sequencer.toScala)
      mediatorConfig = domainNodeConfig.flatMap(_.mediator.toScala)
      existingScanConfig = domainNodeConfig.flatMap(_.scan.toScala).toJava
      existingSequencerConfig = sequencerConfig.map(c =>
        LocalSequencerConfig(c.sequencerId, c.url, c.migrationId)
      )
      existingMediatorConfig = mediatorConfig.map(c => LocalMediatorConfig(c.mediatorId))
      shouldMarkSequencerAsOnboarded = state match {
        case DomainNodeState.Onboarded => sequencerConfig.exists(_.availableAfter.isEmpty)
        case DomainNodeState.Onboarding =>
          false
      }
      _ = ensureSequencerUrlIsDifferentWhenDomainUpgraded(
        existingSequencerConfig,
        localSequencerConfig,
      )
      _ <-
        if (
          existingSequencerConfig != localSequencerConfig ||
          existingMediatorConfig != localMediatorConfig ||
          shouldMarkSequencerAsOnboarded
        ) {
          val nodeConfig = new DomainNodeConfig(
            domainNodeConfig.map(_.cometBft).getOrElse(SvUtil.emptyCometBftConfig),
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
                c.migrationId,
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
            existingScanConfig,
          )
          setConfig(domainId, rulesAndState, nodeConfig)
        } else {
          logger.info(s"Not setting domain node config because it is the same as the existing one.")
          Future.unit
        }
    } yield ()

    retryProvider
      .retry(
        RetryFor.WaitingOnInitDependency,
        "set_domain_config",
        s"setting domain config for $svParty",
        setConfigIfRequired(),
        logger,
      )
  }

  private def ensureSequencerUrlIsDifferentWhenDomainUpgraded(
      existingSequencerConfigOpt: Option[LocalSequencerConfig],
      sequencerConfigOpt: Option[LocalSequencerConfig],
  ): Unit = {
    if (
      existingSequencerConfigOpt.exists { existingSequencerConfig =>
        sequencerConfigOpt.exists(sequencerConfig =>
          existingSequencerConfig.migrationId != sequencerConfig.migrationId
            &&
            existingSequencerConfig.url == sequencerConfig.url
        )
      }
    )
      sys.error("Sequencer URL must be different when domain is upgraded.")
  }
}

object DomainNodeReconciler {

  sealed trait DomainNodeState

  object DomainNodeState {

    case object Onboarded extends DomainNodeState

    case object Onboarding extends DomainNodeState

  }

}
