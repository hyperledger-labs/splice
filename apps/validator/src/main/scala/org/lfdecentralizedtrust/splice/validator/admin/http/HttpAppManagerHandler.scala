// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.admin.http

import org.apache.pekko.http.scaladsl.model.Uri
import org.lfdecentralizedtrust.splice.auth.AuthExtractor.TracedUser
import org.lfdecentralizedtrust.splice.environment.{
  BaseLedgerConnection,
  SpliceLedgerConnection,
  RetryFor,
}
import org.lfdecentralizedtrust.splice.http.v0.{definitions, app_manager as v0}
import org.lfdecentralizedtrust.splice.validator.config.AppManagerConfig
import org.lfdecentralizedtrust.splice.validator.store.AppManagerStore
import org.lfdecentralizedtrust.splice.validator.store.AppManagerStore.AppConfiguration
import org.lfdecentralizedtrust.splice.validator.util.OAuth2Manager
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class HttpAppManagerHandler(
    config: AppManagerConfig,
    ledgerConnection: SpliceLedgerConnection,
    store: AppManagerStore,
    oauth2Manager: OAuth2Manager,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends v0.AppManagerHandler[TracedUser]
    with Spanning
    with NamedLogging {

  private val workflowId = this.getClass.getSimpleName

  private val METADATA_PREFIX = "appmanager.app.network.canton.global"

  private val PROVIDER_PARTY_USER_METADATA_KEY: String =
    s"$METADATA_PREFIX/provider_party"
  // The user id on the default IAM (e.g. auth0) for which we create the per-app user in the appmanager IAM.
  private val USER_ID_USER_METADATA_KEY: String = s"$METADATA_PREFIX/user_id"

  private def perAppUser(userId: String, providerPartyId: PartyId): String =
    // TODO(#7099) This relies on pretty printing of the provider party id to
    // make the string short enough that it satisfies the user id limits. Switch to
    // interned provider party id instead.
    // We put the providerParty first to make sure that we can use `.` as a safe separator.
    // Note that the pretty printing does currently produce ... at the end but since those are always there
    // there is still no possibility of mixing up the separator.
    // TODO(#7092) Consider if we also need to intern userId to avoid exceeding length limits if userId is already close to the max user id length.
    s"app.$providerPartyId.$userId"

  def authorizeApp(
      respond: v0.AppManagerResource.AuthorizeAppResponse.type
  )(provider: String)(
      tuser: TracedUser
  ): Future[v0.AppManagerResource.AuthorizeAppResponse] = {
    implicit val TracedUser(userId, traceContext) = tuser
    withSpan(s"$workflowId.authorizeApp") { _ => _ =>
      val providerPartyId = PartyId.tryFromProtoPrimitive(provider)
      checkAppExists(providerPartyId)(
        v0.AppManagerResource.AuthorizeAppResponse.NotFound(_)
      ) { _ =>
        for {
          primaryParty <- ledgerConnection.getPrimaryParty(userId)
          appUserId = perAppUser(userId, providerPartyId)
          _ <- ledgerConnection.createUserWithPrimaryParty(
            appUserId,
            primaryParty,
            Seq.empty,
            Some(BaseLedgerConnection.APP_MANAGER_IDENTITY_PROVIDER_ID),
          )
          _ <- ledgerConnection.ensureUserMetadataAnnotation(
            appUserId,
            Map(PROVIDER_PARTY_USER_METADATA_KEY -> provider, USER_ID_USER_METADATA_KEY -> userId),
            RetryFor.ClientCalls,
            Some(BaseLedgerConnection.APP_MANAGER_IDENTITY_PROVIDER_ID),
          )
        } yield v0.AppManagerResource.AuthorizeAppResponse.OK
      }
    }
  }

  // This is the endpoint that the app manager frontend calls after the user got redirected to /authorize
  // as part of the OAuth2 flow to confirm that they
  // authorized the app.
  def checkAppAuthorized(
      respond: v0.AppManagerResource.CheckAppAuthorizedResponse.type
  )(provider: String, redirectUri: String, state: String)(
      tuser: TracedUser
  ): Future[v0.AppManagerResource.CheckAppAuthorizedResponse] = {
    implicit val TracedUser(userId, traceContext) = tuser
    withSpan(s"$workflowId.checkAppAuthorized") { _ => _ =>
      val providerPartyId = PartyId.tryFromProtoPrimitive(provider)
      checkAppExists(providerPartyId)(
        v0.AppManagerResource.CheckAppAuthorizedResponse.Forbidden(_)
      ) { appConfiguration =>
        (for {
          appUser <- ledgerConnection
            .getUser(
              perAppUser(userId, providerPartyId),
              Some(BaseLedgerConnection.APP_MANAGER_IDENTITY_PROVIDER_ID),
            )
        } yield {
          val authorizationCode = oauth2Manager.generateAuthorizationCode(appUser.getId)
          // see: https://datatracker.ietf.org/doc/html/draft-ietf-oauth-security-topics-09#section-2.1
          if (!appConfiguration.configuration.allowedRedirectUris.contains(redirectUri)) {
            v0.AppManagerResource.CheckAppAuthorizedResponse.Forbidden(
              definitions.ErrorResponse(
                s"The provided redirectUri is not in the list of allowed redirect URIs."
              )
            )
          } else {
            // TODO(#6839) Consider just letting the frontend compute this instead of returning it here.
            v0.AppManagerResource.CheckAppAuthorizedResponse.OK(
              definitions.CheckAppAuthorizedResponse(
                Uri(redirectUri)
                  .withQuery(Uri.Query("code" -> authorizationCode, "state" -> state))
                  .toString
              )
            )
          }
        }).recover {
          case ex: StatusRuntimeException if ex.getStatus.getCode == Status.Code.NOT_FOUND =>
            v0.AppManagerResource.CheckAppAuthorizedResponse.Forbidden(
              definitions.ErrorResponse(
                s"App $provider is not authorized for $userId"
              )
            )
        }
      }
    }
  }

  private def checkAppExists[T](
      providerPartyId: PartyId
  )(
      doesNotExistF: definitions.ErrorResponse => T
  )(existsF: AppConfiguration => Future[T])(implicit tc: TraceContext) = {
    store.getLatestAppConfiguration(providerPartyId).transformWith {
      case Success(appConfiguration) =>
        existsF(appConfiguration)
      case Failure(ex: StatusRuntimeException) if ex.getStatus.getCode == Status.Code.NOT_FOUND =>
        Future.successful(
          doesNotExistF(definitions.ErrorResponse(s"App $providerPartyId does not exist."))
        )
      case Failure(ex) =>
        Future.failed(ex)
    }
  }

  def listInstalledApps(
      respond: v0.AppManagerResource.ListInstalledAppsResponse.type
  )()(tuser: TracedUser): Future[
    v0.AppManagerResource.ListInstalledAppsResponse
  ] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.listInstalledApps") { _ => _ =>
      store
        .listInstalledApps()
        .map(apps => definitions.ListInstalledAppsResponse(apps.map(_.toHttp).toVector))
    }
  }
  def listRegisteredApps(
      respond: v0.AppManagerResource.ListRegisteredAppsResponse.type
  )()(tuser: TracedUser): Future[
    v0.AppManagerResource.ListRegisteredAppsResponse
  ] = {
    implicit val TracedUser(_, traceContext) = tuser
    withSpan(s"$workflowId.listRegisteredApps") { _ => _ =>
      store
        .listRegisteredApps()
        .map(apps =>
          definitions.ListRegisteredAppsResponse(
            apps.map(_.toHttp(config.appManagerApiUrl)).toVector
          )
        )
    }
  }
}
