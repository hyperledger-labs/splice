package com.daml.network.sv.admin.http

import cats.data.OptionT
import cats.syntax.foldable.*
import com.daml.network.admin.http.HttpErrorHandler
import com.daml.network.store.CNNodeAppStoreWithIngestion
import com.daml.network.codegen.java.cn.svcrules.SvcRules
import com.daml.network.codegen.java.cn.svonboarding.SvOnboardingRequest
import com.daml.network.codegen.java.cn.validatoronboarding.ValidatorOnboarding
import com.daml.network.environment.{
  CNLedgerConnection,
  MediatorAdminConnection,
  ParticipantAdminConnection,
  RetryProvider,
  SequencerAdminConnection,
}
import com.daml.network.http.v0.{definitions, sv as v0}
import com.daml.network.http.v0.sv.SvResource
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.sv.{LocalDomainNode, SvApp, SvcPartyHosting, SvcRulesLock}
import com.daml.network.sv.store.{SvSvStore, SvSvcStore}
import com.daml.network.sv.util.{SvOnboardingToken, SvUtil}
import com.daml.network.sv.util.SvUtil.generateRandomOnboardingSecret
import com.daml.network.util.{Codec, Contract}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{DomainId, MediatorId, ParticipantId, PartyId, SequencerId}
import com.digitalasset.canton.topology.transaction.RequestSide
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.grpc.Status.Code
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class HttpSvHandler(
    globalDomain: DomainId,
    svUserName: String,
    svStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvStore],
    svcStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvcStore],
    isDevNet: Boolean,
    clock: Clock,
    participantAdminConnection: ParticipantAdminConnection,
    svcRulesLock: SvcRulesLock,
    localDomainNode: Option[LocalDomainNode],
    retryProvider: RetryProvider,
    svcPartyHosting: SvcPartyHosting,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends v0.SvHandler[Unit]
    with Spanning
    with NamedLogging {
  private val workflowId = this.getClass.getSimpleName
  private val svStore = svStoreWithIngestion.store
  private val svcStore = svcStoreWithIngestion.store
  private val svParty = svcStore.key.svParty
  private val svcParty = svcStore.key.svcParty

  def onboardValidator(
      respond: v0.SvResource.OnboardValidatorResponse.type
  )(
      body: definitions.OnboardValidatorRequest
  )(fake: Unit): Future[v0.SvResource.OnboardValidatorResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      Codec.decode(Codec.Party)(body.partyId) match {
        case Right(partyId) =>
          svStore.lookupValidatorOnboardingBySecret(body.secret).flatMap {
            case None =>
              svStore.lookupUsedSecret(body.secret).flatMap {
                case Some(_) =>
                  Future.failed(HttpErrorHandler.unauthorized("Secret has already been used."))
                case None => Future.failed(HttpErrorHandler.unauthorized("Unknown secret."))
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
          Future.failed(HttpErrorHandler.badRequest(error))
      }
    }

  def startSvOnboarding(
      respond: v0.SvResource.StartSvOnboardingResponse.type
  )(
      body: definitions.StartSvOnboardingRequest
  )(fake: Unit): Future[v0.SvResource.StartSvOnboardingResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      SvOnboardingToken.verifyAndDecode(body.token) match {
        case Left(error) =>
          Future.failed(
            HttpErrorHandler.badRequest(s"Could not verify and decode token: $error")
          )
        case Right(token) =>
          SvApp
            .isApprovedSvIdentity(
              token.candidateName,
              token.candidateParty,
              body.token,
              svStore,
              logger,
            )
            .flatMap {
              case Left(reason) =>
                Future.failed(
                  HttpErrorHandler.unauthorized(
                    s"Could not authorize onboarding request because of reason: $reason"
                  )
                )
              case Right(_) =>
                // We retry here because the SvcRules can change while attempting this.
                retryProvider
                  .retryForClientCalls(
                    "start SV onboarding via SvcRules",
                    startSvOnboarding(token.candidateName, token.candidateParty, body.token),
                    logger,
                  )
                  .flatMap {
                    case Left(reason) => Future.failed(HttpErrorHandler.badRequest(reason))
                    case Right(()) => Future.successful(v0.SvResource.StartSvOnboardingResponseOK)
                  }
            }
      }
    }

  def getSvOnboardingStatus(
      respond: v0.SvResource.GetSvOnboardingStatusResponse.type
  )(svPartyOrName: String)(fake: Unit): Future[SvResource.GetSvOnboardingStatusResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      Codec.decode(Codec.Party)(svPartyOrName) match {
        case Left(error) =>
          for {
            svcRules <- svcStore.getSvcRules()
            result <- OptionT
              .fromOption[Future](
                isCompleted(svPartyOrName, svcRules)
              )
              .orElse(isConfirmed(svPartyOrName, svcStore))
              .orElse(isRequested(svPartyOrName, svcRules))
              .value
          } yield result match {
            case Some(result) => result
            case None =>
              if (svPartyOrName.nonEmpty) {
                definitions.GetSvOnboardingStatusResponse(state = "unknown")
              } else {
                throw HttpErrorHandler.badRequest(
                  s"Could not find any party ID or name matching: $svPartyOrName; error: $error"
                )
              }
          }
        case Right(svPartyId) =>
          for {
            svcRules <- svcStore.getSvcRules()
            result <- OptionT
              .fromOption[Future](
                isCompleted(svPartyId, svcRules)
              )
              .orElse(
                isConfirmed(svPartyId, svcStore)
              )
              .orElse(
                isRequested(svPartyId, svcRules)
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
  )()(fake: Unit): Future[v0.SvResource.DevNetOnboardValidatorPrepareResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      if (isDevNet) {
        val secret = generateRandomOnboardingSecret()
        val expiresIn = NonNegativeFiniteDuration.ofHours(1)
        SvApp
          .prepareValidatorOnboarding(
            secret,
            expiresIn,
            svStoreWithIngestion,
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
              Future.successful(v0.SvResource.DevNetOnboardValidatorPrepareResponseOK(secret))
          }
      } else {
        Future.failed(
          HttpErrorHandler.notImplemented(
            "Validator onboarding preparation self-service is only available in DevNet."
          )
        )
      }
    }

  def getSvcInfo(
      respond: v0.SvResource.GetSvcInfoResponse.type
  )()(fake: Unit): Future[v0.SvResource.GetSvcInfoResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      for {
        coinRules <- svcStore.getCoinRules()
        svcRules <- svcStore.getSvcRules()
      } yield definitions.GetSvcInfoResponse(
        svUser = svUserName,
        svPartyId = svParty.toProtoPrimitive,
        svcPartyId = svcParty.toProtoPrimitive,
        coinRules = coinRules.toJson,
        svcRules = svcRules.toJson,
      )
    }

  def onboardSvPartyMigrationAuthorize(
      respond: v0.SvResource.OnboardSvPartyMigrationAuthorizeResponse.type
  )(
      body: definitions.OnboardSvPartyMigrationAuthorizeRequest
  )(fake: Unit): Future[v0.SvResource.OnboardSvPartyMigrationAuthorizeResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      (for {
        participantId <- Codec.decode(Codec.Participant)(body.participantId)
        candidateParty <- Codec.decode(Codec.Party)(body.candidatePartyId)
      } yield {
        for {
          isCandidateOnboardingConfirmed <- isOnboardingConfirmed(candidateParty)
          svcRules <- svcStore.getSvcRules()
          isCandidateMember = SvApp.isSvcMemberParty(candidateParty, svcRules)
          isCandidatePartyHostedOnParticipant <- svcPartyHosting
            .isPartyHostedOnTargetParticipant(
              candidateParty,
              globalDomain,
              participantId,
            )
          res <-
            if (!isCandidateOnboardingConfirmed && !isCandidateMember)
              Future.failed(
                HttpErrorHandler.unauthorized(
                  s"Candidate party is not a member and no `SvOnboardingConfirmed` for the candidate party is found."
                )
              )
            else if (!isCandidatePartyHostedOnParticipant)
              Future.failed(
                HttpErrorHandler.unauthorized(
                  s"Candidate party $candidateParty is not authorized by participant $participantId"
                )
              )
            else
              authorizeParticipantForHostingSvcParty(
                participantId
              )
        } yield res
      }).fold(errMsg => Future.failed(HttpErrorHandler.badRequest(errMsg)), identity)
    }

  def onboardSvMediator(
      respond: v0.SvResource.OnboardSvMediatorResponse.type
  )(
      body: definitions.OnboardSvMediatorRequest
  )(fake: Unit): Future[v0.SvResource.OnboardSvMediatorResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      (for {
        mediatorId <- Codec.decode(Codec.Mediator)(body.mediatorId)
        mediatorConnection <- localDomainNode
          .map(_.mediatorAdminConnection)
          .toRight("Onboarding mediator configured to use X nodes but sponsoring SV is not")
      } yield {
        onboardMediator(mediatorConnection, mediatorId)
      }).fold(
        errMsg => Future.failed(HttpErrorHandler.badRequest(errMsg)),
        _.map(_ => v0.SvResource.OnboardSvMediatorResponseOK),
      )
    }

  def onboardSvSequencer(
      respond: v0.SvResource.OnboardSvSequencerResponse.type
  )(
      body: definitions.OnboardSvSequencerRequest
  )(fake: Unit): Future[v0.SvResource.OnboardSvSequencerResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      (for {
        sequencerId <- Codec.decode(Codec.Sequencer)(body.sequencerId)
        sequencerConnection <- localDomainNode
          .map(_.sequencerAdminConnection)
          .toRight("Onboarding sequencer configured to use X nodes but sponsoring SV is not")
      } yield {
        onboardSequencer(sequencerConnection, sequencerId)
      }).fold(
        errMsg => Future.failed(HttpErrorHandler.badRequest(errMsg)),
        _.map(snapshot =>
          v0.SvResource.OnboardSvSequencerResponseOK(
            definitions.OnboardSvSequencerResponse(snapshot)
          )
        ),
      )
    }

  private def isOnboardingConfirmed(party: PartyId)(implicit tc: TraceContext): Future[Boolean] = {
    // wait for a bit as it is possible the store ingression is not complete
    retryProvider
      .retryForClientCalls(
        "wait for ",
        for {
          maybeConfirmed <- svcStore
            .lookupSvOnboardingConfirmedByParty(party)
        } yield
          if (maybeConfirmed.isDefined) maybeConfirmed
          else
            throw new StatusRuntimeException(
              Status.NOT_FOUND.withDescription(
                s"SvOnboardingConfirmed contract not found yet"
              )
            ),
        logger,
      )
      .recover {
        case ex: StatusRuntimeException if ex.getStatus.getCode == Code.NOT_FOUND =>
          None
        case unexpected => throw unexpected
      }
      .map(_.isDefined)
  }

  private def authorizeParticipantForHostingSvcParty(
      participantId: ParticipantId
  )(implicit tc: TraceContext) = {
    logger.info(s"Sponsor SV authorizing svc party to participant $participantId")
    for {
      // As a work around to #3933, prevent participant from crashing when authorization transaction is being processed
      // TODO(#3933): we can remove this when canton team has completed a proper fix to #3933
      _ <- svcRulesLock.lock()
      _ = logger.info(
        s"locked SvcRules and CoinRules contracts before sponsor SV authorizing svc party to participant $participantId"
      )

      // this will wait until the PartyToParticipant state change completed
      authorizedAt <- svcPartyHosting.authorizeSvcPartyToParticipant(
        globalDomain,
        participantId,
        RequestSide.From,
      )
      _ = logger
        .info(s"Sponsor SV finished authorizing svc party to participant $participantId")
      _ <- svcRulesLock.unlock()

      _ = logger.info(
        s"svc party to participant authorization completed, unlock SvcRules and CoinRules contracts"
      )

      acsBytes <- participantAdminConnection.downloadAcsSnapshot(
        Set(svcParty),
        filterDomainId = globalDomain.toProtoPrimitive,
        timestamp = Some(authorizedAt),
      )
      // TODO(M3-57) consider if a more space-efficient encoding is necessary
      encoded = Base64.getEncoder.encodeToString(acsBytes.toByteArray)
    } yield v0.SvResource.OnboardSvPartyMigrationAuthorizeResponseOK(
      definitions.OnboardSvPartyMigrationAuthorizeResponse(
        encoded
      )
    )
  }

  private def addSequencerToTopologyState(
      sequencerAdminConnection: SequencerAdminConnection,
      sequencerId: SequencerId,
  )(implicit traceContext: TraceContext): Future[Unit] = {
    logger.info("Proposing new sequencer")
    for {
      sequencerState <- participantAdminConnection.latestSequencerDomainStateX(globalDomain)
      ourParticipant <- participantAdminConnection.getParticipantId(true)
      _ <-
        if (sequencerState.active.contains(sequencerId)) {
          logger.info(s"Sequencer has already been added to topology state")
          Future.unit
        } else {
          logger.info("Proposing new sequencer")
          for {
            _ <- participantAdminConnection.proposeSequencers(
              globalDomain,
              sequencerState.threshold, // TODO(#5093) Increase this instead of copying the previous value.
              sequencerId +: sequencerState.active,
              sequencerState.observers,
              Some(ourParticipant.uid.namespace.fingerprint),
            )
            msg = "Waiting to observe new sequencer proposal"
            _ = logger.info(msg)
            _ <- retryProvider.retryForClientCalls(
              msg,
              for {
                state <- sequencerAdminConnection.getSequencerState(globalDomain)
              } yield {
                if (!state.item.active.forgetNE.contains(sequencerId)) {
                  throw Status.FAILED_PRECONDITION
                    .withDescription(
                      s"Sequencer $sequencerId was not a member of ${state.item.active.forgetNE}"
                    )
                    .asRuntimeException()
                } else {
                  state.item
                }
              },
              logger,
            )
          } yield ()
        }
    } yield ()
  }

  private def dummyTopologyTransaction(
      sequencerAdminConnection: SequencerAdminConnection
  )(implicit traceContext: TraceContext): Future[Unit] = {
    for {
      party <- svcStoreWithIngestion.connection.allocatePartyViaLedgerApi(
        hint = None,
        displayName = None,
      )
      msg = "Wait for sequencer to observe party allocation"
      _ = logger.info(msg)
      _ <- retryProvider.retryForClientCalls(
        msg,
        for {
          state <- sequencerAdminConnection.getPartyToParticipantState(party)
        } yield {
          if (state.isEmpty) {
            throw Status.FAILED_PRECONDITION
              .withDescription(
                s"Sequencer has not yet observed allocation of $party"
              )
              .asRuntimeException()
          }
        },
        logger,
      )
    } yield ()
  }

  private def onboardSequencer(
      sequencerAdminConnection: SequencerAdminConnection,
      sequencerId: SequencerId,
  )(implicit traceContext: TraceContext): Future[definitions.SequencerSnapshot] = {
    logger.info("Querying sequencer domain state")
    for {
      _ <- addSequencerToTopologyState(sequencerAdminConnection, sequencerId)
      // TODO(#5094) We need to generate a dummy transaction before generating the export
      // to make sure that the new sequencer gets a sequencer counter that is included in the snapshot.
      // Note that for now we do the dummy transaction here independent of whether the sequencer has already been onboarded
      // in the topology state or not. We could crash after the topology update but before this and there doesn't seem to be a good way
      // to test for this.
      _ <- dummyTopologyTransaction(sequencerAdminConnection)
      _ = logger.info(s"Downloading toplogy and sequencer snapshot")
      // TODO(#5339) Check if we need to be careful at which offset we query our snapshot.
      topologySnapshot <- sequencerAdminConnection.getTopologySnapshot(globalDomain)
      sequencerSnapshot <- sequencerAdminConnection.getSequencerSnapshot(
        topologySnapshot.lastChangeTimestamp.getOrElse(
          throw new IllegalStateException(s"Topology snapshot has no last change timestamp")
        )
      )
    } yield definitions.SequencerSnapshot(
      topologySnapshot = Base64.getEncoder.encodeToString(topologySnapshot.toProtoV0.toByteArray),
      sequencerSnapshot = Base64.getEncoder.encodeToString(sequencerSnapshot.toByteArray),
    )
  }

  private def addMediatorToTopologyState(
      mediatorAdminConnection: MediatorAdminConnection,
      mediatorId: MediatorId,
  )(implicit traceContext: TraceContext): Future[Unit] = {
    logger.info("Querying mediator domain state")
    for {
      mediatorState <- mediatorAdminConnection.getMediatorState(globalDomain)
      ourParticipant <- participantAdminConnection.getParticipantId(true)
      _ <-
        if (mediatorState.active.contains(mediatorId)) {
          logger.info(s"Mediator has already been added to topology state")
          Future.unit
        } else {
          logger.info("Proposing new mediator")
          for {
            _ <- participantAdminConnection.proposeMediators(
              globalDomain,
              mediatorState.group, // Put all mediators in the same group. Within a group we get BFT guarantees, different groups are for load balancing.
              mediatorState.threshold, // TODO(#5093) Increase this instead of copying the previous value.
              mediatorId +: mediatorState.active,
              mediatorState.observers,
              Some(ourParticipant.uid.namespace.fingerprint),
            )
            msg = "Waiting to observe new mediator proposal"
            _ = logger.info(msg)
            _ <- retryProvider.retryForClientCalls(
              msg,
              for {
                state <- mediatorAdminConnection.getMediatorState(globalDomain)
              } yield {
                if (!state.active.forgetNE.contains(mediatorId)) {
                  throw Status.FAILED_PRECONDITION
                    .withDescription(
                      s"Mediator $mediatorId was not a member of ${state.active.forgetNE}"
                    )
                    .asRuntimeException()
                } else {
                  state
                }
              },
              logger,
            )
          } yield ()
        }
    } yield ()
  }

  // TODO(#5095) Replace this in favor of a Daml based flow. Note that for now
  // there is no authorization check here. The daml flow will naturally give us one
  // so implementing it here seems like wasted effort.
  private def onboardMediator(
      mediatorAdminConnection: MediatorAdminConnection,
      mediatorId: MediatorId,
  )(implicit traceContext: TraceContext) = {
    for {
      _ <- addMediatorToTopologyState(mediatorAdminConnection, mediatorId)
    } yield ()
  }

  private def isCompleted(
      svParty: PartyId,
      svcRules: Contract[SvcRules.ContractId, SvcRules],
  ): Option[definitions.GetSvOnboardingStatusResponse] = {
    Option.when(SvApp.isSvcMemberParty(svParty, svcRules))(
      definitions.GetSvOnboardingStatusResponse(
        state = "completed",
        name = Some(svcRules.payload.members.get(svParty.toProtoPrimitive).name),
        contractId = Some(Codec.encodeContractId(svcRules.contractId)),
      )
    )
  }

  private def isCompleted(
      svParty: String,
      svcRules: Contract[SvcRules.ContractId, SvcRules],
  ): Option[definitions.GetSvOnboardingStatusResponse] = {
    Option.when(SvApp.isSvcMemberName(svParty, svcRules))(
      definitions.GetSvOnboardingStatusResponse(
        state = "completed",
        name = Some(svParty),
        contractId = Some(Codec.encodeContractId(svcRules.contractId)),
      )
    )
  }

  private def isRequested(
      svParty: PartyId,
      svcRules: Contract[SvcRules.ContractId, SvcRules],
  )(implicit tc: TraceContext): OptionT[Future, definitions.GetSvOnboardingStatusResponse] = {
    for {
      svOnboardingRequest <- OptionT(svcStore.lookupSvOnboardingRequestByCandidateParty(svParty))
      result <- getOnboardedStatus(svOnboardingRequest, svcRules)
    } yield result
  }

  private def isRequested(
      svParty: String,
      svcRules: Contract[SvcRules.ContractId, SvcRules],
  )(implicit tc: TraceContext): OptionT[Future, definitions.GetSvOnboardingStatusResponse] = {
    for {
      svOnboardingRequest <- OptionT(svcStore.lookupSvOnboardingRequestByCandidateName(svParty))
      result <- getOnboardedStatus(svOnboardingRequest, svcRules)
    } yield result
  }

  private def getOnboardedStatus(
      svOnboardingRequest: Contract[SvOnboardingRequest.ContractId, SvOnboardingRequest],
      svcRules: Contract[SvcRules.ContractId, SvcRules],
  )(implicit tc: TraceContext) = {
    for {
      confirmations <- OptionT.liftF(svcStore.listSvOnboardingConfirmations(svOnboardingRequest))
      confirmedBy = confirmations
        .map(c =>
          svcRules.payload.members.asScala.get(c.payload.confirmer) match {
            case Some(member) => member.name
            case None => c.payload.confirmer
          }
        )
        .toVector
    } yield definitions.GetSvOnboardingStatusResponse(
      state = "requested",
      name = Some(svOnboardingRequest.payload.candidateName),
      contractId = Some(Codec.encodeContractId(svOnboardingRequest.contractId)),
      confirmedBy = Some(confirmedBy),
      requiredNumConfirmations = Some(SvUtil.requiredNumVotes(svcRules)),
    )
  }

  private def isConfirmed(
      svParty: PartyId,
      svcStore: SvSvcStore,
  )(implicit tc: TraceContext): OptionT[Future, definitions.GetSvOnboardingStatusResponse] = {
    OptionT(
      svcStore
        .lookupSvOnboardingConfirmedByParty(svParty)
    ).map(svOnboardingConfirmed =>
      definitions.GetSvOnboardingStatusResponse(
        state = "confirmed",
        name = Some(svOnboardingConfirmed.payload.svName),
        contractId = Some(Codec.encodeContractId(svOnboardingConfirmed.contractId)),
      )
    )
  }

  private def isConfirmed(
      svName: String,
      svcStore: SvSvcStore,
  )(implicit tc: TraceContext): OptionT[Future, definitions.GetSvOnboardingStatusResponse] = {
    OptionT(
      svcStore
        .lookupSvOnboardingConfirmedByName(svName)
    ).map(svOnboardingConfirmed =>
      definitions.GetSvOnboardingStatusResponse(
        state = "confirmed",
        name = Some(svOnboardingConfirmed.payload.svName),
        contractId = Some(Codec.encodeContractId(svOnboardingConfirmed.contractId)),
      )
    )
  }

  private def onboardValidator(
      candidateParty: PartyId,
      secret: String,
      validatorOnboarding: Contract[ValidatorOnboarding.ContractId, ValidatorOnboarding],
  )(implicit tc: TraceContext): Future[Unit] =
    // TODO(#4114) Make this idempotent
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
      _ <- svcStoreWithIngestion.connection.submitCommandsNoDedup(
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
        lookup <- svcStore.lookupSvOnboardingRequestByTokenWithOffset(token)
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
              svcStoreWithIngestion.connection
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
}
