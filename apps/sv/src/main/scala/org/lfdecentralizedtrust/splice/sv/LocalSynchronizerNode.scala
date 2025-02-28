// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv

import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.admin.api.client.data.NodeStatus
import com.digitalasset.canton.config.ClientConfig
import com.digitalasset.canton.lifecycle.{FlagCloseable, LifeCycle}
import com.digitalasset.canton.logging.pretty.PrettyInstances.prettyPrettyPrinting
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.networking.Endpoint
import com.digitalasset.canton.protocol.StaticSynchronizerParameters
import com.digitalasset.canton.sequencing.{
  GrpcSequencerConnection,
  SequencerConnection,
  SubmissionRequestAmplification,
}
import com.digitalasset.canton.topology.store.TopologyStoreId
import com.digitalasset.canton.topology.transaction.SignedTopologyTransaction.GenericSignedTopologyTransaction
import com.digitalasset.canton.topology.transaction.TopologyMapping.Code.{
  NamespaceDelegation,
  OwnerToKeyMapping,
}
import com.digitalasset.canton.topology.{ForceFlag, SynchronizerId, UniqueIdentifier}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.{SequencerAlias, SynchronizerAlias}
import io.grpc.Status
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.admin.api.client.commands.HttpCommandException
import org.lfdecentralizedtrust.splice.environment.*
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.sv.admin.api.client.SvConnection
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.onboarding.SvOnboardingUnlimitedTrafficTrigger.UnlimitedTraffic
import org.lfdecentralizedtrust.splice.sv.config.SequencerPruningConfig
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder

import java.time.Duration
import scala.concurrent.{ExecutionContextExecutor, Future}

/** Connections to the domain node (composed of sequencer + mediator) operated by the SV running this SV app.
  * Note that this is optional. An SV app can run without a dedicated domain node.
  * TODO(#5195) Consider making this mandatory.
  */
