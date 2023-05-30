package com.daml.network.sv.admin.http

import cats.data.OptionT
import cats.syntax.either.*
import cats.syntax.foldable.*
import cats.syntax.traverse.*
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
import com.daml.network.sv.{SvApp, SvcPartyHosting}
import com.daml.network.sv.store.{SvSvStore, SvSvcStore}
import com.daml.network.sv.util.{SvOnboardingToken, SvUtil}
import com.daml.network.sv.util.SvUtil.generateRandomOnboardingSecret
import com.daml.network.util.{Codec, Contract}
import com.digitalasset.canton.config.{CommunityCryptoConfig, NonNegativeFiniteDuration}
import com.digitalasset.canton.domain.config.DomainParametersConfig
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{DomainId, MediatorId, ParticipantId, PartyId, SequencerId}
import com.digitalasset.canton.topology.transaction.{RequestSide, SignedTopologyTransactionX}
import com.digitalasset.canton.topology.transaction.SignedTopologyTransactionX.GenericSignedTopologyTransactionX
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.digitalasset.canton.version.{DomainProtocolVersion, ProtocolVersion}
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
    sequencerAdminConnection: Option[SequencerAdminConnection],
    mediatorAdminConnection: Option[MediatorAdminConnection],
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

  // TODO(#5091) Stop hardcoding domain parameters
  private val staticDomainParameters = DomainParametersConfig(
    protocolVersion = DomainProtocolVersion(ProtocolVersion.dev),
    devVersionSupport = true,
    uniqueContractKeys = false,
  ).toStaticDomainParameters(CommunityCryptoConfig())
    .valueOr(err =>
      throw new IllegalArgumentException(s"Static domain parameters are invalid: $err")
    )

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
            .isApprovedSvIdentity(token.candidateName, token.candidateParty, body.token, svStore)
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
        coinRulesContractId = coinRules.contractId.toString,
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
        sequencerIdentity <- body.sequencerIdentity.traverse { rawId =>
          for {
            id <- Codec.decode(Codec.Sequencer)(rawId.id)
            txs <- rawId.identityTransactions
              .traverse(r =>
                SignedTopologyTransactionX.fromByteArray(Base64.getDecoder().decode(r))
              )
              .left
              .map(_.message)
          } yield (id, txs)
        }
        mediatorIdentity <- body.mediatorIdentity.traverse(rawId =>
          for {
            id <- Codec.decode(Codec.Mediator)(rawId.id)
            txs <- rawId.identityTransactions
              .traverse(r =>
                SignedTopologyTransactionX.fromByteArray(Base64.getDecoder().decode(r))
              )
              .left
              .map(_.message)
          } yield (id, txs)
        )
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
                participantId,
                sequencerIdentity,
                mediatorIdentity,
              )
        } yield res
      }).fold(errMsg => Future.failed(HttpErrorHandler.badRequest(errMsg)), identity)
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
      participantId: ParticipantId,
      sequencerIdentity: Option[(SequencerId, Seq[GenericSignedTopologyTransactionX])],
      mediatorIdentity: Option[(MediatorId, Seq[GenericSignedTopologyTransactionX])],
  )(implicit tc: TraceContext) = {
    logger.info(s"Sponsor SV authorizing svc party to participant $participantId")
    for {
      // As a work around to #3933, prevent participant from crashing when authorization transaction is being processed
      // TODO(#3933): we can remove this when canton team has completed a proper fix to #3933
      _ <- retryProvider.retryForClientCalls(
        "locking SvcRules and CoinRules contracts",
        lockSvcRulesAndCoinRules(),
        logger,
      )
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
      _ <- retryProvider.retryForClientCalls(
        "unlocking SvcRules and CoinRules contracts",
        unlockSvcRulesAndCoinRules(),
        logger,
      )

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

      // TODO(#5095) Reconsider if this should be part of this onboarding flow
      // or a separate step.
      sequencerSnapshot <- sequencerIdentity.traverse { case (seqId, seqIdTxs) =>
        val seqCon = sequencerAdminConnection.getOrElse(
          throw new IllegalStateException(
            s"Onboarding sequencer configured to use X nodes but sponsoring SV is not"
          )
        )
        onboardSequencer(seqCon, seqId, seqIdTxs)
      }
      _ <- mediatorIdentity.traverse { case (medId, medIdTxs) =>
        val medCon = mediatorAdminConnection.getOrElse(
          throw new IllegalStateException(
            s"Onboarding mediator configured to use X nodes but sponsoring SV is not"
          )
        )
        onboardMediator(medCon, medId, medIdTxs)
      }

    } yield v0.SvResource.OnboardSvPartyMigrationAuthorizeResponseOK(
      definitions.OnboardSvPartyMigrationAuthorizeResponse(
        encoded,
        sequencerSnapshot,
      )
    )
  }

  // TODO(#5092) Make sequencer onboarding idempotent
  private def onboardSequencer(
      sequencerAdminConnection: SequencerAdminConnection,
      sequencerId: SequencerId,
      sequencerIdentityTransactions: Seq[GenericSignedTopologyTransactionX],
  )(implicit traceContext: TraceContext) = {
    logger.info(
      s"Loading topology transactions for sequencer $sequencerId"
    )
    for {
      _ <- participantAdminConnection.loadTopologyTransactions(sequencerIdentityTransactions)
      _ = logger.info("Querying sequencer domain state")
      seqState <- participantAdminConnection.latestSequencerDomainStateX(globalDomain)
      ourParticipant <- participantAdminConnection.getParticipantId(true)
      _ = logger.info("Proposing new sequencer")
      _ <- participantAdminConnection.proposeSequencers(
        globalDomain,
        seqState.threshold, // TODO(#5093) Increase this instead of copying the previous value.
        seqState.active :+ sequencerId,
        seqState.observers,
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
      // TODO(#5094) We need to generate a dummy transaction before generating the export
      // to make sure that the new sequencer gets a sequencer counter that is included in the snapshot.
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
      _ = logger.info(s"Downloading toplogy and sequencer snapshot")
      topologySnapshot <- sequencerAdminConnection.getTopologySnapshot(globalDomain)
      sequencerSnapshot <- sequencerAdminConnection.getSequencerSnapshot(
        topologySnapshot.lastChangeTimestamp.getOrElse(
          throw new IllegalStateException(s"Topology snapshot has no last change timestamp")
        )
      )
    } yield definitions.SequencerSnapshot(
      topologySnapshot = Base64.getEncoder.encodeToString(topologySnapshot.toProtoV0.toByteArray),
      sequencerSnapshot = Base64.getEncoder.encodeToString(sequencerSnapshot.toByteArray),
      staticDomainParameters = Base64.getEncoder.encodeToString(staticDomainParameters.toByteArray),
    )
  }

  // TODO(#5105) Make mediator onboarding idempotent
  private def onboardMediator(
      mediatorAdminConnection: MediatorAdminConnection,
      mediatorId: MediatorId,
      mediatorIdentityTransactions: Seq[GenericSignedTopologyTransactionX],
  )(implicit traceContext: TraceContext) = {
    logger.info(
      s"Loading topology transactions for mediator $mediatorId"
    )
    for {
      _ <- participantAdminConnection.loadTopologyTransactions(mediatorIdentityTransactions)
      _ = logger.info("Querying mediator domain state")
      medState <- mediatorAdminConnection.getMediatorState(globalDomain)
      ourParticipant <- participantAdminConnection.getParticipantId(true)
      _ = logger.info("Proposing new mediator")
      _ <- participantAdminConnection.proposeMediators(
        globalDomain,
        medState.group,
        medState.threshold, // TODO(#5093) Increase this instead of copying the previous value.
        mediatorId +: medState.active,
        medState.observers,
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
          }
        },
        logger,
      )
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

  private def lockSvcRulesAndCoinRules()(implicit tc: TraceContext) = for {
    svcRules <- svcStore.getSvcRules()
    coinRules <- svcStore.getCoinRules()
    res <- svcStoreWithIngestion.connection.submitWithResultNoDedup(
      Seq(svParty),
      Seq(svcParty),
      svcRules.contractId.exerciseSvcRules_Lock(svParty.toProtoPrimitive, coinRules.contractId),
      globalDomain,
    )
  } yield res

  private def unlockSvcRulesAndCoinRules()(implicit tc: TraceContext) = for {
    svcRules <- svcStore.getSvcRules()
    coinRules <- svcStore.getCoinRules()
    res <- svcStoreWithIngestion.connection.submitWithResultNoDedup(
      Seq(svParty),
      Seq(svcParty),
      svcRules.contractId.exerciseSvcRules_Unlock(svParty.toProtoPrimitive, coinRules.contractId),
      globalDomain,
    )
  } yield res
}
