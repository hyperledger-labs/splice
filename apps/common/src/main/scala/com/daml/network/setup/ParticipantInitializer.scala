// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.setup

import com.daml.network.config.ParticipantBootstrapDumpConfig
import com.daml.network.environment.{ParticipantAdminConnection, RetryFor, RetryProvider}
import com.daml.network.identities.NodeIdentitiesDump
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.{Identifier, ParticipantId, UniqueIdentifier}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status

import java.nio.file.Path
import scala.concurrent.{ExecutionContextExecutor, Future}

/** Methods required by the SvApp and ValidatorApp to initialize their participant with existing identities */

object ParticipantInitializer {
  def ensureParticipantInitializedWithExpectedId(
      participantAdminConnection: ParticipantAdminConnection,
      dumpConfig: Option[ParticipantBootstrapDumpConfig],
      loggerFactory: NamedLoggerFactory,
      retryProvider: RetryProvider,
  )(implicit
      ec: ExecutionContextExecutor,
      tc: TraceContext,
  ): Future[Unit] = {
    val participantInitializer = new ParticipantInitializer(
      dumpConfig,
      loggerFactory,
      retryProvider,
      participantAdminConnection,
    )
    participantInitializer
      .ensureInitializedWithExpectedId()
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
            ParticipantId(UniqueIdentifier(Identifier.tryCreate(id), dump.id.uid.namespace))
          )
          result <- nodeInitializer.initializeAndWait(dump, Some(newParticipantId))
        } yield result
      case None =>
        retryProvider
          .waitUntil(
            RetryFor.WaitingOnInitDependency,
            "participant_init",
            "participant is initialized",
            participantAdminConnection
              .isNodeInitialized()
              .flatMap(if (_) {
                Future.unit
              } else {
                // There doesn't seem to be a way to tell the participant to init with
                // a freshly generated identity, except by changing its config.
                Future.failed(
                  Status.FAILED_PRECONDITION
                    .withDescription(
                      "Participant is still initializing. " +
                        "Is the participant configured to auto-init? " +
                        "(You didn't specify a participant bootstrapping config.)"
                    )
                    .asRuntimeException()
                )
              }),
            logger,
          )
    }
}
