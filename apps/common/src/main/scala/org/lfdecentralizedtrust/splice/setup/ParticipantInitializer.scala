// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.setup

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.{ParticipantId, SynchronizerId, UniqueIdentifier}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import org.lfdecentralizedtrust.splice.config.ParticipantBootstrapDumpConfig
import org.lfdecentralizedtrust.splice.environment.{ParticipantAdminConnection, RetryProvider}
import org.lfdecentralizedtrust.splice.identities.NodeIdentitiesDump

import java.nio.file.Path
import scala.concurrent.{ExecutionContextExecutor, Future}

/** Methods required by the SvApp and ValidatorApp to initialize their participant with existing identities */

object ParticipantInitializer {
  def ensureParticipantInitializedWithExpectedId(
      identifierName: String,
      participantAdminConnection: ParticipantAdminConnection,
      dumpConfig: Option[ParticipantBootstrapDumpConfig],
      loggerFactory: NamedLoggerFactory,
      retryProvider: RetryProvider,
  )(implicit
      ec: ExecutionContextExecutor,
      tc: TraceContext,
  ): Future[Unit] = {
    val participantInitializer = new ParticipantInitializer(
      identifierName,
      dumpConfig,
      loggerFactory,
      retryProvider,
      participantAdminConnection,
    )
    participantInitializer
      .ensureInitializedWithExpectedId()
  }

  def ensureInitializedWithRotatedOTK(
      identifierName: String,
      participantAdminConnection: ParticipantAdminConnection,
      dumpConfig: Option[ParticipantBootstrapDumpConfig],
      loggerFactory: NamedLoggerFactory,
      retryProvider: RetryProvider,
      synchronizerId: SynchronizerId,
  )(implicit
      ec: ExecutionContextExecutor,
      tc: TraceContext,
  ): Future[Unit] = {
    val participantInitializer = new ParticipantInitializer(
      identifierName,
      dumpConfig,
      loggerFactory,
      retryProvider,
      participantAdminConnection,
    )
    participantInitializer
      .ensureInitializedWithRotatedOTK(synchronizerId)
  }

  def getDump(config: ParticipantBootstrapDumpConfig): Future[NodeIdentitiesDump] =
    config match {
      case ParticipantBootstrapDumpConfig.File(file, _) => getDumpFromFile(file)
    }

  private def getDumpFromFile(file: Path): Future[NodeIdentitiesDump] = {
    NodeIdentitiesDump.fromJsonFile(file, ParticipantId.tryFromProtoPrimitive) match {
      case Right(dump) => Future.successful(dump)
      case Left(error) =>
        Future.failed(
          Status.INVALID_ARGUMENT
            .withDescription(s"Could not parse participant bootstrap dump at $file: $error")
            .asRuntimeException()
        )
    }
  }
}

class ParticipantInitializer(
    identifierName: String,
    dumpConfig: Option[ParticipantBootstrapDumpConfig],
    override protected val loggerFactory: NamedLoggerFactory,
    retryProvider: RetryProvider,
    participantAdminConnection: ParticipantAdminConnection,
)(implicit
    ec: ExecutionContextExecutor,
    tc: TraceContext,
) extends NamedLogging {

  private val nodeInitializer =
    new NodeInitializer(participantAdminConnection, retryProvider, loggerFactory)

  def ensureInitializedWithExpectedId(): Future[Unit] =
    dumpConfig match {
      case Some(c: ParticipantBootstrapDumpConfig.File) =>
        logger.info(s"Loading participant identities dump from file with config $c")
        for {
          dump <- ParticipantInitializer.getDump(c)
          newParticipantId = c.newParticipantIdentifier.fold(dump.id)(id =>
            ParticipantId(UniqueIdentifier.tryCreate(id, dump.id.uid.namespace))
          )
          result <- nodeInitializer.initializeFromDumpAndWait(dump, Some(newParticipantId))
        } yield result
      case None =>
        logger.info(s"Initializing participant $identifierName")
        for {
          _ <- nodeInitializer.initializeWithNewIdentityIfNeeded(
            identifierName,
            ParticipantId.apply,
          )
          _ <- nodeInitializer.waitForNodeInitialized()
        } yield {
          logger.info(s"Participant $identifierName is initialized")
        }
    }

  def ensureInitializedWithRotatedOTK(synchronizerId: SynchronizerId): Future[Unit] =
    for {
      _ <- nodeInitializer.rotateLocalCantonNodesOTKIfNeeded(
        identifierName,
        ParticipantId.apply,
        synchronizerId,
      )
    } yield {
      logger.info(s"Participant $identifierName has signed keys")
    }
}
