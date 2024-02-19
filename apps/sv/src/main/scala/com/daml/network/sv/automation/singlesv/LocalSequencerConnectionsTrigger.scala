package com.daml.network.sv.automation.singlesv

import com.daml.network.automation.{PollingTrigger, TriggerContext}
import com.daml.network.codegen.java.cn.svc.globaldomain.SequencerConfig
import com.daml.network.environment.ParticipantAdminConnection
import com.daml.network.sv.LocalDomainNode
import com.daml.network.sv.store.SvSvcStore
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.config.ClientConfig
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.{DomainAlias, SequencerAlias}
import com.digitalasset.canton.participant.domain.DomainConnectionConfig
import com.digitalasset.canton.sequencing.{GrpcSequencerConnection, SequencerConnections}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import com.digitalasset.canton.util.ShowUtil.*

import java.time.Instant
import scala.jdk.OptionConverters.*
import scala.concurrent.{ExecutionContext, Future}

class LocalSequencerConnectionsTrigger(
    override protected val context: TriggerContext,
    participantAdminConnection: ParticipantAdminConnection,
    globalDomainAlias: DomainAlias,
    store: SvSvcStore,
    sequencerInternalConfig: ClientConfig,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingTrigger {
  private val svParty = store.key.svParty
  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] = {
    for {
      svcRules <- store.getSvcRules()
      domainTime <- participantAdminConnection.getDomainTime(globalDomainAlias, timeouts.default)
      globalDomainId <- store.getCoinRulesDomain()(traceContext)
      svcRulesActiveSequencerConfig = getAvailableSequencerConfigFromSvcRules(
        svParty,
        svcRules,
        domainTime.timestamp.toInstant,
        globalDomainId,
      )
      _ <- svcRulesActiveSequencerConfig.fold {
        logger.debug(
          show"Member info or sequencer info not (yet) published to SvcRules for our own party ${store.key.svParty}, skipping"
        )
        Future.unit
      } { publishedSequencerInfo =>
        participantAdminConnection.modifyDomainConnectionConfigAndReconnect(
          globalDomainAlias,
          setLocalSequencerConnection(
            publishedSequencerInfo,
            sequencerInternalConfig,
            domainTime.timestamp.toInstant,
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
          val localEndpoint = LocalDomainNode.toEndpoint(internalSequencerClientConfig)
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
