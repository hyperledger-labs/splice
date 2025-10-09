// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.admin.http

import org.lfdecentralizedtrust.splice.admin.http.HttpErrorHandler
import org.lfdecentralizedtrust.splice.environment.ParticipantAdminConnection
import org.lfdecentralizedtrust.splice.http.v0.{definitions, sv_admin as v0}
import org.lfdecentralizedtrust.splice.http.v0.sv_admin.SvAdminResource as r0
import org.lfdecentralizedtrust.splice.http.v0.definitions.TriggerDomainMigrationDumpRequest
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig
import org.lfdecentralizedtrust.splice.sv.migration.{
  DomainDataSnapshotGenerator,
  DomainMigrationDump,
  SynchronizerNodeIdentities,
}
import org.lfdecentralizedtrust.splice.sv.store.{SvDsoStore, SvSvStore}
import org.lfdecentralizedtrust.splice.sv.LocalSynchronizerNode

import org.lfdecentralizedtrust.splice.util.{BackupDump, Codec, SynchronizerMigrationUtil}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.Spanning
import io.circe.syntax.EncoderOps
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.auth.AdminAuthExtractor.AdminUserRequest
import org.lfdecentralizedtrust.splice.migration.ParticipantUsersDataExporter
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority

import java.nio.file.Path
import java.time.Instant
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.OptionConverters.*