final class LocalSynchronizerNode(
    participantAdminConnection: ParticipantAdminConnection,
    override val sequencerAdminConnection: SequencerAdminConnection,
    override val mediatorAdminConnection: MediatorAdminConnection,
    val staticDomainParameters: StaticSynchronizerParameters,
    val sequencerInternalConfig: ClientConfig,
    override val sequencerExternalPublicUrl: String,
    override val sequencerAvailabilityDelay: Duration,
    val sequencerPruningConfig: Option[SequencerPruningConfig],
    override val mediatorSequencerAmplification: SubmissionRequestAmplification,
    override val loggerFactory: NamedLoggerFactory,
    override protected[this] val retryProvider: RetryProvider,
    sequencerConfig: SequencerConfig,
)(implicit
    ec: ExecutionContextExecutor,
    httpClient: HttpClient,
    templateDecoder: TemplateJsonDecoder,
    mat: Materializer,
) extends SynchronizerNode(
      sequencerAdminConnection,
      mediatorAdminConnection,
      sequencerExternalPublicUrl,
      sequencerAvailabilityDelay,
      sequencerConfig,
      mediatorSequencerAmplification,
    )
    with RetryProvider.Has
    with FlagCloseable
    with NamedLogging {

  val sequencerConnection: GrpcSequencerConnection =
    LocalSynchronizerNode.toSequencerConnection(sequencerInternalConfig)

  private def containsIdentityTransactions(
      uid: UniqueIdentifier,
      txs: Seq[GenericSignedTopologyTransaction],
  ) =
    txs.exists(tx =>
      tx.transaction.mapping.code == NamespaceDelegation && tx.transaction.mapping.namespace == uid.namespace
    ) &&
      txs.exists(tx =>
        tx.transaction.mapping.code == OwnerToKeyMapping && tx.transaction.mapping.namespace == uid.namespace
      )

  private def addIdentityTransactions(
      node: String,
      synchronizerId: SynchronizerId,
      uid: UniqueIdentifier,
      identityTransactions: Seq[GenericSignedTopologyTransaction],
  )(implicit traceContext: TraceContext) = {
    logger.info(s"Adding identity transactions for $node $uid")
    for {
      txs <- participantAdminConnection.getIdentityTransactions(
        uid,
        TopologyStoreId.SynchronizerStore(synchronizerId),
      )
      _ <-
        if (containsIdentityTransactions(uid, txs)) {
          logger.info("Identity transactions have already been uploaded")
          Future.unit
        } else
          for {
            _ <- participantAdminConnection.addTopologyTransactions(
              TopologyStoreId.SynchronizerStore(synchronizerId),
              identityTransactions,
              ForceFlag.AlienMember,
            )
            _ <- waitForIdentityTransaction(synchronizerId, uid)
          } yield ()
    } yield ()
  }

  private def waitForIdentityTransaction(
      synchronizerId: SynchronizerId,
      uid: UniqueIdentifier,
  )(implicit traceContext: TraceContext) =
    retryProvider.waitUntil(
      RetryFor.WaitingOnInitDependency,
      "identity_transaction",
      show"the identity transactions for $uid are visible",
      participantAdminConnection
        .getIdentityTransactions(uid, TopologyStoreId.SynchronizerStore(synchronizerId))
        .map { txs =>
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
  def initializeLocalMediatorIfRequired(
      synchronizerId: SynchronizerId
  )(implicit traceContext: TraceContext): Future[Unit] = {
    onMediatorNotInitialized {
      logger.info("Onboarding mediator")
      initializeLocalMediator(synchronizerId)
    }
  }

  def addLocalMediatorIdentityIfRequired(
      synchronizerId: SynchronizerId
  )(implicit traceContext: TraceContext): Future[Unit] = {
    onMediatorNotInitialized {
      addMediatorIdentityTransactions(synchronizerId)
    }
  }

  private def onMediatorNotInitialized(
      action: => Future[Unit]
  )(implicit traceContext: TraceContext): Future[Unit] = {
    retryProvider
      .getValueWithRetries(
        RetryFor.WaitingOnInitDependency,
        "mediator status",
        "mediator status",
        mediatorAdminConnection.getStatus.map(statusToEither),
        logger,
      )
      .flatMap {
        case Left(NodeStatus.NotInitialized(_, _)) =>
          action
        case Right(NodeStatus.Success(_)) =>
          logger.info("Mediator is already onboarded")
          Future.unit
      }
  }

  private def addMediatorIdentityTransactions(
      synchronizerId: SynchronizerId
  )(implicit traceContext: TraceContext) = {
    logger.info("Adding mediator identity transactions")
    for {
      mediatorId <- mediatorAdminConnection.getMediatorId
      identity <- mediatorAdminConnection.getIdentityTransactions(
        mediatorId.uid,
        TopologyStoreId.AuthorizedStore,
      )
      _ <- addIdentityTransactions(
        "mediator",
        synchronizerId,
        mediatorId.uid,
        identity,
      )
    } yield ()
  }

  /** Onboard the mediator operated by this SV to the domain if it is not already initialized.
    */
  private def initializeLocalMediator(
      synchronizerId: SynchronizerId
  )(implicit traceContext: TraceContext): Future[Unit] = {
    for {
      mediatorId <- mediatorAdminConnection.getMediatorId
      _ <- retryProvider.waitUntil(
        RetryFor.WaitingOnInitDependency,
        "mediator_unlimited_traffic",
        "Mediator has been granted unlimited traffic",
        sequencerAdminConnection
          .getSequencerTrafficControlState(
            mediatorId
          )
          .map(traffic =>
            if (traffic.extraTrafficLimit != UnlimitedTraffic)
              throw Status.FAILED_PRECONDITION
                .withDescription(
                  show"Mediator $mediatorId does not have unlimited traffic limit, current limit: ${traffic.extraTrafficLimit}"
                )
                .asRuntimeException()
            else
              ()
          ),
        logger,
      )
      _ <- retryProvider.waitUntil(
        RetryFor.WaitingOnInitDependency,
        "sequencer_observes_mediator_onboarded",
        "local sequencer observes mediator as onboarded",
        // Otherwise we might fail with `PERMISSION_DENIED` during initialization
        sequencerAdminConnection
          .getMediatorSynchronizerState(synchronizerId)
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
      _ <- retryProvider.retry(
        RetryFor.WaitingOnInitDependency,
        "initialize_mediator",
        "Initializing mediator",
        mediatorAdminConnection.getStatus.flatMap {
          case NodeStatus.NotInitialized(_, _) =>
            mediatorAdminConnection.initialize(
              synchronizerId,
              sequencerConnection,
              mediatorSequencerAmplification,
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
        RetryFor.WaitingOnInitDependency,
        "mediator_onboarded",
        "mediator observes itself as onboarded",
        mediatorAdminConnection.getMediatorSynchronizerState(synchronizerId).map { state =>
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
  def addLocalSequencerIdentityIfRequired(
      synchronizerAlias: SynchronizerAlias,
      synchronizerId: SynchronizerId,
  )(implicit traceContext: TraceContext): Future[Unit] =
    retryProvider
      .getValueWithRetries(
        RetryFor.WaitingOnInitDependency,
        "sequencer status",
        "sequencer status",
        sequencerAdminConnection.getStatus.map(statusToEither),
        logger,
      )
      .flatMap {
        case Left(NodeStatus.NotInitialized(_, _)) =>
          logger.info("Adding sequencer identity")
          addLocalSequencerIdentity(
            synchronizerAlias,
            synchronizerId,
          )
        case Right(NodeStatus.Success(_)) =>
          logger.info("Sequencer identity is already added")
          Future.unit
      }

  private def addLocalSequencerIdentity(
      synchronizerAlias: SynchronizerAlias,
      synchronizerId: SynchronizerId,
  )(implicit traceContext: TraceContext): Future[Unit] = {
    logger.info(
      s"Adding sequencer identity transactions for domain ${synchronizerAlias.toProtoPrimitive}"
    )
    for {
      sequencerId <- sequencerAdminConnection.getSequencerId
      identity <- sequencerAdminConnection.getIdentityTransactions(
        sequencerId.uid,
        TopologyStoreId.AuthorizedStore,
      )
      _ <- addIdentityTransactions(
        "sequencer",
        synchronizerId,
        sequencerId.uid,
        identity,
      )
    } yield ()
  }

  /** Onboard the sequencer operated by this SV to the domain if it is not already.
    */
  def onboardLocalSequencerIfRequired(
      svConnection: => Future[SvConnection]
  )(implicit traceContext: TraceContext): Future[Unit] =
    retryProvider
      .getValueWithRetries(
        RetryFor.WaitingOnInitDependency,
        "sequencer status",
        "sequencer status",
        sequencerAdminConnection.getStatus.map(statusToEither),
        logger,
      )
      .flatMap {
        case Left(NodeStatus.NotInitialized(_, _)) =>
          logger.info("Onboarding sequencer")
          svConnection.flatMap(onboardLocalSequencer(_))
        case Right(NodeStatus.Success(_)) =>
          logger.info("Sequencer is already onboarded")
          Future.unit
      }

  private def onboardLocalSequencer(
      svConnection: SvConnection
  )(implicit traceContext: TraceContext): Future[Unit] = {
    for {
      sequencerId <- sequencerAdminConnection.getSequencerId
      _ = logger.info(s"Onboarding sequencer $sequencerId through sponsoring SV")
      onboardingState <- retryProvider.retry(
        RetryFor.WaitingOnInitDependency,
        "onboarding_sequencer",
        "Onbarding sequencer through sponsoring SV",
        svConnection.onboardSvSequencer(sequencerId).recover {
          // TODO(#13410) - remove once canton returns a retryable error
          case HttpCommandException(_, StatusCodes.BadRequest, message)
              if message.contains("SNAPSHOT_NOT_FOUND") =>
            throw Status.NOT_FOUND
              .withDescription(message)
              .asRuntimeException()
          case HttpCommandException(_, StatusCodes.BadRequest, message)
              if message.contains("BLOCK_NOT_FOUND") =>
            // ensure the request is retried as the sequencer will eventually finish processing the block
            throw Status.NOT_FOUND
              .withDescription(message)
              .asRuntimeException()
        },
        logger,
      )
      _ = logger.info(s"Onboarded sequencer $sequencerId")
      _ = logger.info(
        s"Initializing sequencer $sequencerId"
      )
      _ <- retryProvider.retry(
        RetryFor.WaitingOnInitDependency,
        "initializer_sequencer",
        "Initializing sequencer",
        sequencerAdminConnection.getStatus.flatMap {
          case NodeStatus.NotInitialized(_, _) =>
            sequencerAdminConnection.initializeFromOnboardingState(
              onboardingState
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
    } yield ()
  }

  def ensureMediatorSequencerRequestAmplification()(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    retryProvider.ensureThat(
      RetryFor.WaitingOnInitDependency,
      "ensure_mediator_sequencer_request_amplification",
      s"The mediator's sequencer request amplification is set to $mediatorSequencerAmplification",
      for {
        sequencerConnectionsO <- mediatorAdminConnection.getSequencerConnections()
        sequencerConnections = sequencerConnectionsO.getOrElse(
          throw Status.FAILED_PRECONDITION
            .withDescription(
              "Mediator not initialized properly; getSequencerConnections returned None"
            )
            .asRuntimeException
        )
        connections: Seq[SequencerConnection] = sequencerConnections.connections.toSeq
      } yield connections match {
        case Seq(connection) =>
          if (
            sequencerConnections.submissionRequestAmplification == mediatorSequencerAmplification
          ) {
            Right(())
          } else {
            Left(connection)
          }
        case _ =>
          throw Status.FAILED_PRECONDITION
            .withDescription(
              s"Mediator not initialized properly; expected a single sequencer connection got ${connections}"
            )
            .asRuntimeException
      },
      (sequencerConnection: SequencerConnection) =>
        mediatorAdminConnection.setSequencerConnection(
          sequencerConnection,
          mediatorSequencerAmplification,
        ),
      logger,
    )

  override protected def onClosed(): Unit = {
    LifeCycle.close(sequencerAdminConnection, mediatorAdminConnection)(logger)
    super.onClosed()
  }
}

object LocalSynchronizerNode {
  def toEndpoint(config: ClientConfig): Endpoint = Endpoint(config.address, config.port)

  // TODO(#5107) Consider using something other than a ClientConfig in the config file
  // to simplify conversion to GrpcSequencerConnection.
  private def toEndpoints(config: ClientConfig): NonEmpty[Seq[Endpoint]] =
    NonEmpty.mk(Seq, toEndpoint(config))

  def toSequencerConnection(config: ClientConfig, alias: SequencerAlias = SequencerAlias.Default) =
    new GrpcSequencerConnection(
      LocalSynchronizerNode.toEndpoints(config),
      transportSecurity = config.tls.isDefined,
      customTrustCertificates = None,
      alias,
    )
}
