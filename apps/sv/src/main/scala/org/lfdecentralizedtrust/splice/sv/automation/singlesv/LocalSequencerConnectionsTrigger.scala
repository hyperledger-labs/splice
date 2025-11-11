// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv

import org.lfdecentralizedtrust.splice.automation.{
  PollingTrigger,
  TriggerContext,
  TriggerEnabledSynchronization,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.decentralizedsynchronizer.SequencerConfig
import org.lfdecentralizedtrust.splice.environment.ParticipantAdminConnection
import org.lfdecentralizedtrust.splice.sv.LocalSynchronizerNode
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.config.ClientConfig
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, PositiveInt}
import com.digitalasset.canton.{SequencerAlias, SynchronizerAlias}
import com.digitalasset.canton.participant.synchronizer.SynchronizerConnectionConfig
import com.digitalasset.canton.sequencing.{
  GrpcSequencerConnection,
  SequencerConnectionPoolDelays,
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
    decentralizedSynchronizerAlias: SynchronizerAlias,
    store: SvDsoStore,
    sequencerInternalConfig: ClientConfig,
    sequencerRequestAmplification: SubmissionRequestAmplification,
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
      // TODO(#998): double-check that the right domain-ids are used in the right place to make this work with soft-domain migration
      synchronizerId <- participantAdminConnection.getSynchronizerId(decentralizedSynchronizerAlias)
      domainTimeLb <- participantAdminConnection.getDomainTimeLowerBound(
        synchronizerId,
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
        participantAdminConnection.modifySynchronizerConnectionConfigAndReconnect(
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
  )(implicit
      traceContext: TraceContext
  ): SynchronizerConnectionConfig => Option[SynchronizerConnectionConfig] =
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
              transportSecurity = internalSequencerClientConfig.tlsConfig.isDefined,
              customTrustCertificates = None,
              SequencerAlias.Default,
              sequencerId = None,
            )
          val newConnections = SequencerConnections.tryMany(
            Seq(localSequencerConnection),
            PositiveInt.tryCreate(1),
            // We only have a single connection here.
            sequencerLivenessMargin = NonNegativeInt.zero,
            submissionRequestAmplification = sequencerRequestAmplification,
            // TODO(#2666) Make the delays configurable.
            sequencerConnectionPoolDelays = SequencerConnectionPoolDelays.default,
          )
          if (
            ParticipantAdminConnection.dropSequencerId(
              conf.sequencerConnections
            ) == ParticipantAdminConnection.dropSequencerId(newConnections)
          ) {
            logger.trace(
              "already set SynchronizerConnectionConfig.sequencerConnections to the local sequencer only."
            )
            None
          } else {
            logger.info(
              "setting SynchronizerConnectionConfig.sequencerConnections to the local sequencer only."
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
