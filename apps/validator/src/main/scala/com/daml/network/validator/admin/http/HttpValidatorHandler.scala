package com.daml.network.validator.admin.http

import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.http.v0.{definitions, validator as v0}
import com.daml.network.validator.store.ValidatorStore
import com.daml.network.validator.util.ValidatorUtil
import com.digitalasset.canton.lifecycle.FlagCloseable
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.circe.Json
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class HttpValidatorHandler(
    ledgerClient: CoinLedgerClient,
    store: ValidatorStore,
    validatorUserName: String,
    walletServiceUser: String,
    domainId: DomainId,
    retryProvider: CoinRetries,
    flagCloseable: FlagCloseable,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends v0.ValidatorHandler[String]
    with Spanning
    with NamedLogging {
  private val workflowId = this.getClass.getSimpleName
  private val connection = ledgerClient.connection()

  def onboardUser(
      respond: v0.ValidatorResource.OnboardUserResponse.type
  )(
      body: definitions.OnboardUserRequest
  )(ledgerApiUser: String): Future[v0.ValidatorResource.OnboardUserResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      val name = body.name
      span.setAttribute("name", name)
      onboard(name).map(p => definitions.OnboardUserResponse(p))
    }

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

  def listConnectedDomains(
      respond: v0.ValidatorResource.ListConnectedDomainsResponse.type
  )()(ledgerApiUser: String): Future[v0.ValidatorResource.ListConnectedDomainsResponse] = {
    withNewTrace(workflowId) { _ => span =>
      for {
        domains <- store.domains.listConnectedDomains()
      } yield v0.ValidatorResource.ListConnectedDomainsResponse.OK(
        definitions.ListConnectedDomainsResponse(
          domains.view.map { case (k, v) =>
            k.toProtoPrimitive -> v.toProtoPrimitive
          }.toMap
        )
      )
    }
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
        flagCloseable,
        logger,
      )
      .map(p => p.filterString)
  }
}
