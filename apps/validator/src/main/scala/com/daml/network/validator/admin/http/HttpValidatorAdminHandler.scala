package com.daml.network.validator.admin.http

import cats.syntax.traverse.*
import com.daml.network.store.CNNodeAppStoreWithIngestion
import com.daml.network.environment.{ParticipantAdminConnection, RetryProvider}
import com.daml.network.http.v0.{definitions, validatorAdmin as v0}
import com.daml.network.validator.store.ValidatorStore
import com.daml.network.validator.util.ValidatorUtil
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.grpc.StatusRuntimeException
import io.opentelemetry.api.trace.Tracer

import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.OptionConverters.*

class HttpValidatorAdminHandler(
    storeWithIngestion: CNNodeAppStoreWithIngestion[ValidatorStore],
    validatorUserName: String,
    validatorWalletUserName: Option[String],
    domainId: DomainId,
    participantAdminConnection: ParticipantAdminConnection,
    lock: (String, () => Future[Unit]) => Future[Unit],
    retryProvider: RetryProvider,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends v0.ValidatorAdminHandler[Unit]
    with Spanning
    with NamedLogging {
  private val workflowId = this.getClass.getSimpleName
  private val store = storeWithIngestion.store

  def onboardUser(
      respond: v0.ValidatorAdminResource.OnboardUserResponse.type
  )(
      body: definitions.OnboardUserRequest
  )(fake: Unit): Future[v0.ValidatorAdminResource.OnboardUserResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      val name = body.name
      span.setAttribute("name", name)
      onboard(name).map(p => definitions.OnboardUserResponse(p))
    }

  def listUsers(
      respond: v0.ValidatorAdminResource.ListUsersResponse.type
  )()(fake: Unit): Future[
    v0.ValidatorAdminResource.ListUsersResponse
  ] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      store.listUsers().map(us => definitions.ListUsersResponse(us.toVector))
    }

  def offboardUser(
      respond: v0.ValidatorAdminResource.OffboardUserResponse.type
  )(username: String)(fake: Unit): Future[
    v0.ValidatorAdminResource.OffboardUserResponse
  ] = withNewTrace(workflowId) { implicit traceContext => _ =>
    offboardUser(username)
      .map(_ => v0.ValidatorAdminResource.OffboardUserResponse.OK)
      .recover({
        case e: StatusRuntimeException if e.getStatus.getCode == io.grpc.Status.Code.NOT_FOUND =>
          v0.ValidatorAdminResource
            .OffboardUserResponseNotFound(definitions.ErrorResponse(e.getMessage()))
      })
  }

  def dumpParticipantIdentities(
      respond: v0.ValidatorAdminResource.DumpParticipantIdentitiesResponse.type
  )()(
      fake: Unit
  ): scala.concurrent.Future[v0.ValidatorAdminResource.DumpParticipantIdentitiesResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      for {
        id <- participantAdminConnection.getParticipantId()
        keysMetadata <- participantAdminConnection.listMyKeys()
        keys <- keysMetadata.traverse(keyM =>
          participantAdminConnection
            .exportKeyPair(keyM.publicKeyWithName.publicKey.id)
            .map(keyBytes =>
              definitions.ParticipantKeyPair(
                Base64.getEncoder().encodeToString(keyBytes.toByteArray),
                keyM.publicKeyWithName.name.map(_.toString),
              )
            )
        )
        bootstrapTxs <- participantAdminConnection
          .getIdentityBootstrapTransactions(id.uid)
          .map(txes => txes.map(tx => Base64.getEncoder().encodeToString(tx.toByteArray)))
        users <- storeWithIngestion.connection
          .listUsers()
          .map(users =>
            users.map(user =>
              definitions.ParticipantUser(
                user.getId(),
                user.getPrimaryParty().toScala,
              )
            )
          )
      } yield definitions.DumpParticipantIdentitiesResponse(
        id.toProtoPrimitive,
        keys.toVector,
        bootstrapTxs.toVector,
        users.toVector,
      )
    }

  private def onboard(name: String)(implicit traceContext: TraceContext): Future[String] = {
    ValidatorUtil
      .onboard(
        name,
        None,
        storeWithIngestion,
        validatorUserName,
        domainId,
        participantAdminConnection,
        lock,
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
      domainId,
      retryProvider,
      logger,
    )
  }
}