class HttpSvAdminHandler(
    config: SvAppBackendConfig,
    optDomainMigrationDumpConfig: Option[Path],
    svStoreWithIngestion: AppStoreWithIngestion[SvSvStore],
    dsoStoreWithIngestion: AppStoreWithIngestion[SvDsoStore],
    localSynchronizerNode: Option[LocalSynchronizerNode],
    participantAdminConnection: ParticipantAdminConnection,
    domainDataSnapshotGenerator: DomainDataSnapshotGenerator,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    protected val tracer: Tracer,
) extends v0.SvAdminHandler[AdminUserRequest]
    with Spanning
    with NamedLogging {

  protected val workflowId: String = this.getClass.getSimpleName
  private val dsoStore = dsoStoreWithIngestion.store

  override def pauseDecentralizedSynchronizer(
      respond: r0.PauseDecentralizedSynchronizerResponse.type
  )()(
      extracted: AdminUserRequest
  ): Future[r0.PauseDecentralizedSynchronizerResponse] = {
    implicit val AdminUserRequest(traceContext) = extracted
    withSpan(s"$workflowId.pauseDecentralizedSynchronizer") { _ => _ =>
      for {
        decentralizedSynchronizer <- dsoStore.getDsoRules().map(_.domain)
        _ <- SynchronizerMigrationUtil.ensureSynchronizerIsPaused(
          participantAdminConnection,
          decentralizedSynchronizer,
        )
      } yield r0.PauseDecentralizedSynchronizerResponseOK
    }
  }

  override def unpauseDecentralizedSynchronizer(
      respond: r0.UnpauseDecentralizedSynchronizerResponse.type
  )()(
      extracted: AdminUserRequest
  ): Future[r0.UnpauseDecentralizedSynchronizerResponse] = {
    implicit val AdminUserRequest(traceContext) = extracted
    withSpan(s"$workflowId.unpauseDecentralizedSynchronizer") { _ => _ =>
      for {
        decentralizedSynchronizer <- dsoStore.getDsoRules().map(_.domain)
        _ <- SynchronizerMigrationUtil.ensureSynchronizerIsUnpaused(
          participantAdminConnection,
          decentralizedSynchronizer,
        )
      } yield r0.UnpauseDecentralizedSynchronizerResponseOK
    }
  }

  override def getDomainMigrationDump(
      respond: r0.GetDomainMigrationDumpResponse.type
  )()(extracted: AdminUserRequest): Future[r0.GetDomainMigrationDumpResponse] = {
    val AdminUserRequest(traceContext) = extracted
    withSpan(s"$workflowId.getDomainMigrationDump") { implicit tc => _ =>
      localSynchronizerNode match {
        case Some(synchronizerNode) =>
          dsoStore.getDsoRules().flatMap { dsoRules =>
            dsoRules.payload.config.nextScheduledSynchronizerUpgrade.toScala match {
              case Some(scheduled) =>
                DomainMigrationDump
                  .getDomainMigrationDump(
                    config.domains.global.alias,
                    svStoreWithIngestion.connection(SpliceLedgerConnectionPriority.Medium),
                    participantAdminConnection,
                    synchronizerNode,
                    loggerFactory,
                    dsoStore,
                    scheduled.migrationId,
                    domainDataSnapshotGenerator,
                  )
                  .map { response =>
                    r0.GetDomainMigrationDumpResponse.OK(response.toHttp)
                  }
              case None =>
                Future.failed(
                  HttpErrorHandler.internalServerError(
                    s"Could not get DomainMigrationDump because migration is not scheduled"
                  )
                )
            }
          }
        case None =>
          Future.failed(
            HttpErrorHandler.internalServerError(
              s"Could not prepare DomainMigrationDump because domain node is not configured"
            )
          )
      }
    }(traceContext, tracer)
  }

  override def getDomainDataSnapshot(respond: r0.GetDomainDataSnapshotResponse.type)(
      timestamp: String,
      partyId: Option[String],
      migrationId: Option[Long],
      force: Option[Boolean],
  )(
      tuser: AdminUserRequest
  ): Future[r0.GetDomainDataSnapshotResponse] = {
    val AdminUserRequest(traceContext) = tuser
    withSpan(s"$workflowId.getDomainDataSnapshot") { implicit tc => _ =>
      for {
        participantUsersData <- new ParticipantUsersDataExporter(
          svStoreWithIngestion.connection(SpliceLedgerConnectionPriority.Medium)
        )
          .exportParticipantUsersData()
      } yield domainDataSnapshotGenerator
        .getDomainDataSnapshot(
          Instant.parse(timestamp),
          partyId.map(Codec.tryDecode(Codec.Party)(_)),
          force.getOrElse(false),
        )
        .map { response =>
          val responseHttp = response.toHttp
          r0.GetDomainDataSnapshotResponse.OK(
            definitions
              .GetDomainDataSnapshotResponse(
                responseHttp.acsTimestamp,
                migrationId getOrElse (config.domainMigrationId + 1),
                responseHttp,
                participantUsersData.toHttp,
              )
          )
        }
    }(traceContext, tracer)
  }.flatten

  override def getSynchronizerNodeIdentitiesDump(
      respond: r0.GetSynchronizerNodeIdentitiesDumpResponse.type
  )()(
      tuser: AdminUserRequest
  ): Future[r0.GetSynchronizerNodeIdentitiesDumpResponse] = {
    val AdminUserRequest(traceContext) = tuser
    withSpan(s"$workflowId.getSynchronizerNodeIdentitiesDump") { implicit tc => _ =>
      localSynchronizerNode match {
        case Some(synchronizerNode) =>
          SynchronizerNodeIdentities
            .getSynchronizerNodeIdentities(
              participantAdminConnection,
              synchronizerNode,
              dsoStore,
              config.domains.global.alias,
              loggerFactory,
            )
            .map { response =>
              r0.GetSynchronizerNodeIdentitiesDumpResponse.OK(
                definitions.GetSynchronizerNodeIdentitiesDumpResponse(response.toHttp())
              )
            }
        case None =>
          Future.failed(
            HttpErrorHandler.internalServerError(
              s"Could not prepare SynchronizerNodeIdentitiesDump because domain node is not configured"
            )
          )
      }
    }(traceContext, tracer)
  }

  override def triggerDomainMigrationDump(
      respond: r0.TriggerDomainMigrationDumpResponse.type
  )(
      request: TriggerDomainMigrationDumpRequest
  )(extracted: AdminUserRequest): Future[r0.TriggerDomainMigrationDumpResponse] = {
    withSpan(s"$workflowId.triggerDomainMigrationDump") { implicit tc => _ =>
      localSynchronizerNode match {
        case Some(synchronizerNode) =>
          optDomainMigrationDumpConfig match {
            case Some(dumpPath) =>
              for {
                dump <- DomainMigrationDump
                  .getDomainMigrationDump(
                    config.domains.global.alias,
                    svStoreWithIngestion.connection(SpliceLedgerConnectionPriority.Low),
                    participantAdminConnection,
                    synchronizerNode,
                    loggerFactory,
                    dsoStore,
                    request.migrationId,
                    domainDataSnapshotGenerator,
                  )
              } yield {
                val path = BackupDump.writeToPath(
                  dumpPath,
                  dump.asJson.noSpaces,
                )
                logger.info(s"Wrote domain migration dump at path $path")
                r0.TriggerDomainMigrationDumpResponseOK
              }
            case None =>
              Future.failed(
                HttpErrorHandler.internalServerError(
                  s"Could not trigger DomainMigrationDump because dump path is not configured"
                )
              )
          }
        case None =>
          Future.failed(
            HttpErrorHandler.internalServerError(
              s"Could not trigger DomainMigrationDump because domain node is not configured"
            )
          )
      }
    }(extracted.traceContext, tracer)
  }

}
