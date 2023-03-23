package com.daml.network.validator.admin.http

import com.daml.network.codegen.java.cn.wallet.install as walletCodegen
import com.daml.network.environment.{CNLedgerClient, RetryProvider}
import com.daml.network.http.v0.{definitions, validatorAdmin as v0}
import com.daml.network.validator.store.ValidatorStore
import com.daml.network.validator.util.ValidatorUtil
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import io.grpc.{Status, StatusRuntimeException}
import scala.jdk.CollectionConverters.*

class HttpValidatorAdminHandler(
    ledgerClient: CNLedgerClient,
    store: ValidatorStore,
    validatorUserName: String,
    walletServiceUser: String,
    domainId: DomainId,
    retryProvider: RetryProvider,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends v0.ValidatorAdminHandler[Unit]
    with Spanning
    with NamedLogging {
  private val workflowId = this.getClass.getSimpleName
  private val connection = ledgerClient.connection(this.getClass.getSimpleName, loggerFactory)

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
    withNewTrace(workflowId) { _ => _ =>
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

  private def onboard(name: String)(implicit traceContext: TraceContext): Future[String] = {
    ValidatorUtil
      .onboard(
        name,
        None,
        connection,
        store,
        validatorUserName,
        walletServiceUser,
        domainId,
        retryProvider,
        logger,
      )
      .map(p => p.filterString)
  }

  private def offboardUser(
      user: String
  )(implicit ec: ExecutionContext, traceContext: TraceContext): Future[Unit] = {
    logger.debug(s"Offboarding user: ${user}")
    for {
      acsStore <- store.defaultAcs
      install <- acsStore.findContract(walletCodegen.WalletAppInstall.COMPANION)(
        _.payload.endUserName == user
      )
      res <- install match {
        case None =>
          Future.failed(
            Status.NOT_FOUND
              .withDescription(s"No install contract found for user ${user}")
              .asRuntimeException()
          )
        case Some(c) =>
          connection.submitCommandsNoDedup(
            actAs = Seq(
              store.key.validatorParty,
              store.key.walletServiceParty,
              PartyId.tryFromProtoPrimitive(c.payload.endUserParty),
            ),
            readAs = Seq.empty,
            commands = c.contractId
              .exerciseArchive(
                new com.daml.network.codegen.java.da.internal.template.Archive()
              )
              .commands
              .asScala
              .toSeq,
            domainId = domainId,
          )
      }
    } yield res
  }
}
