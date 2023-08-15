package com.daml.network.validator.admin.http

import akka.http.scaladsl.model.Uri
import com.daml.network.environment.{CNLedgerConnection}
import com.daml.network.http.v0.{appManager as v0, definitions}
import com.daml.network.validator.config.AppManagerConfig
import com.daml.network.validator.store.AppManagerStore
import com.daml.network.validator.util.OAuth2Manager
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.Spanning
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class HttpAppManagerHandler(
    config: AppManagerConfig,
    ledgerConnection: CNLedgerConnection,
    store: AppManagerStore,
    oauth2Manager: OAuth2Manager,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends v0.AppManagerHandler[String]
    with Spanning
    with NamedLogging {

  private val workflowId = this.getClass.getSimpleName

  def authorizeApp(
      respond: v0.AppManagerResource.AuthorizeAppResponse.type
  )(provider: String)(
      userId: String
  ): Future[v0.AppManagerResource.AuthorizeAppResponse] =
    withNewTrace(workflowId) { _ => _ =>
      for {
        primaryParty <- ledgerConnection.getPrimaryParty(userId)
        providerPartyId = PartyId.tryFromProtoPrimitive(provider)
        // TODO(#7099) This relies on pretty printing of the provider party id to
        // make the string short enough that it satisfies the user id limits. Switch to
        // interned provider party id instead.
        appUserId = s"app.$userId.$providerPartyId"
        _ <- ledgerConnection.createUserWithPrimaryParty(
          appUserId,
          primaryParty,
          Seq.empty,
          Some(CNLedgerConnection.APP_MANAGER_IDENTITY_PROVIDER_ID),
        )
        _ <- ledgerConnection.ensureUserMetadataAnnotation(
          appUserId,
          Map("provider" -> provider, "user" -> userId),
          Some(CNLedgerConnection.APP_MANAGER_IDENTITY_PROVIDER_ID),
        )
      } yield v0.AppManagerResource.AuthorizeAppResponse.OK
    }

  // This is the endpoint that the app manager frontend calls after the user got redirected to /authorize
  // as part of the OAuth2 flow to confirm that they
  // authorized the app.
  def checkAppAuthorized(
      respond: v0.AppManagerResource.CheckAppAuthorizedResponse.type
  )(provider: String, redirectUri: String, state: String)(
      userId: String
  ): Future[v0.AppManagerResource.CheckAppAuthorizedResponse] =
    withNewTrace(workflowId) { _ => _ =>
      // TODO(#7092) Avoid linear search across all users.
      ledgerConnection
        .findUserProto(
          user =>
            user.hasMetadata && user.getMetadata.getAnnotationsOrDefault(
              "provider",
              "",
            ) == provider && user.getMetadata.getAnnotationsOrDefault("user", "") == userId,
          Some(CNLedgerConnection.APP_MANAGER_IDENTITY_PROVIDER_ID),
        )
        .flatMap {
          case None =>
            Future.successful(
              v0.AppManagerResource.CheckAppAuthorizedResponse.Forbidden(
                definitions.ErrorResponse(
                  s"App $provider is not authorized for $userId"
                )
              )
            )
          case Some(appUser) =>
            val authorizationCode = oauth2Manager.generateAuthorizationCode(appUser.getId())
            Future.successful(
              // TODO(#6839) Consider just letting the frontend compute this instead of returning it here.
              definitions.CheckAppAuthorizedResponse(
                Uri(redirectUri)
                  .withQuery(Uri.Query("code" -> authorizationCode, "state" -> state))
                  .toString
              )
            )
        }
    }

  def listInstalledApps(
      respond: v0.AppManagerResource.ListInstalledAppsResponse.type
  )()(userId: String): Future[
    v0.AppManagerResource.ListInstalledAppsResponse
  ] =
    withNewTrace(workflowId) { implicit tc => _ =>
      store
        .listInstalledApps()
        .map(apps => definitions.ListInstalledAppsResponse(apps.map(_.toJson).toVector))
    }

  def listRegisteredApps(
      respond: v0.AppManagerResource.ListRegisteredAppsResponse.type
  )()(userId: String): Future[
    v0.AppManagerResource.ListRegisteredAppsResponse
  ] =
    withNewTrace(workflowId) { implicit tc => _ =>
      store
        .listRegisteredApps()
        .map(apps =>
          definitions.ListRegisteredAppsResponse(
            apps.map(_.toJson(config.appManagerApiUrl)).toVector
          )
        )
    }

}
