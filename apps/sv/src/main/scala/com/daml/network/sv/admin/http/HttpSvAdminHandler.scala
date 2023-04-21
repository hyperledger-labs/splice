package com.daml.network.sv.admin.http

import com.daml.network.admin.http.HttpErrorHandler
import com.daml.network.environment.CNLedgerClient
import com.daml.network.http.v0.{definitions, svAdmin as v0}
import com.daml.network.sv.SvApp
import com.daml.network.sv.store.SvSvStore
import com.daml.network.sv.util.SvUtil.generateRandomOnboardingSecret
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.Spanning
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContextExecutor, Future}

class HttpSvAdminHandler(
    ledgerClient: CNLedgerClient,
    globalDomain: DomainId,
    svStore: SvSvStore,
    clock: Clock,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    tracer: Tracer,
) extends v0.SvAdminHandler
    with Spanning
    with NamedLogging {
  private val workflowId = this.getClass.getSimpleName
  private val ledgerConnection = ledgerClient.connection(this.getClass.getSimpleName, loggerFactory)

  def listOngoingValidatorOnboardings(
      respond: v0.SvAdminResource.ListOngoingValidatorOnboardingsResponse.type
  )(): Future[v0.SvAdminResource.ListOngoingValidatorOnboardingsResponse] = {
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

  def prepareValidatorOnboarding(
      respond: v0.SvAdminResource.PrepareValidatorOnboardingResponse.type
  )(
      body: definitions.PrepareValidatorOnboardingRequest
  ): Future[v0.SvAdminResource.PrepareValidatorOnboardingResponse] = {
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
  ): Future[v0.SvAdminResource.ApproveSvIdentityResponse] =
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
}
