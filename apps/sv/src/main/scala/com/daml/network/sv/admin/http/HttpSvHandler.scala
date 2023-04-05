package com.daml.network.sv.admin.http

import cats.data.OptionT
import com.daml.network.environment.ParticipantAdminConnection
import com.daml.network.codegen.java.cn
import com.daml.network.environment.{CNLedgerClient, CNLedgerConnection, RetryProvider}
import com.daml.network.http.v0.{definitions, sv as v0}
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.sv.{SvApp, SvcPartyHosting}
import com.daml.network.sv.store.{SvSvStore, SvSvcStore}
import com.daml.network.sv.util.{SvOnboardingToken, SvUtil}
import com.daml.network.util.{Codec, Contract}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{DomainId, ParticipantId, PartyId}
import com.digitalasset.canton.tracing.Spanning
import io.opentelemetry.api.trace.Tracer
import com.digitalasset.canton.topology.transaction.RequestSide

import java.security.SecureRandom
import java.util.Base64
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters.*

class HttpSvHandler(
    ledgerClient: CNLedgerClient,
    globalDomain: DomainId,
    svUserName: String,
    svStore: SvSvStore,
    svcStore: SvSvcStore,
    isDevNet: Boolean,
    clock: Clock,
    participantAdminConnection: ParticipantAdminConnection,
    retryProvider: RetryProvider,
    svcPartyHosting: SvcPartyHosting,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    tracer: Tracer,
) extends v0.SvHandler
    with Spanning
    with NamedLogging {
  private val workflowId = this.getClass.getSimpleName
  private val ledgerConnection = ledgerClient.connection(this.getClass.getSimpleName, loggerFactory)
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
      PartyId.fromProtoPrimitive(body.partyId) match {
        case Right(partyId) =>
          svStore.lookupValidatorOnboardingBySecret(body.secret).flatMap {
            case None =>
              svStore.lookupUsedSecret(body.secret).map {
                case Some(_) =>
                  v0.SvResource
                    .OnboardValidatorResponseUnauthorized(s"Secret has already been used.")
                case None =>
                  v0.SvResource.OnboardValidatorResponseUnauthorized("Unknown secret.")
              }
            case Some(vo) =>
              for {
                // We retry here because this mutates the CoinRules and rounds contracts,
                // which can lead to races.
                _ <- retryProvider.retryForClientCalls(
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

  def approveSvIdentity(
      respond: v0.SvResource.ApproveSvIdentityResponse.type
  )(body: definitions.ApproveSvIdentityRequest): Future[v0.SvResource.ApproveSvIdentityResponse] =
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
        .map {
          case Left(reason) =>
            v0.SvResource.ApproveSvIdentityResponseBadRequest(
              s"Bad request: $reason"
            )
          case Right(()) => v0.SvResource.ApproveSvIdentityResponseOK
        }
    }

  def onboardSv(
      respond: v0.SvResource.OnboardSvResponse.type
  )(body: definitions.OnboardSvRequest): Future[v0.SvResource.OnboardSvResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      SvOnboardingToken.verifyAndDecode(body.token) match {
        case Left(error) =>
          Future.successful(
            v0.SvResource.OnboardSvResponseBadRequest(s"Could not verify and decode token: $error")
          )
        case Right(token) =>
          SvApp
            .isApprovedSvIdentity(token.candidateName, token.candidateParty, body.token, svStore)
            .flatMap {
              case Left(reason) =>
                Future.successful(
                  v0.SvResource
                    .OnboardSvResponseUnauthorized(
                      s"Could not authorize onboarding request because of reason: $reason"
                    )
                )
              case Right(_) =>
                // We retry here because the SvcRules can change while attempting this.
                for {
                  outcome <- retryProvider.retryForClientCalls(
                    "start SV onboarding via SvcRules",
                    startSvOnboarding(token.candidateName, token.candidateParty, body.token),
                    logger,
                  )
                } yield outcome match {
                  case Left(reason) =>
                    v0.SvResource.OnboardSvResponseBadRequest(
                      s"Could not start onboarding: $reason"
                    )
                  case Right(()) => v0.SvResource.OnboardSvResponseOK
                }
            }
      }
    }

  def getSvOnboardingStatus(
      respond: v0.SvResource.GetSvOnboardingStatusResponse.type
  )(svPartyIdS: String): Future[v0.SvResource.GetSvOnboardingStatusResponse] = {

    PartyId.fromProtoPrimitive(svPartyIdS) match {
      case Left(error) =>
        Future.successful(
          v0.SvResource.GetSvOnboardingStatusResponseBadRequest(
            s"Could not decode party ID $svPartyIdS; error: $error"
          )
        )
      case Right(svPartyId) =>
        for {
          svcRules <- svcStore.getSvcRules()
          result <- OptionT
            .fromOption[Future](
              Option.when(SvApp.isSvcMemberParty(svPartyId, svcRules))(
                definitions.GetSvOnboardingStatusResponse(
                  state = "completed",
                  name = Some(svcRules.payload.members.get(svPartyId.toProtoPrimitive).name),
                  contractId = Some(Codec.encodeContractId(svcRules.contractId)),
                )
              )
            )
            .orElse(
              OptionT(
                svcStore
                  .lookupSvConfirmed(svPartyId)
              ).map(svConfirmed =>
                definitions.GetSvOnboardingStatusResponse(
                  state = "confirmed",
                  name = Some(svConfirmed.payload.svName),
                  contractId = Some(Codec.encodeContractId(svConfirmed.contractId)),
                )
              )
            )
            .orElse(
              for {
                svOnboarding <- OptionT(svcStore.lookupSvOnboardingByCandidateParty(svPartyId))
                confirmations <- OptionT.liftF(svcStore.listSvOnboardingConfirmations(svOnboarding))
                confirmedBy = confirmations
                  .map(c =>
                    svcRules.payload.members.asScala.get(c.payload.confirmer) match {
                      case Some(member) => member.name
                      case None => c.payload.confirmer
                    }
                  )
                  .toVector
              } yield definitions.GetSvOnboardingStatusResponse(
                state = "onboarding",
                name = Some(svOnboarding.payload.candidateName),
                contractId = Some(Codec.encodeContractId(svOnboarding.contractId)),
                confirmedBy = Some(confirmedBy),
                requiredNumConfirmations = Some(SvUtil.requiredNumConfirmations(svcRules)),
              )
            )
            .value
        } yield result match {
          case Some(result) => result
          case None => definitions.GetSvOnboardingStatusResponse(state = "unknown")
        }
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
    // TODO(#2241) Add check to ensure that a validator can't get onboarded twice
    for {
      svcRules <- svcStore.getSvcRules()
      cmds = (
        svcRules.contractId
          .exerciseSvcRules_OnboardValidator(
            svParty.toProtoPrimitive,
            candidateParty.toProtoPrimitive,
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

  private def startSvOnboarding(
      candidateName: String,
      candidateParty: PartyId,
      token: String,
  ): Future[Either[String, Unit]] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      for {
        svcRules <- svcStore.getSvcRules()
        lookup <- svcStore.lookupSvOnboardingByTokenWithOffset(token)
        outcome <- lookup match {
          case QueryResult(_, Some(_)) =>
            logger.info("An SV onboarding contract for this token already exists.")
            Future.successful(Right(()))
          case result @ QueryResult(_, None) => {
            if (SvApp.isSvcMemberParty(candidateParty, svcRules)) {
              Future.successful(
                Left("An SV with that party ID already exists.")
              )
            } else if (
              !SvApp.isDevNet(svcRules) && SvApp.isSvcMemberName(candidateName, svcRules)
            ) {
              Future.successful(
                Left("An SV with that name already exists.")
              )
            } else {
              val cmd = (
                svcRules.contractId
                  .exerciseSvcRules_StartSvOnboarding(
                    candidateName,
                    candidateParty.toProtoPrimitive,
                    token,
                    svParty.toProtoPrimitive,
                  )
              )
              ledgerConnection
                .submitCommands(
                  actAs = Seq(svParty),
                  readAs = Seq(svcParty),
                  commands = cmd.commands.asScala.toSeq,
                  commandId = CNLedgerConnection.CommandId(
                    "com.daml.network.sv.startSvOnboarding",
                    Seq(svParty),
                    s"$token",
                  ),
                  deduplicationOffset = result.deduplicationOffset,
                  domainId = globalDomain,
                )
                .map { _ => Right(()) }
            }
          }
        }
      } yield outcome
    }

  // TODO(#3429) add a check here to make sure the candidate SV is already confirmed.
  override def onboardSvPartyMigrationAuthorize(
      respond: v0.SvResource.OnboardSvPartyMigrationAuthorizeResponse.type
  )(
      body: definitions.OnboardSvPartyMigrationAuthorizeRequest
  ): Future[v0.SvResource.OnboardSvPartyMigrationAuthorizeResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      ParticipantId
        .fromProtoPrimitive(body.participantId, "")
        .fold(
          err =>
            Future
              .successful(
                v0.SvResource.OnboardSvPartyMigrationAuthorizeResponse.BadRequest(err.message)
              ),
          participantId =>
            for {
              authorizedAt <- svcPartyHosting.authorizeSvcPartyToParticipant(
                globalDomain,
                participantId,
                RequestSide.From,
              )
              acsBytes <- participantAdminConnection.downloadAcsSnapshot(
                Set(svcParty),
                filterDomainId = globalDomain.toProtoPrimitive,
                timestamp = Some(authorizedAt),
              )
              // TODO(M3-57) consider if a more space-efficient encoding is necessary
              encoded = Base64.getEncoder.encodeToString(acsBytes.toByteArray)
            } yield definitions.OnboardSvPartyMigrationAuthorizeResponse(encoded),
        )
    }
}
