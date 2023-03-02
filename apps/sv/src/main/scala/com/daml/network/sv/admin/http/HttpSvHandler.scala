package com.daml.network.sv.admin.http

import com.daml.network.codegen.java.{cc, cn}
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.http.v0.{definitions, sv as v0}
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.sv.SvApp
import com.daml.network.sv.store.{SvSvStore, SvSvcStore}
import com.daml.network.util.Contract
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.{Clock, NonNegativeFiniteDuration}
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.Spanning
import io.opentelemetry.api.trace.Tracer

import java.security.SecureRandom
import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class HttpSvHandler(
    ledgerClient: CoinLedgerClient,
    globalDomain: DomainId,
    svUserName: String,
    svStore: SvSvStore,
    svcStore: SvSvcStore,
    isDevNet: Boolean,
    clock: Clock,
    retryProvider: CoinRetries,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends v0.SvHandler
    with Spanning
    with NamedLogging {
  private val workflowId = this.getClass.getSimpleName
  private val ledgerConnection = ledgerClient.connection(this.getClass.getSimpleName)
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
        .prepareValidatorOnboarding(
          secret,
          expiresIn,
          svStore,
          ledgerConnection,
          globalDomain,
          clock,
          logger,
        )
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
      // TODO(M3-46) Add check to avoid that a validator can get onboarded twice
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
                  logger,
                )
              } yield v0.SvResource.OnboardValidatorResponseOK
          }
        case Left(error) =>
          Future.successful(v0.SvResource.OnboardValidatorResponseBadRequest(error))
      }
    }

  def devNetOnboardValidatorPrepare(
      respond: v0.SvResource.DevNetOnboardValidatorPrepareResponse.type
  )(): Future[v0.SvResource.DevNetOnboardValidatorPrepareResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      if (isDevNet) {
        val secret = generateRandomOnboardingSecret()
        val expiresIn = NonNegativeFiniteDuration.ofHours(1)
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
          .map {
            case Left(reason) =>
              v0.SvResource.DevNetOnboardValidatorPrepareResponseInternalServerError(
                s"Could not prepare onboarding: $reason"
              )
            case Right(()) => v0.SvResource.DevNetOnboardValidatorPrepareResponseOK(secret)
          }
      } else {
        Future.successful(
          v0.SvResource.DevNetOnboardValidatorPrepareResponseNotImplemented(
            s"Validator onboarding preparation self-service is only available in DevNet."
          )
        )
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
  ): Future[Unit] =
    for {
      acs <- svcStore.acs(globalDomain)
      openMiningRounds <- acs.listContracts(cc.round.OpenMiningRound.COMPANION)
      issuingMiningRounds <- acs.listContracts(cc.round.IssuingMiningRound.COMPANION)
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
        globalDomain,
      )
    } yield ()
}
