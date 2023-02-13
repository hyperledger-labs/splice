package com.daml.network.sv.admin.http

import com.daml.network.codegen.java.{cc, cn}
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.http.v0.{definitions, sv as v0}
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.sv.SvApp
import com.daml.network.sv.store.{SvSvStore, SvSvcStore}
import com.daml.network.util.Contract
import com.digitalasset.canton.lifecycle.FlagCloseable
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.{Clock, NonNegativeFiniteDuration}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.Spanning
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import java.security.SecureRandom
import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class HttpSvHandler(
    ledgerClient: CoinLedgerClient,
    svUserName: String,
    svStore: SvSvStore,
    svcStore: SvSvcStore,
    clock: Clock,
    retryProvider: CoinRetries,
    flagCloseable: FlagCloseable,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends v0.SvHandler
    with Spanning
    with NamedLogging {
  private val workflowId = this.getClass.getSimpleName
  private val ledgerConnection = ledgerClient.connection()
  private val svParty = svcStore.key.svParty
  private val svcParty = svcStore.key.svcParty

  def listOngoingValidatorOnboardings(
      respond: v0.SvResource.ListOngoingValidatorOnboardingsResponse.type
  )(): Future[v0.SvResource.ListOngoingValidatorOnboardingsResponse] = {
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

  def prepareValidatorOnboarding(respond: v0.SvResource.PrepareValidatorOnboardingResponse.type)(
      body: definitions.PrepareValidatorOnboardingRequest
  ): Future[v0.SvResource.PrepareValidatorOnboardingResponse] = {
    withNewTrace(workflowId) { implicit traceContext => _ =>
      val secret = generateRandomOnboardingSecret()
      val expiresIn = NonNegativeFiniteDuration.ofSeconds(body.expiresIn.toLong)
      SvApp
        .prepareValidatorOnboarding(secret, expiresIn, svStore, ledgerConnection, clock, logger)
        .map {
          case Left(reason) =>
            v0.SvResource.PrepareValidatorOnboardingResponseInternalServerError(
              s"Could not prepare onboarding: $reason"
            )
          case Right(()) => definitions.PrepareValidatorOnboardingResponse(secret)
        }
    }
  }

  def onboardValidator(
      respond: v0.SvResource.OnboardValidatorResponse.type
  )(body: definitions.OnboardValidatorRequest): Future[v0.SvResource.OnboardValidatorResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      PartyId.fromProtoPrimitive(body.partyId) match {
        case Right(partyId) =>
          svStore.lookupValidatorOnboardingBySecretWithOffset(body.secret).flatMap {
            case QueryResult(_, None) =>
              svStore.lookupUsedSecret(body.secret).map {
                case QueryResult(_, Some(_)) =>
                  v0.SvResource
                    .OnboardValidatorResponseUnauthorized(s"Secret has already been used.")
                case QueryResult(_, None) =>
                  v0.SvResource.OnboardValidatorResponseUnauthorized("Unknown secret.")
              }
            case QueryResult(_, Some(vo)) =>
              for {
                // We retry here because this mutates the CoinRules and rounds contracts,
                // which can lead to races.
                _ <- retryProvider.retryForAutomation(
                  "onboard validator via SvcRules",
                  onboardValidator(partyId, body.secret, vo),
                  flagCloseable,
                )
              } yield v0.SvResource.OnboardValidatorResponseOK
          }
        case Left(error) =>
          Future.successful(v0.SvResource.OnboardValidatorResponseBadRequest(error))
      }
    }

  def getDebugInfo(
      respond: v0.SvResource.GetDebugInfoResponse.type
  )(): Future[v0.SvResource.GetDebugInfoResponse] =
    withNewTrace(workflowId) { _ => _ =>
      for {
        coinRules <- svcStore.getCoinRules()
        svcRules <- svcStore.getSvcRules()
      } yield definitions.GetDebugInfoResponse(
        svUser = svUserName,
        svPartyId = svParty.toProtoPrimitive,
        svcPartyId = svcParty.toProtoPrimitive,
        coinRulesContractId = coinRules.contractId.toString,
        svcRulesContractId = svcRules.contractId.toString,
      )
    }

  def listConnectedDomains(
      respond: v0.SvResource.ListConnectedDomainsResponse.type
  )(): Future[v0.SvResource.ListConnectedDomainsResponse] = {
    withNewTrace(workflowId) { _ => span =>
      for {
        // both stores are typically (no hard guarantees) connected to the same domains
        domains <- svcStore.domains.listConnectedDomains()
      } yield v0.SvResource.ListConnectedDomainsResponse.OK(
        definitions.ListConnectedDomainsResponse(
          domains.view.map { case (k, v) =>
            k.toProtoPrimitive -> v.toProtoPrimitive
          }.toMap
        )
      )
    }
  }

  private def generateRandomOnboardingSecret(): String = {
    val rng = new SecureRandom();
    // 256 bits of entropy
    val bytes = new Array[Byte](32)
    rng.nextBytes(bytes)
    Base64.getEncoder().encodeToString(bytes)
  }

  private def onboardValidator(
      candidateParty: PartyId,
      secret: String,
      validatorOnboarding: Contract[
        cn.validatoronboarding.ValidatorOnboarding.ContractId,
        cn.validatoronboarding.ValidatorOnboarding,
      ],
  )(implicit traceContext: TraceContext): Future[Unit] =
    for {
      domainId <- svcStore.domains.getUniqueDomainId()
      openMiningRounds <- svcStore.acs.listContracts(cc.round.OpenMiningRound.COMPANION)
      issuingMiningRounds <- svcStore.acs.listContracts(cc.round.IssuingMiningRound.COMPANION)
      coinRules <- svcStore.getCoinRules()
      svcRules <- svcStore.getSvcRules()
      cmds = (
        svcRules.contractId
          .exerciseSvcRules_OnboardValidator(
            svParty.toProtoPrimitive,
            candidateParty.toProtoPrimitive,
            coinRules.contractId,
            openMiningRounds.map(_.contractId).asJava,
            issuingMiningRounds.map(_.contractId).asJava,
          )
          .commands
          .asScala ++ validatorOnboarding.contractId
          .exerciseValidatorOnboarding_Match(secret, candidateParty.toProtoPrimitive)
          .commands
          .asScala
      ).toSeq
      // No command-dedup required, as the CoinRules contract is archived and recreated.
      _ <- ledgerConnection.submitCommandsNoDedup(
        Seq(svParty),
        Seq(svcParty),
        cmds,
        domainId,
      )
    } yield ()
}
