package com.daml.network.sv.admin.http

import cats.data.OptionT
import cats.syntax.applicative.*
import cats.syntax.foldable.*
import cats.syntax.option.*
import com.daml.error.utils.ErrorDetails
import com.daml.network.admin.http.HttpErrorHandler
import com.daml.network.codegen.java.cc.coin.FeaturedAppRight
import com.daml.network.codegen.java.cn.svcrules.SvcRules
import com.daml.network.codegen.java.cn.svonboarding.SvOnboardingRequest
import com.daml.network.codegen.java.cn.validatoronboarding.ValidatorOnboarding
import com.daml.network.environment.{
  CNLedgerConnection,
  ParticipantAdminConnection,
  RetryProvider,
  SequencerAdminConnection,
}
import com.daml.network.http.v0.definitions.{
  CometBftNodeStatusResponse,
  CometBftStatusOrError,
  ErrorResponse,
}
import com.daml.network.http.v0.sv.SvResource
import com.daml.network.http.v0.{definitions, sv as v0}
import com.daml.network.store.CNNodeAppStoreWithIngestion
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.sv.cometbft.CometBftClient
import com.daml.network.sv.setup.SvcPartyHosting
import com.daml.network.sv.store.{SvSvStore, SvSvcStore}
import com.daml.network.sv.util.SvUtil.{generateRandomOnboardingSecret, requiredNumVotes}
import com.daml.network.sv.util.{SvOnboardingToken, SvUtil, SvcRulesLock}
import com.daml.network.sv.{LocalDomainNode, SvApp}
import com.daml.network.util.{Codec, Contract}
import com.digitalasset.canton.LfTimestamp
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.*
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status.Code
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import java.util.{Base64, UUID}
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
    cometBftClient: Option[CometBftClient],
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends v0.SvHandler[TraceContext]
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
  )(extracted: TraceContext): Future[v0.SvResource.OnboardValidatorResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.onboardValidator") { _ => _ =>
      Codec.decode(Codec.Party)(body.partyId) match {
        case Right(partyId) =>
          svStore.lookupValidatorOnboardingBySecret(body.secret).flatMap {
            case None =>
              svStore.lookupUsedSecret(body.secret).flatMap {
                case Some(used) if used.payload.validator == body.partyId =>
                  // This validator is already onboarded with the same secret - nothing to do
                  Future.successful(v0.SvResource.OnboardValidatorResponseOK)
                case Some(_) =>
                  Future.failed(
                    HttpErrorHandler
                      .unauthorized("Secret has already been used for a different validator.")
                  )
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
  }

  def startSvOnboarding(
      respond: v0.SvResource.StartSvOnboardingResponse.type
  )(
      body: definitions.StartSvOnboardingRequest
  )(extracted: TraceContext): Future[v0.SvResource.StartSvOnboardingResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.startSvOnboarding") { _ => _ =>
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
  }

  def getSvOnboardingStatus(
      respond: v0.SvResource.GetSvOnboardingStatusResponse.type
  )(
      svPartyOrName: String
  )(extracted: TraceContext): Future[SvResource.GetSvOnboardingStatusResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getSvOnboardingStatus") { _ => _ =>
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
  }

  def devNetOnboardValidatorPrepare(
      respond: v0.SvResource.DevNetOnboardValidatorPrepareResponse.type
  )()(extracted: TraceContext): Future[v0.SvResource.DevNetOnboardValidatorPrepareResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.devNetOnboardValidatorPrepare") { _ => _ =>
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
  }

  def getSvcInfo(
      respond: v0.SvResource.GetSvcInfoResponse.type
  )()(extracted: TraceContext): Future[v0.SvResource.GetSvcInfoResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getSvcInfo") { _ => _ =>
      for {
        latestOpenMiningRound <- svcStore.getLatestActiveOpenMiningRound()
        coinRules <- svcStore.getCoinRules()
        svcRules <- svcStore.getSvcRules()
      } yield definitions.GetSvcInfoResponse(
        svUser = svUserName,
        svPartyId = svParty.toProtoPrimitive,
        svcPartyId = svcParty.toProtoPrimitive,
        votingThreshold = requiredNumVotes(svcRules),
        latestMiningRound = latestOpenMiningRound.contract.toHttp,
        coinRules = coinRules.toHttp,
        svcRules = svcRules.contract.toHttp,
      )
    }
  }

  def getCometBftNodeStatus(
      respond: SvResource.GetCometBftNodeStatusResponse.type
  )()(extracted: TraceContext): Future[
    SvResource.GetCometBftNodeStatusResponse
  ] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getCometBftNodeStatus") { _ => _ =>
      withClientOrNotFound(respond.NotFound) {
        _.nodeStatus()
          .map(status =>
            CometBftStatusOrError(
              response = CometBftNodeStatusResponse(
                status.nodeInfo.id,
                status.syncInfo.catchingUp,
                BigDecimal(status.validatorInfo.votingPower),
              ).some
            )
          )
      }
    }
  }

  def onboardSvPartyMigrationAuthorize(
      respond: v0.SvResource.OnboardSvPartyMigrationAuthorizeResponse.type
  )(
      body: definitions.OnboardSvPartyMigrationAuthorizeRequest
  )(extracted: TraceContext): Future[v0.SvResource.OnboardSvPartyMigrationAuthorizeResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.onboardSvPartyMigrationAuthorize") { _ => _ =>
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
  }

  def onboardSvMediator(
      respond: v0.SvResource.OnboardSvMediatorResponse.type
  )(
      body: definitions.OnboardSvMediatorRequest
  )(extracted: TraceContext): Future[v0.SvResource.OnboardSvMediatorResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.onboardSvMediator") { _ => _ =>
      (for {
        mediatorId <- Codec.decode(Codec.Mediator)(body.mediatorId)
      } yield {
        onboardMediator(mediatorId)
      }).fold(
        errMsg => Future.failed(HttpErrorHandler.badRequest(errMsg)),
        _.map(_ => v0.SvResource.OnboardSvMediatorResponseOK),
      )
    }
  }

  def onboardSvSequencer(
      respond: v0.SvResource.OnboardSvSequencerResponse.type
  )(
      body: definitions.OnboardSvSequencerRequest
  )(extracted: TraceContext): Future[v0.SvResource.OnboardSvSequencerResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.onboardSvSequencer") { _ => _ =>
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
  }

  private def withClientOrNotFound[T](
      notFound: ErrorResponse => T
  )(call: CometBftClient => Future[T]) = cometBftClient
    .fold {
      notFound(ErrorResponse("CometBFT is not configured."))
        .pure[Future]
    } {
      call
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

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private def authorizeParticipantForHostingSvcParty(
      participantId: ParticipantId
  )(implicit tc: TraceContext) = {
    logger.info(s"Sponsor SV authorizing svc party to participant $participantId")

    def submitDummyTransaction(): Future[Unit] =
      svStoreWithIngestion.connection.submitCommandsNoDedup(
        Seq(svParty),
        Seq.empty,
        // The transaction here is arbitrary with the restriction that it should not have the SVC as a stakeholder.
        // FeaturedAppRight just happens to be one of the simplest templates we have.
        new FeaturedAppRight(svParty.toProtoPrimitive, svParty.toProtoPrimitive).createAnd
          .exerciseArchive(new com.daml.network.codegen.java.da.internal.template.Archive())
          .commands
          .asScala
          .toSeq,
        globalDomain,
      )
    for {
      // As a work around to #3933, prevent participant from crashing when authorization transaction is being processed
      // TODO(#3933): we can remove this when canton team has completed a proper fix to #3933
      acsBytes <- svcRulesLock.withLock(s"authorizing SVC party to participant $participantId")(
        for {
          // this will wait until the PartyToParticipant state change completed
          authorizedAt <- svcPartyHosting.authorizeSvcPartyToParticipant(
            globalDomain,
            participantId,
          )
          // Acquiring the ACS snapshot is tricky due to two issues:
          // 1. The snapshot can only be acquired at a "clean" timestamp which means there are no outstanding ACS commitments.
          //    To ensure that the timestamp will eventually be clean we need to submit a transaction visible to the participant (submitDummyTransaction) and
          //    retry the download afterwards. Note that due to the second issue, this transaction must not change contracts with SVC as the stakeholder.
          // 2. Concurrent ACS pruning in Canton can prune the data for that timestamp. In that case, we need to fetch the data at a later timestamp.
          //    Of course this is only safe if there have not been any relevant changes in between. We ensure this using the SvcRules lock.
          //    In that case, we again need to submit a dummy transaction to ensure that the updated timestamp is clean.
          _ <- submitDummyTransaction()
          acsBytes <- {
            var acsOffset = authorizedAt
            retryProvider.retryForAutomation(
              show"Download ACS snapshot for SVC at $authorizedAt",
              participantAdminConnection
                .downloadAcsSnapshot(
                  Set(svcParty),
                  filterDomainId = globalDomain.toProtoPrimitive,
                  timestamp = Some(acsOffset),
                )
                .recoverWith { case ex: StatusRuntimeException =>
                  val errorDetails = ErrorDetails.from(ex: StatusRuntimeException)
                  for {
                    _ <- errorDetails.traverse_ {
                      case ErrorDetails.ErrorInfoDetail("UNAVAILABLE_ACS_SNAPSHOT", metadata) =>
                        metadata.get("prunedTimestamp") match {
                          case None =>
                            logger.warn(
                              s"UNAVAILABLE_ACS_SNAPSHOT has no prunedTimestamp field: $metadata"
                            )
                            Future.unit
                          case Some(timestamp) =>
                            LfTimestamp.fromString(timestamp) match {
                              case Left(err) =>
                                logger.warn(
                                  s"Failed to convert pruned timestamp from error details into an LfTimestamp: $err"
                                )
                                Future.unit
                              case Right(timestamp) =>
                                // The pruned timestamp is the latest timestamp that has been pruned
                                // so we need to increase it by one
                                acsOffset = timestamp.addMicros(1).toInstant
                                submitDummyTransaction()
                            }
                        }
                      case _ => Future.unit
                    }
                  } yield throw ex
                },
              logger,
            )
          }
          _ = logger
            .info(
              s"Sponsor SV finished authorizing svc party to participant $participantId, unlocking SvcRules and CoinRules contracts"
            )
          ourParticipant <- participantAdminConnection.getParticipantId()
          _ = logger.info("Adding candidate SV to unionspace")
          _ <- participantAdminConnection.ensureUnionspaceDefinition(
            globalDomain,
            svcParty.uid.namespace,
            participantId.uid.namespace,
            ourParticipant.uid.namespace.fingerprint,
          )
        } yield acsBytes
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
      ourParticipant <- participantAdminConnection.getParticipantId()
      _ <- participantAdminConnection.ensureSequencerDomainState(
        globalDomain,
        sequencerId,
        ourParticipant.uid.namespace.fingerprint,
      )
      _ <- retryProvider.waitUntil(
        "New sequencer is observed in SequencerDomainState through existing sequencer",
        sequencerAdminConnection
          .getSequencerDomainState(globalDomain)
          .map(result =>
            if (!result.mapping.active.contains(sequencerId)) {
              throw Status.FAILED_PRECONDITION
                .withDescription(
                  s"Sequencer $sequencerId is not in active sequencers ${result.mapping.active}"
                )
                .asRuntimeException()
            }
          ),
        logger,
      )
    } yield ()
  }

  private def dummyTopologyTransaction(
      sequencerAdminConnection: SequencerAdminConnection
  )(implicit traceContext: TraceContext): Future[Unit] = {
    val dummyHint = s"dummy-${UUID.randomUUID}"
    for {
      party <- svcStoreWithIngestion.connection.ensurePartyAllocated(
        dummyHint,
        None,
        participantAdminConnection,
      )
      msg = "Wait for sequencer to observe party allocation"
      _ = logger.info(msg)
      _ <- retryProvider.retryForClientCalls(
        msg,
        for {
          state <- sequencerAdminConnection.listPartyToParticipant(filterParty = party.filterString)
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
      mediatorId: MediatorId
  )(implicit traceContext: TraceContext): Future[Unit] =
    for {
      ourParticipant <- participantAdminConnection.getParticipantId()
      _ <- participantAdminConnection.ensureMediatorDomainState(
        globalDomain,
        mediatorId,
        ourParticipant.uid.namespace.fingerprint,
      )
    } yield ()

  // TODO(#6256): Remove this and make SvApp pay for mediator traffic as well
  private def grantMediatorUnlimitedTraffic(
      mediatorId: MediatorId
  )(implicit traceContext: TraceContext): Future[Unit] =
    for {
      ourParticipant <- participantAdminConnection.getParticipantId()
      _ <- participantAdminConnection.ensureTrafficControlState(
        globalDomain,
        mediatorId,
        Long.MaxValue,
        ourParticipant.uid.namespace.fingerprint,
      )
    } yield ()

  // TODO(#5196) Replace this in favor of a Daml based flow. Note that for now
  // there is no authorization check here. The daml flow will naturally give us one
  // so implementing it here seems like wasted effort.
  private def onboardMediator(
      mediatorId: MediatorId
  )(implicit traceContext: TraceContext) = {
    for {
      _ <- addMediatorToTopologyState(mediatorId)
      _ <- grantMediatorUnlimitedTraffic(mediatorId)
    } yield ()
  }

  private def isCompleted(
      svParty: PartyId,
      svcRules: Contract.Has[SvcRules.ContractId, SvcRules],
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
      svcRules: Contract.Has[SvcRules.ContractId, SvcRules],
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
      svcRules: Contract.Has[SvcRules.ContractId, SvcRules],
  )(implicit tc: TraceContext): OptionT[Future, definitions.GetSvOnboardingStatusResponse] = {
    for {
      svOnboardingRequest <- OptionT(svcStore.lookupSvOnboardingRequestByCandidateParty(svParty))
      result <- getOnboardedStatus(svOnboardingRequest, svcRules)
    } yield result
  }

  private def isRequested(
      svParty: String,
      svcRules: Contract.Has[SvcRules.ContractId, SvcRules],
  )(implicit tc: TraceContext): OptionT[Future, definitions.GetSvOnboardingStatusResponse] = {
    for {
      svOnboardingRequest <- OptionT(svcStore.lookupSvOnboardingRequestByCandidateName(svParty))
      result <- getOnboardedStatus(svOnboardingRequest, svcRules)
    } yield result
  }

  private def getOnboardedStatus(
      svOnboardingRequest: Contract[SvOnboardingRequest.ContractId, SvOnboardingRequest],
      svcRules: Contract.Has[SvcRules.ContractId, SvcRules],
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
      _ <- svcStoreWithIngestion.connection
        .submit(Seq(svParty), Seq(svcParty), cmds)
        .withDomainId(svcRules.domain)
        .noDedup // No command-dedup required, as the ValidatorOnboarding contract is archived
        .yieldUnit()
    } yield ()

  private def startSvOnboarding(
      candidateName: String,
      candidateParty: PartyId,
      token: String,
  )(implicit tc: TraceContext): Future[Either[String, Unit]] = {
    withSpan(s"$workflowId.startSvOnboarding") { _ => _ =>
      for {
        svcRules <- svcStore.getSvcRules()
        lookup <- svcStore.lookupSvOnboardingRequestByTokenWithOffset(token)
        outcome <- lookup match {
          case QueryResult(_, Some(_)) =>
            logger.info("An SV onboarding contract for this token already exists.")
            Future.successful(Right(()))
          case QueryResult(offset, None) => {
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
              val cmd = svcRules.exercise(
                _.exerciseSvcRules_StartSvOnboarding(
                  candidateName,
                  candidateParty.toProtoPrimitive,
                  token,
                  svParty.toProtoPrimitive,
                )
              )
              svcStoreWithIngestion.connection
                .submit(actAs = Seq(svParty), readAs = Seq(svcParty), cmd)
                .withDedup(
                  commandId = CNLedgerConnection.CommandId(
                    "com.daml.network.sv.startSvOnboarding",
                    Seq(svParty),
                    s"$token",
                  ),
                  deduplicationOffset = offset,
                )
                .yieldUnit()
                .map { _ => Right(()) }
            }
          }
        }
      } yield outcome
    }
  }
}
