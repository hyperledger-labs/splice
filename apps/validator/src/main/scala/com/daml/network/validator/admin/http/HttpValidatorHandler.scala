package com.daml.network.validator.admin.http

import com.daml.network.store.CNNodeAppStoreWithIngestion
import com.daml.network.environment.{ParticipantAdminConnection, RetryProvider}
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
    storeWithIngestion: CNNodeAppStoreWithIngestion[ValidatorStore],
    validatorUserName: String,
    domainId: DomainId,
    participantAdminConnection: ParticipantAdminConnection,
    lock: (String, () => Future[Unit]) => Future[Unit],
    retryProvider: RetryProvider,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends v0.ValidatorHandler[String]
    with Spanning
    with NamedLogging {
  private val workflowId = this.getClass.getSimpleName

  def register(
      respond: v0.ValidatorResource.RegisterResponse.type
  )(body: Option[Json])(ledgerApiUser: String): Future[v0.ValidatorResource.RegisterResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      span.setAttribute("name", ledgerApiUser)
      onboard(ledgerApiUser).map(p => definitions.RegistrationResponse(p))
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
}
