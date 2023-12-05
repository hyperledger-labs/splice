package com.daml.network.validator.admin.http

import com.daml.network.auth.AuthExtractor.TracedUser
import com.daml.network.environment.{ParticipantAdminConnection, RetryProvider}
import com.daml.network.http.v0.{definitions, validator_admin as v0}
import com.daml.network.scan.admin.api.client.ScanConnection.GetCoinRulesDomain
import com.daml.network.store.CNNodeAppStoreWithIngestion
import com.daml.network.validator.store.{NodeIdentitiesStore, ValidatorStore}
import com.daml.network.validator.util.ValidatorUtil
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.grpc.StatusRuntimeException
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class HttpValidatorAdminHandler(
    storeWithIngestion: CNNodeAppStoreWithIngestion[ValidatorStore],
    identitiesStore: NodeIdentitiesStore,
    validatorUserName: String,
    validatorWalletUserName: Option[String],
    getCoinRulesDomain: GetCoinRulesDomain,
    participantAdminConnection: ParticipantAdminConnection,
    retryProvider: RetryProvider,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends v0.ValidatorAdminHandler[TracedUser]
    with Spanning
    with NamedLogging {
  private val workflowId = this.getClass.getSimpleName
  private val store = storeWithIngestion.store

  def onboardUser(
      respond: v0.ValidatorAdminResource.OnboardUserResponse.type
  )(
      body: definitions.OnboardUserRequest
  )(tuser: TracedUser): Future[v0.ValidatorAdminResource.OnboardUserResponse] = {
    implicit val TracedUser(_, tracedContext) = tuser
    withSpan(s"$workflowId.onboardUser") { _ => span =>
      val name = body.name
      span.setAttribute("name", name)
      onboard(name).map(p => definitions.OnboardUserResponse(p))
    }
  }

  def listUsers(
      respond: v0.ValidatorAdminResource.ListUsersResponse.type
  )()(tuser: TracedUser): Future[
    v0.ValidatorAdminResource.ListUsersResponse
  ] = {
    implicit val TracedUser(_, tracedContext) = tuser
    withSpan(s"$workflowId.listUsers") { _ => _ =>
      store.listUsers().map(us => definitions.ListUsersResponse(us.toVector))
    }
  }

  def offboardUser(
      respond: v0.ValidatorAdminResource.OffboardUserResponse.type
  )(username: String)(tuser: TracedUser): Future[
    v0.ValidatorAdminResource.OffboardUserResponse
  ] = {
    implicit val TracedUser(_, tracedContext) = tuser
    withSpan(s"$workflowId.offboardUser") { _ => _ =>
      offboardUser(username)
        .map(_ => v0.ValidatorAdminResource.OffboardUserResponse.OK)
        .recover({
          case e: StatusRuntimeException if e.getStatus.getCode == io.grpc.Status.Code.NOT_FOUND =>
            v0.ValidatorAdminResource
              .OffboardUserResponseNotFound(definitions.ErrorResponse(e.getMessage()))
        })
    }
  }

  def dumpParticipantIdentities(
      respond: v0.ValidatorAdminResource.DumpParticipantIdentitiesResponse.type
  )()(
      tuser: TracedUser
  ): Future[v0.ValidatorAdminResource.DumpParticipantIdentitiesResponse] = {
    implicit val TracedUser(_, tracedContext) = tuser
    withSpan(s"$workflowId.dumpParticipantIdentities") { _ => _ =>
      identitiesStore
        .getNodeIdentitiesDump()
        .map(response =>
          v0.ValidatorAdminResource.DumpParticipantIdentitiesResponse.OK(response.toHttp)
        )
    }
  }

  private def onboard(name: String)(implicit traceContext: TraceContext): Future[String] = {
    ValidatorUtil
      .onboard(
        name,
        None,
        storeWithIngestion,
        validatorUserName,
        getCoinRulesDomain,
        participantAdminConnection,
        retryProvider,
        logger,
      )
      .map(p => p.filterString)
  }

  private def offboardUser(
      user: String
  )(implicit ec: ExecutionContext, traceContext: TraceContext): Future[Unit] = {
    ValidatorUtil.offboard(
      user,
      storeWithIngestion,
      validatorUserName,
      validatorWalletUserName,
      retryProvider,
      logger,
    )
  }
}
