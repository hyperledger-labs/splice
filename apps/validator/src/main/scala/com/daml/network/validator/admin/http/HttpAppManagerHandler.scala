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
import io.grpc.{Status, StatusRuntimeException}
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
      userId: String
  ): Future[v0.AppManagerResource.AuthorizeAppResponse] =
    withNewTrace(workflowId) { _ => _ =>
      for {
        primaryParty <- ledgerConnection.getPrimaryParty(userId)
        providerPartyId = PartyId.tryFromProtoPrimitive(provider)
        appUserId = perAppUser(userId, providerPartyId)
        _ <- ledgerConnection.createUserWithPrimaryParty(
          appUserId,
          primaryParty,
          Seq.empty,
          Some(CNLedgerConnection.APP_MANAGER_IDENTITY_PROVIDER_ID),
        )
        _ <- ledgerConnection.ensureUserMetadataAnnotation(
          appUserId,
          Map(PROVIDER_PARTY_USER_METADATA_KEY -> provider, USER_ID_USER_METADATA_KEY -> userId),
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
      ledgerConnection
        .getUser(
          perAppUser(userId, PartyId.tryFromProtoPrimitive(provider)),
          Some(CNLedgerConnection.APP_MANAGER_IDENTITY_PROVIDER_ID),
        )
        .map { appUser =>
          val authorizationCode = oauth2Manager.generateAuthorizationCode(appUser.getId)
          // TODO(#6839) Consider just letting the frontend compute this instead of returning it here.
          v0.AppManagerResource.CheckAppAuthorizedResponse.OK(
            definitions.CheckAppAuthorizedResponse(
              Uri(redirectUri)
                .withQuery(Uri.Query("code" -> authorizationCode, "state" -> state))
                .toString
            )
          )
        }
        .recover {
          case ex: StatusRuntimeException if ex.getStatus.getCode == Status.Code.NOT_FOUND =>
            v0.AppManagerResource.CheckAppAuthorizedResponse.Forbidden(
              definitions.ErrorResponse(
                s"App $provider is not authorized for $userId"
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
        .map(apps => definitions.ListInstalledAppsResponse(apps.map(_.toHttp).toVector))
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
            apps.map(_.toHttp(config.appManagerApiUrl)).toVector
          )
        )
    }
}
