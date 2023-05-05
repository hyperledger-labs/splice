package com.daml.network.sv.admin.http

import com.daml.network.admin.http.HttpErrorHandler
import com.daml.network.environment.CNLedgerClient
import com.daml.network.http.v0.{definitions, svAdmin as v0}
import com.daml.network.sv.SvApp
import com.daml.network.sv.store.{SvSvStore, SvSvcStore}
import com.daml.network.sv.util.SvUtil.generateRandomOnboardingSecret
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.Spanning
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class HttpSvAdminHandler(
    ledgerClient: CNLedgerClient,
    globalDomain: DomainId,
    svStore: SvSvStore,
    svcStore: SvSvcStore,
    clock: Clock,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends v0.SvAdminHandler[String]
    with Spanning
    with NamedLogging {
  private val workflowId = this.getClass.getSimpleName
  private val ledgerConnection = ledgerClient.connection(this.getClass.getSimpleName, loggerFactory)

  def listOngoingValidatorOnboardings(
      respond: v0.SvAdminResource.ListOngoingValidatorOnboardingsResponse.type
  )()(adminUser: String): Future[v0.SvAdminResource.ListOngoingValidatorOnboardingsResponse] = {
    withNewTrace(workflowId) { implicit traceContext => _ =>
      for {
        validatorOnboardings <- svStore.listValidatorOnboardings()
      } yield {
        definitions.ListOngoingValidatorOnboardingsResponse(
          validatorOnboardings.map(_.toJson).toVector
        )
      }
    }
  }

  def listValidatorLicenses(
      respond: v0.SvAdminResource.ListValidatorLicensesResponse.type
  )()(adminUser: String): Future[v0.SvAdminResource.ListValidatorLicensesResponse] = {
    withNewTrace(workflowId) { implicit traceContext => _ =>
      for {
        validatorLicenses <- svcStore.listValidatorLicenses()
      } yield {
        definitions.ListValidatorLicensesResponse(
          validatorLicenses.map(_.toJson).toVector
        )
      }
    }
  }

  def prepareValidatorOnboarding(
      respond: v0.SvAdminResource.PrepareValidatorOnboardingResponse.type
  )(
      body: definitions.PrepareValidatorOnboardingRequest
  )(adminUser: String): Future[v0.SvAdminResource.PrepareValidatorOnboardingResponse] = {
    withNewTrace(workflowId) { implicit traceContext => _ =>
      val secret = generateRandomOnboardingSecret()
      val expiresIn = NonNegativeFiniteDuration.ofSeconds(body.expiresIn.toLong)
      SvApp
        .prepareValidatorOnboarding(
          secret,
          expiresIn,
          svStore,
          ledgerConnection,
          globalDomain,
          clock,
          logger,
        )
        .flatMap {
          case Left(reason) =>
            Future.failed(
              HttpErrorHandler.internalServerError(s"Could not prepare onboarding: $reason")
            )
          case Right(()) =>
            Future.successful(definitions.PrepareValidatorOnboardingResponse(secret))
        }
    }
  }

  def approveSvIdentity(
      respond: v0.SvAdminResource.ApproveSvIdentityResponse.type
  )(
      body: definitions.ApproveSvIdentityRequest
  )(adminUser: String): Future[v0.SvAdminResource.ApproveSvIdentityResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      SvApp
        .approveSvIdentity(
          body.candidateName,
          body.candidateKey,
          svStore,
          ledgerConnection,
          globalDomain,
          logger,
        )
        .flatMap {
          case Left(reason) => Future.failed(HttpErrorHandler.badRequest(reason))
          case Right(()) => Future.successful(v0.SvAdminResource.ApproveSvIdentityResponseOK)
        }
    }

  def isAuthorized(
      respond: v0.SvAdminResource.IsAuthorizedResponse.type
  )(
  )(adminUser: String): Future[v0.SvAdminResource.IsAuthorizedResponse] =
    withNewTrace(workflowId) { _ => _ =>
      Future.successful(v0.SvAdminResource.IsAuthorizedResponseOK)
    }
}
