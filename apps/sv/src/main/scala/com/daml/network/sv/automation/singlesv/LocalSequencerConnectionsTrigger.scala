// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.automation.singlesv

import com.daml.network.automation.{PollingTrigger, TriggerContext, TriggerEnabledSynchronization}
import com.daml.network.codegen.java.splice.dso.decentralizedsynchronizer.SequencerConfig
import com.daml.network.environment.ParticipantAdminConnection
import com.daml.network.sv.LocalSynchronizerNode
import com.daml.network.sv.store.SvDsoStore
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.config.ClientConfig
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.{DomainAlias, SequencerAlias}
import com.digitalasset.canton.participant.domain.DomainConnectionConfig
import com.digitalasset.canton.sequencing.{
  GrpcSequencerConnection,
  SequencerConnections,
  SubmissionRequestAmplification,
}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import com.digitalasset.canton.util.ShowUtil.*

import java.time.Instant
import scala.jdk.OptionConverters.*
import scala.concurrent.{ExecutionContext, Future}

class LocalSequencerConnectionsTrigger(
    baseContext: TriggerContext,
    participantAdminConnection: ParticipantAdminConnection,
    decentralizedSynchronizerAlias: DomainAlias,
    store: SvDsoStore,
    sequencerInternalConfig: ClientConfig,
    migrationId: Long,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingTrigger {
  // Disabling domain time and domain paused sync since we might need to fix domain connections to allow for catchup.
  override protected lazy val context =
    baseContext.copy(triggerEnabledSync = TriggerEnabledSynchronization.Noop)

  private val svParty = store.key.svParty
  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] = {
    for {
      rulesAndState <- store.getDsoRulesWithSvNodeState(svParty)
      // TODO(#4906): double-check that the right domain-ids are used in the right place to make this work with soft-domain migration
      domainId <- participantAdminConnection.getDomainId(decentralizedSynchronizerAlias)
      domainTimeLb <- participantAdminConnection.getDomainTimeLowerBound(
        domainId,
        maxDomainTimeLag = context.config.pollingInterval,
      )
      decentralizedSynchronizerId <- store.getAmuletRulesDomain()(traceContext)
      dsoRulesActiveSequencerConfig = rulesAndState.lookupSequencerConfigFor(
        decentralizedSynchronizerId,
        domainTimeLb.timestamp.toInstant,
        migrationId,
      )
      _ <- dsoRulesActiveSequencerConfig.fold {
        logger.debug(
          show"Sv info or sequencer info not (yet) published to DsoRules for our own party ${store.key.svParty}, skipping"
        )
        Future.unit
      } { publishedSequencerInfo =>
        participantAdminConnection.modifyDomainConnectionConfigAndReconnect(
          decentralizedSynchronizerAlias,
          setLocalSequencerConnection(
            publishedSequencerInfo,
            sequencerInternalConfig,
            domainTimeLb.timestamp.toInstant,
          ),
        )
      }
    } yield false
  }

  private def setLocalSequencerConnection(
      publishedSequencerInfo: SequencerConfig,
      internalSequencerClientConfig: ClientConfig,
      domainTime: Instant,
  )(implicit traceContext: TraceContext): DomainConnectionConfig => Option[DomainConnectionConfig] =
    conf =>
      conf.sequencerConnections.default match {
        case _: GrpcSequencerConnection
            if publishedSequencerInfo.availableAfter.toScala
              .exists(availableAfter => domainTime.isAfter(availableAfter)) =>
          // connect to the sequencer with internal client config here instead of the public url to avoid
          // - installing loopback in each SV namespace to work around traffic being blocked by cluster whitelisting
          // - network traffic going all the way through cluster load balancer / ingress routing while the sequencer is in the same namespace
          val localEndpoint = LocalSynchronizerNode.toEndpoint(internalSequencerClientConfig)
          val localSequencerConnection =
            new GrpcSequencerConnection(
              NonEmpty.mk(Seq, localEndpoint),
              transportSecurity = internalSequencerClientConfig.tls.isDefined,
              customTrustCertificates = None,
              SequencerAlias.Default,
            )
          val newConnections = SequencerConnections.tryMany(
            Seq(localSequencerConnection),
            PositiveInt.tryCreate(1),
            submissionRequestAmplification = SubmissionRequestAmplification.NoAmplification,
          )
          if (conf.sequencerConnections == newConnections) {
            logger.trace(
              "already set DomainConnectionConfig.sequencerConnections to the local sequencer only."
            )
            None
          } else {
            logger.info(
              "setting DomainConnectionConfig.sequencerConnections to the local sequencer only."
            )
            Some(
              conf.copy(
                sequencerConnections = newConnections
              )
            )
          }
        case _ => None
      }
}
