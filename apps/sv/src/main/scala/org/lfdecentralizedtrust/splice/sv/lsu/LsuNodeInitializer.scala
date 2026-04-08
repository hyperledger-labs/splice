// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.lsu

import cats.implicits.showInterpolator
import cats.syntax.foldable.*
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.admin.api.client.data.NodeStatus
import com.digitalasset.canton.admin.api.client.data.SequencerHealthStatus.implicitPrettyString
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.networking
import com.digitalasset.canton.protocol.StaticSynchronizerParameters
import com.digitalasset.canton.topology.PhysicalSynchronizerId
import com.digitalasset.canton.topology.transaction.GrpcConnection
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import org.lfdecentralizedtrust.splice.environment.{RetryFor, RetryProvider}
import org.lfdecentralizedtrust.splice.environment.SynchronizerNode.LocalSynchronizerNodes
import org.lfdecentralizedtrust.splice.setup.NodeInitializer
import org.lfdecentralizedtrust.splice.sv.LocalSynchronizerNode

import java.net.URI
import scala.concurrent.{ExecutionContext, Future}

class LsuNodeInitializer(
    localSynchronizerNodes: LocalSynchronizerNodes[LocalSynchronizerNode],
    successorSynchronizerNode: LocalSynchronizerNode,
    val loggerFactory: NamedLoggerFactory,
    retryProvider: RetryProvider,
)(implicit ec: ExecutionContext)
    extends NamedLogging {

  private val newSequencerIntializer = new NodeInitializer(
    successorSynchronizerNode.sequencerAdminConnection,
    retryProvider,
    loggerFactory,
  )
  private val newMediatorInitializer = new NodeInitializer(
    successorSynchronizerNode.mediatorAdminConnection,
    retryProvider,
    loggerFactory,
  )

  val successorConnection = {
    networking.Endpoint
      .fromUris(
        NonEmpty(
          Seq,
          URI.create(
            successorSynchronizerNode.sequencerExternalPublicUrl
          ),
        )
      )
      .map { case (validatedEndpoints, useTls) =>
        GrpcConnection(
          validatedEndpoints,
          useTls,
          None,
        )
      }
      .getOrElse(
        throw Status.INVALID_ARGUMENT
          .withDescription(
            s"Failed to create gRPC connection for ${successorSynchronizerNode.sequencerExternalPublicUrl}"
          )
          .asRuntimeException()
      )
  }

  def initializeSynchronizer(
      state: LsuState,
      successorSynchronizerId: PhysicalSynchronizerId,
      now: CantonTimestamp,
      upgradeTime: Option[CantonTimestamp],
  )(implicit tc: TraceContext): Future[StaticSynchronizerParameters] = {
    logger.info("Initializing sequencer and mediators from the data of the old nodes")
    for {
      _ <- newMediatorInitializer.initializeFromDump(state.nodeIdentities.mediator)
      _ <- newSequencerIntializer.initializeFromDump(state.nodeIdentities.sequencer)
      parameters = successorSynchronizerNode.staticSynchronizerParameters(
        successorSynchronizerId.serial
      )
      _ <- retryProvider.ensureThat(
        RetryFor.InitializingClientCalls,
        "init_sequencer_lsu",
        "Initialize sequencer from the state of the predecessor",
        successorSynchronizerNode.sequencerAdminConnection.getStatus.map {
          case NodeStatus.Failure(msg) => Left(msg)
          case NodeStatus.NotInitialized(_, _) => Left("Not initialized")
          case NodeStatus.Success(_) =>
            logger.info("Sequencer is already initialized")
            Right(())
        },
        (_: String) => {
          logger.info(
            show"Initializing sequencer from predecessor with $parameters"
          )
          successorSynchronizerNode.sequencerAdminConnection.initializeFromPredecessor(
            state.synchronizerStatePath,
            parameters.copy(
              protocolVersion = successorSynchronizerId.protocolVersion
            ),
          )
        },
        logger,
      )
      psid <- successorSynchronizerNode.sequencerAdminConnection.getPhysicalSynchronizerId()
      _ <- upgradeTime.traverse_ { t =>
        if (t.isAfter(now))
          localSynchronizerNodes.current.sequencerAdminConnection.getSequencerId.flatMap {
            sequencerId =>
              localSynchronizerNodes.current.sequencerAdminConnection.ensureSequencerSuccessor(
                psid,
                sequencerId = sequencerId,
                connection = successorConnection,
              )
          }
        else {
          logger.warn("Not publishing sequencer successor as we are past upgrade time")
          Future.unit
        }
      }
      _ <- retryProvider.ensureThat(
        RetryFor.InitializingClientCalls,
        "init_mediator_lsu",
        "Initialize mediator after the LSU",
        successorSynchronizerNode.mediatorAdminConnection.getStatus.map {
          case NodeStatus.Failure(msg) => Left(msg)
          case NodeStatus.NotInitialized(_, _) => Left("Not initialized")
          case NodeStatus.Success(_) =>
            logger.info("Mediator is already initialized")
            Right(())
        },
        (_: String) => {
          logger.info(show"Initializing mediator")
          successorSynchronizerNode.mediatorAdminConnection.initialize(
            psid,
            successorSynchronizerNode.internalSequencerConnection,
            successorSynchronizerNode.mediatorSequencerAmplification.toInternal,
            successorSynchronizerNode.mediatorSequencerConnectionPoolDelays.toInternal,
          )
        },
        logger,
      )
    } yield parameters

  }
}
