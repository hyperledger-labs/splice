// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.admin.http

import org.apache.pekko.http.scaladsl.model.ContentType
import org.apache.pekko.stream.Materializer
import cats.data.EitherT
import cats.syntax.foldable.*
import cats.syntax.traverse.*
import org.lfdecentralizedtrust.splice.auth.AuthExtractor.TracedUser
import org.lfdecentralizedtrust.splice.environment.{
  ParticipantAdminConnection,
  RetryFor,
  RetryProvider,
}
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.http.v0.{definitions, app_manager_admin as v0}
import org.lfdecentralizedtrust.splice.validator.admin.AppManagerService
import org.lfdecentralizedtrust.splice.validator.store.AppManagerStore
import org.lfdecentralizedtrust.splice.validator.util.{DarUtil, HttpUtil}
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.crypto.Hash
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.participant.domain.DomainConnectionConfig
import com.digitalasset.canton.sequencing.{GrpcSequencerConnection, SequencerConnections}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.digitalasset.canton.util.EitherTUtil
import com.digitalasset.canton.util.ShowUtil.*
import io.circe.parser.decode
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import java.io.{ByteArrayInputStream, File}
import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}

class HttpAppManagerAdminHandler(
    participantAdminConnection: ParticipantAdminConnection,
    store: AppManagerStore,
    appManagerService: AppManagerService,
    retryProvider: RetryProvider,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
    httpClient: HttpClient,
    mat: Materializer,
) extends v0.AppManagerAdminHandler[TracedUser]
    with Spanning
    with NamedLogging {

  private val workflowId = this.getClass.getSimpleName

  def registerApp(respond: v0.AppManagerAdminResource.RegisterAppResponse.type)(
      providerUserId: String,
      configuration: String,
      release: (java.io.File, Option[String], ContentType),
  )(tuser: TracedUser): Future[v0.AppManagerAdminResource.RegisterAppResponse] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.registerApp") { _ => _ =>
      decode[definitions.AppConfiguration](configuration) match {
        case Left(err) =>
          Future.successful(
            v0.AppManagerAdminResource.RegisterAppResponse.BadRequest(
              definitions.ErrorResponse(
                s"App configuration could not be decoded: $err"
              )
            )
          )
        case Right(configuration) =>
          appManagerService
            .registerApp(providerUserId, configuration, release._1, RetryFor.ClientCalls)
            .map(_ => v0.AppManagerAdminResource.RegisterAppResponse.Created)
            .recover { case _: IllegalArgumentException =>
              v0.AppManagerAdminResource.RegisterAppResponse.BadRequest(
                definitions.ErrorResponse(
                  s"Configuration version on registration must be 0 but was ${configuration.version}"
                )
              )
            }
      }
    }
  }
  def registerAppMapFileField(
      fieldName: String,
      fileName: Option[String],
      contentType: ContentType,
  ): File =
    // File is deleted by the generated code in guardrail.
    File.createTempFile("release_", ".tgz")

  def publishAppRelease(
      respond: v0.AppManagerAdminResource.PublishAppReleaseResponse.type
  )(provider: String, release: (File, Option[String], ContentType))(
      tuser: TracedUser
  ): Future[v0.AppManagerAdminResource.PublishAppReleaseResponse] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.publishAppRelease") { _ => _ =>
      val providerParty = PartyId.tryFromProtoPrimitive(provider)
      for {
        _ <- store.getLatestAppConfiguration(providerParty) // Check that app is registered
        _ <- appManagerService.storeAppRelease(providerParty, release._1, RetryFor.ClientCalls)
      } yield v0.AppManagerAdminResource.PublishAppReleaseResponse.Created
    }
  }

  def publishAppReleaseMapFileField(
      fieldName: String,
      fileName: Option[String],
      contentType: ContentType,
  ): File =
    // File is deleted by the generated code in guardrail.
    File.createTempFile("release_", ".tgz")

  def updateAppConfiguration(
      respond: v0.AppManagerAdminResource.UpdateAppConfigurationResponse.type
  )(provider: String, body: definitions.UpdateAppConfigurationRequest)(
      tuser: TracedUser
  ): Future[v0.AppManagerAdminResource.UpdateAppConfigurationResponse] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.updateAppConfiguration") { _ => _ =>
      val providerParty = PartyId.tryFromProtoPrimitive(provider)
      validateAppConfiguration(providerParty, body.configuration).flatMap {
        case Left(err) =>
          Future.successful(err)
        case Right(()) =>
          for {
            _ <- store.storeAppConfiguration(
              AppManagerStore.AppConfiguration(providerParty, body.configuration)
            )
          } yield v0.AppManagerAdminResource.UpdateAppConfigurationResponse.Created
      }
    }
  }

  private def validateAppConfiguration(
      provider: PartyId,
      configuration: definitions.AppConfiguration,
  )(implicit
      tc: TraceContext
  ): Future[Either[v0.AppManagerAdminResource.UpdateAppConfigurationResponse, Unit]] =
    (for {
      latestConfig <- EitherT.right(store.getLatestAppConfiguration(provider))
      _ <- EitherTUtil.ifThenET(latestConfig.configuration.version + 1 != configuration.version)(
        EitherT.leftT[Future, Unit](
          v0.AppManagerAdminResource.UpdateAppConfigurationResponse.Conflict(
            definitions.ErrorResponse(
              show"Tried to update configuration to version ${configuration.version} but previous config was version ${latestConfig.configuration.version}"
            )
          )
        )
      )
      _ <- configuration.releaseConfigurations.traverse { config =>
        EitherT.fromOptionF(
          store.lookupAppRelease(provider, config.releaseVersion),
          v0.AppManagerAdminResource.UpdateAppConfigurationResponse.BadRequest(
            definitions.ErrorResponse(
              show"Release configuration references release version ${config.releaseVersion} but release with that version has not been uploaded"
            )
          ),
        )
      }
    } yield ()).value

  def installApp(respond: v0.AppManagerAdminResource.InstallAppResponse.type)(
      body: definitions.InstallAppRequest
  )(tuser: TracedUser): Future[v0.AppManagerAdminResource.InstallAppResponse] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.installApp") { _ => _ =>
      val appUrl = AppManagerStore.AppUrl(body.appUrl)
      for {
        _ <- appManagerService.installApp(appUrl)
      } yield v0.AppManagerAdminResource.InstallAppResponse.Created
    }
  }
  def approveAppReleaseConfiguration(
      respond: v0.AppManagerAdminResource.ApproveAppReleaseConfigurationResponse.type
  )(provider: String, body: definitions.ApproveAppReleaseConfigurationRequest)(
      tuser: TracedUser
  ): Future[v0.AppManagerAdminResource.ApproveAppReleaseConfigurationResponse] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.approveAppReleaseConfiguration") { _ => _ =>
      val providerParty = PartyId.tryFromProtoPrimitive(provider)
      for {
        config <- store.getAppConfiguration(providerParty, body.configurationVersion)
        releaseConfig =
          config.configuration.releaseConfigurations
            .lift(body.releaseConfigurationIndex)
            .getOrElse(
              throw Status.NOT_FOUND
                .withDescription(
                  show"App $providerParty has only ${config.configuration.releaseConfigurations.length} release configs but tried to approve release config at index ${body.releaseConfigurationIndex}"
                )
                .asRuntimeException
            )
        release <-
          store.getAppRelease(providerParty, releaseConfig.releaseVersion)
        // TODO(#7206) Consider switching this to a reconciliation trigger to avoid
        // partial changes where we change domain config/dars but don't store the ApprovedReleaseConfiguration.
        _ <- releaseConfig.domains.traverse_ { domain =>
          participantAdminConnection.ensureDomainRegisteredAndConnected(
            DomainConnectionConfig(
              // TODO(#6839) Fix the alias here, we can't assume that app providers use distinct aliases.
              DomainAlias.tryCreate(domain.alias),
              SequencerConnections.single(GrpcSequencerConnection.tryCreate(domain.url)),
            ),
            RetryFor.ClientCalls,
          )
        }
        appUrl <- store.getInstalledAppUrl(providerParty)
        _ <- release.release.darHashes.traverse_(ensureDar(appUrl, _))
        _ <- store.storeApprovedReleaseConfiguration(
          AppManagerStore.ApprovedReleaseConfiguration(
            providerParty,
            body.configurationVersion,
            releaseConfig,
          )
        )
      } yield v0.AppManagerAdminResource.ApproveAppReleaseConfigurationResponse.OK
    }
  }

  private def ensureDar(
      appUrl: AppManagerStore.AppUrl,
      darHash: String,
  )(implicit
      tc: TraceContext
  ): Future[Unit] =
    retryProvider.ensureThatO(
      RetryFor.ClientCalls,
      "dar_upload",
      show"DAR $darHash is uploaded",
      participantAdminConnection.lookupDar(Hash.tryFromHexString(darHash)).map(_.map(_ => ())),
      for {
        darResponse <- HttpUtil.getHttpJson[definitions.DarFile](appUrl.darUrl(darHash))
        dar = DarUtil
          .readDar(
            darHash,
            new ByteArrayInputStream(Base64.getDecoder().decode(darResponse.base64Dar)),
          )
          ._1
        _ <- participantAdminConnection.uploadDarFiles(Seq(dar), RetryFor.ClientCalls)
      } yield (),
      logger,
    )
}
