// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.validator.admin.http

import com.daml.network.environment.{ParticipantAdminConnection}
import com.daml.network.http.v0.{app_manager_public as v0, definitions}
import com.daml.network.validator.config.AppManagerConfig
import com.daml.network.validator.store.AppManagerStore
import com.daml.network.validator.util.OAuth2Manager
import com.digitalasset.canton.crypto.Hash
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.Spanning
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import java.util.Base64

class HttpAppManagerPublicHandler(
    config: AppManagerConfig,
    participantAdminConnection: ParticipantAdminConnection,
    store: AppManagerStore,
    oauth2Manager: OAuth2Manager,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends v0.AppManagerPublicHandler[Unit]
    with Spanning
    with NamedLogging {

  private val workflowId = this.getClass.getSimpleName

  def oauth2Jwks(
      respond: v0.AppManagerPublicResource.Oauth2JwksResponse.type
  )()(extracted: Unit): scala.concurrent.Future[v0.AppManagerPublicResource.Oauth2JwksResponse] =
    withNewTrace(workflowId) { _ => _ =>
      Future.successful(
        definitions.JwksResponse(
          Vector(
            oauth2Manager.jwk
          )
        )
      )
    }
  def oauth2OpenIdConfiguration(
      respond: v0.AppManagerPublicResource.Oauth2OpenIdConfigurationResponse.type
  )()(extracted: Unit): Future[v0.AppManagerPublicResource.Oauth2OpenIdConfigurationResponse] =
    withNewTrace(workflowId) { _ => _ =>
      Future.successful(
        definitions.OpenIdConfigurationResponse(
          issuer = config.issuerUrl.toString,
          authorizationEndpoint = config.authorizationEndpoint.toString,
          tokenEndpoint = config.tokenEndpoint.toString,
          jwksUri = config.jwksUri.toString,
        )
      )
    }
  def oauth2Token(
      respond: v0.AppManagerPublicResource.Oauth2TokenResponse.type
  )(grantType: String, code: String, redirectUri: String, clientId: String)(
      extracted: Unit
  ): Future[v0.AppManagerPublicResource.Oauth2TokenResponse] =
    withNewTrace(workflowId) { _ => _ =>
      oauth2Manager.getJwt(code) match {
        case None =>
          Future.successful(
            v0.AppManagerPublicResource.Oauth2TokenResponse.BadRequest(
              definitions.ErrorResponse("invalid_grant")
            )
          )
        case Some(jwt) =>
          Future.successful(
            v0.AppManagerPublicResource.Oauth2TokenResponse.OK(
              definitions.TokenResponse(
                accessToken = jwt,
                tokenType = "bearer",
              )
            )
          )
      }
    }

  def getDarFile(
      respond: v0.AppManagerPublicResource.GetDarFileResponse.type
  )(darHashStr: String)(
      extracted: Unit
  ): Future[v0.AppManagerPublicResource.GetDarFileResponse] =
    withNewTrace(workflowId) { implicit tc => _ =>
      val darHash = Hash.tryFromHexString(darHashStr)
      participantAdminConnection
        .lookupDar(darHash)
        .map { darO =>
          darO.fold(
            v0.AppManagerPublicResource.GetDarFileResponse
              .NotFound(definitions.ErrorResponse(show"DAR with hash $darHash does not exist"))
          )(dar => definitions.DarFile(Base64.getEncoder().encodeToString(dar.toByteArray)))
        }
    }

  def getLatestAppConfiguration(
      respond: v0.AppManagerPublicResource.GetLatestAppConfigurationResponse.type
  )(provider: String)(
      extracted: Unit
  ): Future[v0.AppManagerPublicResource.GetLatestAppConfigurationResponse] =
    withNewTrace(workflowId) { implicit tc => _ =>
      store.getLatestAppConfiguration(PartyId.tryFromProtoPrimitive(provider)).map(_.toHttp)
    }

  override def getLatestAppConfigurationByName(
      respond: v0.AppManagerPublicResource.GetLatestAppConfigurationByNameResponse.type
  )(
      name: String
  )(
      extracted: Unit
  ): Future[v0.AppManagerPublicResource.GetLatestAppConfigurationByNameResponse] = {
    withNewTrace(workflowId) { implicit tc => _ =>
      store
        .lookupLatestAppConfigurationByName(name)
        .map(
          _.fold[v0.AppManagerPublicResource.GetLatestAppConfigurationByNameResponse](
            v0.AppManagerPublicResource.GetLatestAppConfigurationByNameResponseNotFound(
              definitions.ErrorResponse(s"Could not found AppConfiguration with name $name")
            )
          )(config =>
            v0.AppManagerPublicResource.GetLatestAppConfigurationByNameResponseOK(config.toHttp)
          )
        )
    }
  }

  def getAppRelease(respond: v0.AppManagerPublicResource.GetAppReleaseResponse.type)(
      provider: String,
      version: String,
  )(extracted: Unit): Future[v0.AppManagerPublicResource.GetAppReleaseResponse] =
    withNewTrace(workflowId) { implicit tc => _ =>
      store.getAppRelease(PartyId.tryFromProtoPrimitive(provider), version).map(_.toHttp)
    }
}
