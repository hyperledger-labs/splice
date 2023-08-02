// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.setup

import cats.implicits.*
import com.digitalasset.canton.health.admin.data.NodeStatus
import com.digitalasset.canton.logging.{NamedLogging, NamedLoggerFactory}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.ParticipantId
import com.digitalasset.canton.tracing.TraceContext
import com.daml.network.config.{CNParticipantClientConfig, ParticipantBootstrapDumpConfig}
import com.daml.network.environment.{ParticipantAdminConnection, RetryProvider}
import com.daml.network.util.ParticipantIdentitiesDump
import io.grpc.Status

import java.nio.file.Path
import scala.concurrent.{ExecutionContextExecutor, Future}

/** Methods required by the SvApp and ValidatorApp to initialize their participant with existing identities */

object ParticipantInitializer {
  def ensureParticipantInitializedWithExpectedId(
      participantClientConfig: CNParticipantClientConfig,
      dumpConfig: Option[ParticipantBootstrapDumpConfig],
      loggerFactory: NamedLoggerFactory,
      retryProvider: RetryProvider,
      clock: Clock,
  )(implicit
      ec: ExecutionContextExecutor,
      tc: TraceContext,
  ): Future[Unit] = {

    val participantAdminConnection = new ParticipantAdminConnection(
      participantClientConfig.adminApi,
      loggerFactory,
      retryProvider,
      clock,
    )
    val participantInitializer = new ParticipantInitializer(
      dumpConfig,
      loggerFactory,
      retryProvider,
      participantAdminConnection,
    )
    participantInitializer
      .ensureInitializedWithExpectedId()
      .andThen(_ => participantAdminConnection.close())
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

  def ensureInitializedWithExpectedId(): Future[Unit] =
    dumpConfig match {
      case Some(c) =>
        for {
          dump <- getDump(c)
          _ <- retryProvider.ensureThatB(
            "participant is initialized",
            isInitialized(),
            initializeFromDump(dump),
            logger,
          )
          id <- getId()
          result <-
            if (id == dump.id) {
              Future.unit
            } else {
              Future.failed(
                Status.INTERNAL
                  .withDescription(s"Participant has ID $id instead of expected ID ${dump.id}.")
                  .asRuntimeException()
              )
            }
        } yield result
      case None =>
        retryProvider
          .waitUntil(
            "participant is initialized",
            isInitialized()
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

  private def isInitialized(): Future[Boolean] = participantAdminConnection.getStatus().map {
    case NodeStatus.NotInitialized(_) => false
    case _ => true
  }

  private def getId(): Future[ParticipantId] = participantAdminConnection.getParticipantId()

  private def initializeFromDump(dump: ParticipantIdentitiesDump): Future[Unit] = for {
    _ <- {
      logger.info("Uploading participant keys from dump")
      // this is idempotent
      dump.keys.traverse_(key => participantAdminConnection.importKeyPair(key.keyPair, key.name))
    }
    uploadedBootstrapTxs <- participantAdminConnection.getIdentityBootstrapTransactions(dump.id.uid)
    missingBootstrapTxs = dump.bootstrapTxs.filter(!uploadedBootstrapTxs.contains(_))
    // things blow up later in init if we upload the same tx multiple times ¯\_(ツ)_/¯
    _ <- {
      logger.info(s"Uploading ${missingBootstrapTxs.size} missing bootstrap transactions from dump")
      participantAdminConnection.addTopologyTransactions(missingBootstrapTxs)
    }
    _ <- {
      logger.info(s"Triggering participant initialization for participant ID ${dump.id}")
      participantAdminConnection.initId(dump.id)
    }
  } yield ()

  private def getDump(config: ParticipantBootstrapDumpConfig): Future[ParticipantIdentitiesDump] =
    config match {
      case ParticipantBootstrapDumpConfig.File(file) => getDumpFromFile(file)
    }

  private def getDumpFromFile(file: Path): Future[ParticipantIdentitiesDump] = {
    logger.info(s"Loading participant identities dump from file at $file")
    ParticipantIdentitiesDump.fromJsonFile(file) match {
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
