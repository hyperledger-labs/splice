package com.daml.network.validator.admin.http

import com.daml.network.environment.{CNLedgerClient, RetryProvider}
import com.daml.network.http.v0.{definitions, validator as v0}
import com.daml.network.validator.store.ValidatorStore
import com.daml.network.validator.util.ValidatorUtil
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.circe.Json
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class HttpValidatorHandler(
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
) extends v0.ValidatorHandler[String]
    with Spanning
    with NamedLogging {
  private val workflowId = this.getClass.getSimpleName
  private val connection = ledgerClient.connection(this.getClass.getSimpleName, loggerFactory)

  def register(
      respond: v0.ValidatorResource.RegisterResponse.type
  )(body: Option[Json])(ledgerApiUser: String): Future[v0.ValidatorResource.RegisterResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      span.setAttribute("name", ledgerApiUser)
      onboard(ledgerApiUser).map(p => definitions.RegistrationResponse(p))
    }

  def getValidatorUserInfo(
      respond: v0.ValidatorResource.GetValidatorUserInfoResponse.type
  )()(
      ledgerApiUser: String
  ): Future[v0.ValidatorResource.GetValidatorUserInfoResponse] =
    withNewTrace(workflowId) { _ => _ =>
      Future.successful(
        definitions.GetValidatorUserInfoResponse(
          store.key.validatorParty.filterString,
          validatorUserName,
        )
      )
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
}
