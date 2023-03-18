package com.daml.network.sv.admin.http

import com.daml.network.codegen.java.cn
import com.daml.network.environment.{CoinLedgerClient, CoinLedgerConnection, CoinRetries}
import com.daml.network.http.v0.{definitions, sv as v0}
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.sv.SvApp
import com.daml.network.sv.store.{SvSvStore, SvSvcStore}
import com.daml.network.sv.util.SvOnboardingToken
import com.daml.network.util.Contract
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.{Clock, NonNegativeFiniteDuration}
import com.digitalasset.canton.topology.{DomainId, ParticipantId, PartyId}
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.opentelemetry.api.trace.Tracer
import com.daml.network.admin.api.client.ParticipantAdminConnection
import com.digitalasset.canton.topology.transaction.{
  ParticipantPermission,
  RequestSide,
  TopologyChangeOp,
}
import io.grpc.{Status, StatusRuntimeException}

import java.security.SecureRandom
import java.time.Instant
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
    participantAdminConnection: ParticipantAdminConnection,
    retryProvider: CoinRetries,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
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

  def onboardSv(
      respond: v0.SvResource.OnboardSvResponse.type
  )(body: definitions.OnboardSvRequest): Future[v0.SvResource.OnboardSvResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      SvOnboardingToken.verifyAndDecode(body.token) match {
        case Left(error) =>
          Future.successful(
            v0.SvResource.OnboardSvResponseBadRequest(s"Could not vefify and decode token: $error")
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
                  _ <- retryProvider.retryForClientCalls(
                    "start SV onboarding via SvcRules",
                    startSvOnboarding(token.candidateName, token.candidateParty, body.token),
                    logger,
                  )
                } yield v0.SvResource.OnboardSvResponseOK
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
  ): Future[Unit] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      for {
        svcRules <- svcStore.getSvcRules()
        lookup <- svcStore.lookupSvOnboardingByTokenWithOffset(token)
        _ <- lookup match {
          case QueryResult(_, Some(_)) =>
            logger.info("An SV onboarding contract for this token already exists.")
            Future.successful(())
          case QueryResult(off, None) => {
            val cmd = (
              svcRules.contractId
                .exerciseSvcRules_StartSvOnboarding(
                  candidateName,
                  candidateParty.toProtoPrimitive,
                  token,
                  svParty.toProtoPrimitive,
                )
            )
            ledgerConnection.submitCommands(
              actAs = Seq(svParty),
              readAs = Seq(svcParty),
              commands = cmd.commands.asScala.toSeq,
              commandId = CoinLedgerConnection.CommandId(
                "com.daml.network.sv.startSvOnboarding",
                Seq(svParty),
                s"$token",
              ),
              deduplicationOffset = off,
              domainId = globalDomain,
            )
          }
        }
      } yield ()
    }

  override def onboardSvPartyMigration(respond: v0.SvResource.OnboardSvPartyMigrationResponse.type)(
      body: definitions.OnboardSvPartyMigrationRequest
  ): Future[v0.SvResource.OnboardSvPartyMigrationResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      ParticipantId
        .fromProtoPrimitive(body.participantId, "")
        .fold(
          err =>
            Future
              .successful(v0.SvResource.OnboardSvPartyMigrationResponse.BadRequest(err.message)),
          participantId =>
            for {
              authorizedAt <- authorizeSvcPartyToParticipantUntilCompletion(participantId)
              acsBytes <- participantAdminConnection.downloadAcsSnapshot(
                Set(svcParty),
                filterDomainId = globalDomain.toProtoPrimitive,
                timestamp = Some(authorizedAt),
              )
              // TODO(M3-57) consider if a more space-efficient encoding is necessary
              encoded = Base64.getEncoder.encodeToString(acsBytes.toByteArray)
            } yield definitions.OnboardSvPartyMigrationResponse(encoded),
        )
    }

  private def authorizeSvcPartyToParticipantUntilCompletion(
      participantId: ParticipantId
  )(implicit traceContext: TraceContext): Future[Instant] = for {
    _ <- participantAdminConnection
      .authorizePartyToParticipant(
        TopologyChangeOp.Add,
        svcParty,
        participantId,
        RequestSide.From,
        ParticipantPermission.Observation,
      )

    // retry until the authorization can be seen from PartyToParticipantMappings
    authorizedAt <- retryProvider.retryForClientCalls(
      "wait for authorization to complete", {
        participantAdminConnection
          .listPartyToParticipantMappings(
            filterStore = globalDomain.toProtoPrimitive,
            operation = Some(TopologyChangeOp.Add),
            filterParty = svcParty.toProtoPrimitive,
            filterParticipant = participantId.uid.id.unwrap,
            filterRequestSide = Some(RequestSide.From),
            filterPermission = Some(ParticipantPermission.Observation),
          )
          .map {
            case Seq(mapping) =>
              mapping.context.validFrom
            case Seq() =>
              throw new StatusRuntimeException(
                Status.NOT_FOUND.withDescription("Authorization is still in progress")
              )
            case _ =>
              throw new StatusRuntimeException(
                Status.INTERNAL.withDescription("Unexpected number of mappings")
              )
          }
      },
      logger,
    )
  } yield authorizedAt
}
