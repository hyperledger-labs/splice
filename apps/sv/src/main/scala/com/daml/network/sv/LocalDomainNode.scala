package com.daml.network.sv

import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.Materializer
import com.daml.network.environment.*
import com.daml.network.sv.admin.api.client.SvConnection
import com.daml.network.util.TemplateJsonDecoder
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.config.ClientConfig
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.health.admin.data.NodeStatus
import com.digitalasset.canton.lifecycle.{FlagCloseable, Lifecycle}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.networking.Endpoint
import com.digitalasset.canton.participant.domain.DomainConnectionConfig
import com.digitalasset.canton.protocol.StaticDomainParameters
import com.digitalasset.canton.sequencing.{GrpcSequencerConnection, SequencerConnections}
import com.digitalasset.canton.topology.transaction.SignedTopologyTransactionX.GenericSignedTopologyTransactionX
import com.digitalasset.canton.topology.transaction.TopologyMappingX.Code.{
  NamespaceDelegationX,
  OwnerToKeyMappingX,
}
import com.digitalasset.canton.topology.{DomainId, UniqueIdentifier}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.{DomainAlias, SequencerAlias}
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
    val sequencerInternalConfig: ClientConfig,
    val sequencerExternalPublicUrl: String,
    override val loggerFactory: NamedLoggerFactory,
    override protected[this] val retryProvider: RetryProvider,
)(implicit
    ec: ExecutionContextExecutor,
    httpClient: HttpRequest => Future[HttpResponse],
    templateDecoder: TemplateJsonDecoder,
    mat: Materializer,
) extends RetryProvider.Has
    with FlagCloseable
    with NamedLogging {

  val sequencerConnection =
    new GrpcSequencerConnection(
      LocalDomainNode.toEndpoints(sequencerInternalConfig),
      transportSecurity = sequencerInternalConfig.tls.isDefined,
      customTrustCertificates = None,
      SequencerAlias.Default,
    )

  private def containsIdentityTransactions(
      uid: UniqueIdentifier,
      txs: Seq[GenericSignedTopologyTransactionX],
  ) =
    txs.exists(tx =>
      tx.transaction.mapping.code == NamespaceDelegationX && tx.transaction.mapping.namespace == uid.namespace
    ) &&
      txs.exists(tx =>
        tx.transaction.mapping.code == OwnerToKeyMappingX && tx.transaction.mapping.namespace == uid.namespace
      )

  private def addIdentityTransactions(
      node: String,
      domainId: DomainId,
      uid: UniqueIdentifier,
      participantAdminConnection: ParticipantAdminConnection,
      identityTransactions: Seq[GenericSignedTopologyTransactionX],
  )(implicit traceContext: TraceContext) = {
    logger.info(s"Adding identity transactions for $node $uid")
    for {
      txs <- participantAdminConnection.getIdentityTransactions(uid, Some(domainId))
      _ <-
        if (containsIdentityTransactions(uid, txs)) {
          logger.info("Identity transactions have already been uploaded")
          Future.unit
        } else
          for {
            _ <- participantAdminConnection.addTopologyTransactions(identityTransactions)
            _ <- waitForIdentityTransaction(domainId, uid, participantAdminConnection)
          } yield ()
    } yield ()
  }

  private def waitForIdentityTransaction(
      domainId: DomainId,
      uid: UniqueIdentifier,
      participantAdminConnection: ParticipantAdminConnection,
  )(implicit traceContext: TraceContext) =
    retryProvider.waitUntil(
      show"the identity transactions for $uid are visible",
      participantAdminConnection.getIdentityTransactions(uid, Some(domainId)).map { txs =>
        if (!containsIdentityTransactions(uid, txs)) {
          throw Status.NOT_FOUND
            .withDescription(
              show"identity transactions for $uid"
            )
            .asRuntimeException()
        }
      },
      logger,
    )

  private def statusToEither[S <: NodeStatus.Status](
      status: NodeStatus[S]
  ): Either[NodeStatus.NotInitialized, NodeStatus.Success[S]] = status match {
    case s: NodeStatus.NotInitialized => Left(s)
    case s: NodeStatus.Success[S] => Right(s)
    case NodeStatus.Failure(msg) =>
      throw Status.FAILED_PRECONDITION
        .withDescription(s"Failed to query mediator status: $msg")
        .asRuntimeException
  }

  /** Onboard the mediator operated by this SV to the domain if it is not already initialized.
    */
  def onboardLocalMediatorIfRequired(
      domainId: DomainId,
      participantAdminConnection: ParticipantAdminConnection,
      svConnection: SvConnection,
  )(implicit traceContext: TraceContext): Future[Unit] = {
    retryProvider
      .getValueWithRetries(
        "mediator status",
        mediatorAdminConnection.getStatus.map(statusToEither(_)),
        logger,
      )
      .flatMap {
        case Left(NodeStatus.NotInitialized(_)) =>
          logger.info("Onboarding mediator")
          onboardLocalMediator(domainId, participantAdminConnection, svConnection)
        case Right(NodeStatus.Success(_)) =>
          logger.info("Mediator is already onboarded")
          Future.unit
      }
  }

  /** Onboard the mediator operated by this SV to the domain if it is not already initialized.
    */
  private def onboardLocalMediator(
      domainId: DomainId,
      participantAdminConnection: ParticipantAdminConnection,
      svConnection: SvConnection,
  )(implicit traceContext: TraceContext): Future[Unit] = {
    logger.info("Adding mediator identity transactions")
    for {
      mediatorId <- mediatorAdminConnection.getMediatorId
      identity <- mediatorAdminConnection.getIdentityTransactions(mediatorId.uid, domainId = None)
      _ <- addIdentityTransactions(
        "mediator",
        domainId,
        mediatorId.uid,
        participantAdminConnection,
        identity,
      )
      _ = logger.info(s"Onboarding mediator $mediatorId through sponsoring SV")
      _ <- retryProvider.retryForAutomation(
        "Onboarding mediator through sponsoring SV",
        svConnection.onboardSvMediator(mediatorId),
        logger,
      )
      _ = logger.info(s"Onboarded mediator $mediatorId")
      _ <- retryProvider.waitUntil(
        "local sequencer observes mediator as onboarded",
        // Otherwise we might fail with `PERMISSION_DENIED` during initialization
        sequencerAdminConnection
          .getMediatorDomainState(domainId)
          .map { state =>
            if (!state.mapping.active.contains(mediatorId)) {
              throw Status.FAILED_PRECONDITION
                .withDescription(
                  s"Mediator $mediatorId not in active mediators ${state.mapping.active.forgetNE}"
                )
                .asRuntimeException()
            }
          },
        logger,
      )
      _ = logger.info(s"Initializing mediator $mediatorId")
      _ <- retryProvider.retryForAutomation(
        "Initializing mediator",
        mediatorAdminConnection.getStatus.flatMap {
          case NodeStatus.NotInitialized(_) =>
            mediatorAdminConnection.initialize(
              domainId,
              staticDomainParameters,
              sequencerConnection,
            )
          case NodeStatus.Success(_) =>
            logger.info("Mediator is already initialized")
            Future.unit
          case NodeStatus.Failure(err) =>
            Future.failed(
              Status.UNAVAILABLE
                .withDescription(s"Failed to query status endpoint of mediator $err")
                .asRuntimeException()
            )
        },
        logger,
      )
      _ <- retryProvider.waitUntil(
        "mediator observes itself as onboarded",
        mediatorAdminConnection.getMediatorDomainState(domainId).map { state =>
          if (!state.mapping.active.contains(mediatorId)) {
            throw Status.FAILED_PRECONDITION
              .withDescription(
                s"Mediator $mediatorId not in active mediators ${state.mapping.active.forgetNE}"
              )
              .asRuntimeException()
          }
        },
        logger,
      )
    } yield ()
  }

  /** Onboard the sequencer operated by this SV to the domain if it is not already.
    */
  def onboardLocalSequencerIfRequired(
      domainAlias: DomainAlias,
      domainId: DomainId,
      participantAdminConnection: ParticipantAdminConnection,
      svConnection: SvConnection,
  )(implicit traceContext: TraceContext): Future[Unit] =
    retryProvider
      .getValueWithRetries(
        "sequencer status",
        sequencerAdminConnection.getStatus.map(statusToEither(_)),
        logger,
      )
      .flatMap {
        case Left(NodeStatus.NotInitialized(_)) =>
          logger.info("Onboarding sequencer")
          onboardLocalSequencer(domainAlias, domainId, participantAdminConnection, svConnection)
        case Right(NodeStatus.Success(_)) =>
          logger.info("Sequencer is already onboarded")
          Future.unit
      }

  private def onboardLocalSequencer(
      domainAlias: DomainAlias,
      domainId: DomainId,
      participantAdminConnection: ParticipantAdminConnection,
      svConnection: SvConnection,
  )(implicit traceContext: TraceContext): Future[Unit] = {
    logger.info("Adding sequencer identity transactions")
    logger.info(domainAlias.toProtoPrimitive)
    for {
      sequencerId <- sequencerAdminConnection.getSequencerId
      identity <- sequencerAdminConnection.getIdentityTransactions(sequencerId.uid, domainId = None)
      _ <- addIdentityTransactions(
        "sequencer",
        domainId,
        sequencerId.uid,
        participantAdminConnection,
        identity,
      )
      _ = logger.info(s"Onboarding sequencer $sequencerId through sponsoring SV")
      snapshot <- retryProvider.retryForAutomation(
        "Onbarding sequencer through sponsoring SV",
        svConnection.onboardSvSequencer(sequencerId),
        logger,
      )
      _ = logger.info(s"Onboarded sequencer $sequencerId")
      _ = logger.info(s"Initializing sequencer $sequencerId")
      _ <- retryProvider.retryForAutomation(
        "Initializing sequencer",
        sequencerAdminConnection.getStatus.flatMap {
          case NodeStatus.NotInitialized(_) =>
            sequencerAdminConnection.initialize(
              snapshot.topologySnapshot,
              staticDomainParameters,
              Some(snapshot.sequencerSnapshot),
            )
          case NodeStatus.Success(_) =>
            logger.info("Sequencer is already initialized")
            Future.unit
          case NodeStatus.Failure(err) =>
            Future.failed(
              Status.UNAVAILABLE
                .withDescription(s"Failed to query status endpoint of sequencer $err")
                .asRuntimeException()
            )
        },
        logger,
      )
      _ = logger.info(
        "Sequencer initialized, changing participant connection to point to new sequencer"
      )
      _ <- participantAdminConnection.modifyDomainConnectionConfig(
        domainAlias,
        addLocalSequencerConnection(sequencerInternalConfig),
      )
      _ = logger.info("Participant is now connected to new sequencer")
    } yield ()
  }

  private def addLocalSequencerConnection(
      sequencerConfig: ClientConfig
  )(implicit traceContext: TraceContext): DomainConnectionConfig => Option[DomainConnectionConfig] =
    conf =>
      conf.sequencerConnections.default match {
        case defaultConnection: GrpcSequencerConnection =>
          val localEndpoint = LocalDomainNode.toEndpoint(sequencerConfig)
          val localSequencerConnection =
            new GrpcSequencerConnection(
              NonEmpty.mk(Seq, localEndpoint),
              transportSecurity = sequencerConfig.tls.isDefined,
              customTrustCertificates = None,
              SequencerAlias.Local,
            )
          val newConnections = SequencerConnections.many(
            NonEmpty.mk(Seq, defaultConnection, localSequencerConnection),
            PositiveInt.tryCreate(1),
          )
          if (conf.sequencerConnections == newConnections) {
            logger.info("Sequencers already set.")
            None
          } else {
            Some(
              conf.copy(
                sequencerConnections = newConnections
              )
            )
          }
        case _ => None
      }

  override protected def onClosed() = {
    Lifecycle.close(sequencerAdminConnection, mediatorAdminConnection)(logger)
    super.onClosed()
  }
}

object LocalDomainNode {
  // TODO(#5107) Consider using something other than a ClientConfig in the config file
  // to simplify conversion to GrpcSequencerConnection.
  private def toEndpoints(config: ClientConfig): NonEmpty[Seq[Endpoint]] =
    NonEmpty.mk(Seq, toEndpoint(config))
  private def toEndpoint(config: ClientConfig): Endpoint = Endpoint(config.address, config.port)
}
