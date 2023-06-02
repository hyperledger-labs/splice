package com.daml.network.sv

import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.Materializer
import com.daml.network.environment.*
import com.daml.network.sv.admin.api.client.SvConnection
import com.daml.network.util.TemplateJsonDecoder
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.{DomainAlias, SequencerAlias}
import com.digitalasset.canton.config.{ClientConfig, ProcessingTimeout}
import com.digitalasset.canton.lifecycle.{FlagCloseable, Lifecycle}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.networking.Endpoint
import com.digitalasset.canton.participant.domain.DomainConnectionConfig
import com.digitalasset.canton.protocol.StaticDomainParameters
import com.digitalasset.canton.sequencing.GrpcSequencerConnection
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status

import scala.concurrent.{ExecutionContextExecutor, Future}

/** Connections to the domain node (composed of sequencer + mediator) operated by the SV running this SV app.
  * Note that this is optional. An SV app can run without a dedicated domain node.
  * TODO(#5195) Consider making this mandatory.
  */
final class LocalDomainNode(
    val sequencerAdminConnection: SequencerAdminConnection,
    val mediatorAdminConnection: MediatorAdminConnection,
    val staticDomainParameters: StaticDomainParameters,
    val sequencerPublicConfig: ClientConfig,
    override val timeouts: ProcessingTimeout,
    override val loggerFactory: NamedLoggerFactory,
    retryProvider: RetryProvider,
)(implicit
    ec: ExecutionContextExecutor,
    httpClient: HttpRequest => Future[HttpResponse],
    templateDecoder: TemplateJsonDecoder,
    mat: Materializer,
) extends FlagCloseable
    with NamedLogging {

  /** Onboard the mediator operated by this SV to the domain.
    */
  def onboardLocalMediator(
      domainId: DomainId,
      participantAdminConnection: ParticipantAdminConnection,
      svConnection: SvConnection,
      svcRulesLock: SvcRulesLock,
  )(implicit traceContext: TraceContext): Future[Unit] = {
    logger.info("Adding mediator identity transactions")
    for {
      mediatorId <- mediatorAdminConnection.getMediatorId
      identity <- mediatorAdminConnection.getMediatorIdentityTransactions(mediatorId)
      _ <- participantAdminConnection.addTopologyTransactions(identity)
      _ <- retryProvider.retryForAutomation(
        "Wait to observe mediator identity transactions",
        participantAdminConnection.getIdentityTransactions(domainId, mediatorId.uid).map { txs =>
          if (txs.length != identity.length) {
            throw Status.FAILED_PRECONDITION
              .withDescription(
                s"Expected ${identity.length} identity transactions for mediator ${mediatorId} but got ${txs.length}"
              )
              .asRuntimeException()
          }
        },
        logger,
      )
      // We need to lock between the topology transaction and the mediator initializing. Otherwise things blow up with
      // "Unable to find mediator group"
      _ <- svcRulesLock.lock()
      _ = logger.info(s"Onboarding mediator $mediatorId through sponsoring SV")
      _ <- svConnection.onboardSvMediator(mediatorId)
      _ = logger.info(s"Onboarded mediator $mediatorId")
      _ = logger.info(s"Initializating mediator $mediatorId")
      _ <- mediatorAdminConnection.initialize(
        domainId,
        staticDomainParameters,
        new GrpcSequencerConnection(
          LocalDomainNode.toEndpoints(sequencerPublicConfig),
          transportSecurity = sequencerPublicConfig.tls.isDefined,
          customTrustCertificates = None,
          SequencerAlias.Default,
        ),
      )
      _ = logger.info("Watiing for mediator to observe itself as onboarded")
      _ <- retryProvider.retryForAutomation(
        "Mediator observers itself as onboarded",
        mediatorAdminConnection.getMediatorState(domainId).map { state =>
          if (!state.active.contains(mediatorId)) {
            throw Status.FAILED_PRECONDITION
              .withDescription(
                s"Mediator $mediatorId not in active mediators ${state.active.forgetNE}"
              )
              .asRuntimeException()
          }
        },
        logger,
      )
      _ <- svcRulesLock.unlock()
    } yield ()
  }

  /** Onboard the sequencer operated by this SV to the domain.
    */
  def onboardLocalSequencer(
      domainAlias: DomainAlias,
      domainId: DomainId,
      participantAdminConnection: ParticipantAdminConnection,
      svConnection: SvConnection,
  )(implicit traceContext: TraceContext): Future[Unit] = {
    logger.info("Adding sequencer identity transactions")
    for {
      sequencerId <- sequencerAdminConnection.getSequencerId
      identity <- sequencerAdminConnection.getSequencerIdentityTransactions(sequencerId)
      _ <- participantAdminConnection.addTopologyTransactions(identity)
      _ <- retryProvider.retryForAutomation(
        "Wait to observe sequencer identity transactions",
        participantAdminConnection.getIdentityTransactions(domainId, sequencerId.uid).map { txs =>
          if (txs.length != identity.length) {
            throw Status.FAILED_PRECONDITION
              .withDescription(
                s"Expected ${identity.length} identity transactions for sequencer ${sequencerId} but got ${txs.length}"
              )
              .asRuntimeException()
          }
        },
        logger,
      )
      _ = logger.info(s"Onboarding sequencer $sequencerId through sponsoring SV")
      snapshot <- svConnection.onboardSvSequencer(sequencerId)
      _ = logger.info(s"Onboarded sequencer $sequencerId")
      _ = logger.info(s"Initializing sequencer $sequencerId")
      _ <- sequencerAdminConnection.initialize(
        snapshot.topologySnapshot,
        staticDomainParameters,
        snapshot.sequencerSnapshot,
      )
      _ = logger.info(
        "Sequencer initialized, changing participant connection to point to new sequencer"
      )
      _ <- participantAdminConnection.modifyDomainConnectionConfig(
        domainAlias,
        LocalDomainNode.setSequencerEndpoint(sequencerPublicConfig),
      )
      _ = logger.info("Participant is now connected to new sequencer")
    } yield ()
  }

  override protected def onClosed() = {
    Lifecycle.close(sequencerAdminConnection, sequencerAdminConnection)(logger)
    super.onClosed()
  }
}

object LocalDomainNode {
  // TODO(#5107) Consider using something other than a ClientConfig in the config file
  // to simplify conversion to GrpcSequencerConnection.
  private def toEndpoints(config: ClientConfig): NonEmpty[Seq[Endpoint]] =
    NonEmpty.mk(Seq, Endpoint(config.address, config.port))

  private def setSequencerEndpoint(
      endpoint: ClientConfig
  ): DomainConnectionConfig => DomainConnectionConfig = conf =>
    conf.copy(
      sequencerConnection = conf.sequencerConnection match {
        case con: GrpcSequencerConnection =>
          con.copy(endpoints = toEndpoints(endpoint))
        case con =>
          throw new IllegalArgumentException(s"Expected GrpcSequencerConnection but got $con")
      }
    )
}
