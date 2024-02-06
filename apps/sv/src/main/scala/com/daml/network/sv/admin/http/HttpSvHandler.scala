package com.daml.network.sv.admin.http

import cats.data.{EitherT, OptionT}
import cats.syntax.applicative.*
import com.daml.network.admin.http.HttpErrorHandler
import com.daml.network.codegen.java.cn.svcrules.SvcRules
import com.daml.network.codegen.java.cn.svonboarding.SvOnboardingRequest
import com.daml.network.codegen.java.cn.validatoronboarding.ValidatorOnboarding
import com.daml.network.config.CNThresholds
import com.daml.network.environment.*
import com.daml.network.http.v0.{definitions, sv as v0}
import com.daml.network.store.CNNodeAppStoreWithIngestion
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.sv.cometbft.CometBftClient
import com.daml.network.sv.onboarding.SvcPartyHosting
import com.daml.network.sv.onboarding.sponsor.SvcPartyMigration
import com.daml.network.sv.store.{SvSvStore, SvSvcStore}
import com.daml.network.sv.util.SvOnboardingToken
import com.daml.network.sv.util.SvUtil.generateRandomOnboardingSecret
import com.daml.network.sv.{LocalDomainNode, SvApp}
import com.daml.network.util.{Codec, Contract}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.PositiveLong
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.*
import com.digitalasset.canton.topology.store.TopologyStoreId
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status.Code
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import java.util.{Base64, UUID}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class HttpSvHandler(
    svUserName: String,
    svStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvStore],
    svcStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvcStore],
    isDevNet: Boolean,
    clock: Clock,
    participantAdminConnection: ParticipantAdminConnection,
    localDomainNode: Option[LocalDomainNode],
    retryProvider: RetryProvider,
    svcPartyMigration: SvcPartyMigration,
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
          for {
            svcRules <- svcStore.getSvcRules()
            isCandidatePartyHostedOnParticipant <- participantAdminConnection
              .listPartyToParticipant(
                svcRules.domain.filterString,
                filterParty = token.candidateParty.filterString,
                filterParticipant = token.candidateParticipantId.toProtoPrimitive,
              )
              .map(_.nonEmpty)
            res <-
              if (!SvApp.validateSvNamespace(token.candidateParty, token.candidateParticipantId)) {
                Future.failed(
                  HttpErrorHandler.badRequest(
                    s"Party ${token.candidateParty} does not have the same namespace than its participant ${token.candidateParticipantId}."
                  )
                )
              } else if (!isCandidatePartyHostedOnParticipant)
                Future.failed(
                  HttpErrorHandler.unauthorized(
                    s"Candidate party ${token.candidateParty} is not authorized by participant ${token.candidateParticipantId}"
                  )
                )
              else
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
                          s"Could not approve SV Identity because of reason: $reason"
                        )
                      )
                    case Right(_) =>
                      // We retry here because the SvcRules can change while attempting this.
                      retryProvider
                        .retryForClientCalls(
                          s"start SV ${token.candidateName} onboarding via SvcRules",
                          startSvOnboarding(
                            token.candidateName,
                            token.candidateParty,
                            token.candidateParticipantId,
                            body.token,
                          ),
                          logger,
                        )
                        .flatMap {
                          case Left(reason) =>
                            Future.failed(
                              HttpErrorHandler.badRequest(
                                s"Could not start onboarding request because of reason: : $reason"
                              )
                            )
                          case Right(_) =>
                            Future.successful(v0.SvResource.StartSvOnboardingResponseOK)
                        }
                  }
          } yield res
      }
    }
  }

  def getSvOnboardingStatus(
      respond: v0.SvResource.GetSvOnboardingStatusResponse.type
  )(
      svPartyOrName: String
  )(extracted: TraceContext): Future[v0.SvResource.GetSvOnboardingStatusResponse] = {
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
                definitions.GetSvOnboardingStatusResponse(
                  definitions.SvOnboardingStateUnknown(state = "unknown")
                )
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
            case None =>
              definitions.GetSvOnboardingStatusResponse(
                definitions.SvOnboardingStateUnknown(state = "unknown")
              )
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
        svcStore
          .getSvcRules()
          .flatMap { svcRules =>
            SvApp
              .prepareValidatorOnboarding(
                secret,
                expiresIn,
                svStoreWithIngestion,
                svcRules.domain,
                clock,
                logger,
              )
          }
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
        votingThreshold = CNThresholds.requiredNumVotes(svcRules),
        latestMiningRound = latestOpenMiningRound.contract.toHttp,
        coinRules = coinRules.toHttp,
        svcRules = svcRules.contract.toHttp,
      )
    }
  }

  def getCometBftNodeStatus(
      respond: v0.SvResource.GetCometBftNodeStatusResponse.type
  )()(extracted: TraceContext): Future[
    v0.SvResource.GetCometBftNodeStatusResponse
  ] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getCometBftNodeStatus") { _ => _ =>
      withClientOrNotFound(respond.NotFound) {
        _.nodeStatus()
          .map(status =>
            definitions.CometBftNodeStatusOrErrorResponse(
              definitions.CometBftNodeStatusResponse(
                status.nodeInfo.id,
                status.syncInfo.catchingUp,
                BigDecimal(status.validatorInfo.votingPower),
              )
            )
          )
      }
    }
  }

  override def cometBftJsonRpcRequest(
      respond: v0.SvResource.CometBftJsonRpcRequestResponse.type
  )(
      body: definitions.CometBftJsonRpcRequest
  )(extracted: TraceContext): Future[v0.SvResource.CometBftJsonRpcRequestResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.cometBftJsonRpcRequest") { _ => _ =>
      withClientOrNotFound(respond.NotFound) { client =>
        client
          .jsonRpcCall(body.id, body.method.value, body.params.getOrElse(Map.empty))
          .map(res =>
            v0.SvResource.CometBftJsonRpcRequestResponse.OK(
              definitions.CometBftJsonRpcResponse(
                res.jsonrpc,
                res.id,
                res.result,
              )
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
        candidateParty <- Codec.decode(Codec.Party)(body.candidatePartyId)
      } yield {
        val errorMessage =
          s"Candidate party is not a member and no `SvOnboardingConfirmed` for the candidate party is found."
        for {
          isCandidateOnboardingConfirmed <- isOnboardingConfirmed(candidateParty)
          svcRules <- svcStore.getSvcRules()
          isCandidateMember = SvApp.isSvcMemberParty(candidateParty, svcRules)
          contracts <- svcStore.lookupSvOnboardingConfirmedByParty(candidateParty)
          candidateParticipantId = contracts
            .getOrElse(
              throw Status.NOT_FOUND
                .withDescription(errorMessage)
                .asRuntimeException()
            )
          res <-
            if (!isCandidateOnboardingConfirmed && !isCandidateMember)
              Future.failed(
                HttpErrorHandler.unauthorized(
                  errorMessage
                )
              )
            else
              authorizeParticipantForHostingSvcParty(
                ParticipantId.tryFromProtoPrimitive(candidateParticipantId.payload.svParticipantId)
              )
        } yield res
      }).fold(errMsg => Future.failed(HttpErrorHandler.badRequest(errMsg)), identity)
    }
  }

  private def authorizeParticipantForHostingSvcParty(
      participantId: ParticipantId
  )(implicit tc: TraceContext): Future[v0.SvResource.OnboardSvPartyMigrationAuthorizeResponse] = {
    svcPartyMigration
      .authorizeParticipantForHostingSvcParty(
        participantId
      )
      .fold(
        {
          case SvcPartyHosting
                .RequiredProposalNotFound(
                  partyToParticipantSerial
                ) =>
            v0.SvResource.OnboardSvPartyMigrationAuthorizeResponseBadRequest(
              definitions.ProposalNotFoundErrorResponse(
                proposalNotFound = definitions.ProposalNotFoundErrorResponse.ProposalNotFound(
                  BigInt(partyToParticipantSerial.value)
                )
              )
            )
        },
        { acsBytes =>
          // TODO(M3-57) consider if a more space-efficient encoding is necessary
          val encoded = Base64.getEncoder.encodeToString(acsBytes.toByteArray)
          v0.SvResource.OnboardSvPartyMigrationAuthorizeResponseOK(
            definitions.OnboardSvPartyMigrationAuthorizeResponse(
              encoded
            )
          )
        },
      )
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
        getSequencerOnboardingSnapshots(sequencerConnection, sequencerId)
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
      notFound: definitions.ErrorResponse => T
  )(call: CometBftClient => Future[T]) = cometBftClient
    .fold {
      notFound(definitions.ErrorResponse("CometBFT is not configured."))
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
            throw Status.NOT_FOUND
              .withDescription(
                s"SvOnboardingConfirmed contract not found yet"
              )
              .asRuntimeException(),
        logger,
      )
      .recover {
        case ex: StatusRuntimeException if ex.getStatus.getCode == Code.NOT_FOUND =>
          None
        case unexpected => throw unexpected
      }
      .map(_.isDefined)
  }

  private def waitForNewSequencerObservedByExistingSequencer(
      sequencerAdminConnection: SequencerAdminConnection,
      sequencerId: SequencerId,
  )(implicit traceContext: TraceContext): Future[Unit] = {
    for {
      globalDomain <- svcStore.getSvcRules().map(_.domain)
      _ <- retryProvider.waitUntil(
        RetryFor.WaitingOnInitDependency, // the trigger runs every 30s, so this should be enough to observe the new sequencer
        "New sequencer is observed in SequencerDomainState through existing sequencer",
        sequencerAdminConnection
          .getSequencerDomainState(globalDomain)
          .map(result =>
            if (!result.mapping.active.contains(sequencerId)) {
              throw Status.NOT_FOUND
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
      globalDomainId: DomainId,
      sequencerAdminConnection: SequencerAdminConnection,
  )(implicit traceContext: TraceContext): Future[Unit] = {
    val dummyHint = s"dummy-${UUID.randomUUID}"
    for {
      party <- svcStoreWithIngestion.connection.ensurePartyAllocated(
        TopologyStoreId.DomainStore(globalDomainId),
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

  private def getSequencerOnboardingSnapshots(
      sequencerAdminConnection: SequencerAdminConnection,
      sequencerId: SequencerId,
  )(implicit traceContext: TraceContext): Future[definitions.SequencerSnapshot] = {
    logger.info("Querying sequencer domain state")
    for {
      _ <- waitForNewSequencerObservedByExistingSequencer(sequencerAdminConnection, sequencerId)
      // We need to generate a dummy transaction before generating the export
      // to make sure that the new sequencer gets a sequencer counter that is included in the snapshot.
      // Note that for now we do the dummy transaction here independent of whether the sequencer has already been onboarded
      // in the topology state or not. We could crash after the topology update but before this and there doesn't seem to be a good way
      // to test for this.
      globalDomain <- svcStore.getSvcRules().map(_.domain)
      _ <- dummyTopologyTransaction(globalDomain, sequencerAdminConnection)
      _ = logger.info(s"Downloading topology and sequencer snapshot")
      // TODO(#5339) Check if we need to be careful at which offset we query our snapshot.
      topologySnapshot <- sequencerAdminConnection.getTopologySnapshot(globalDomain)
      sequencerSnapshot <- sequencerAdminConnection.getSequencerSnapshot(
        topologySnapshot.lastChangeTimestamp.getOrElse(
          throw new IllegalStateException(s"Topology snapshot has no last change timestamp")
        )
      )
    } yield definitions.SequencerSnapshot(
      topologySnapshot = Base64.getEncoder.encodeToString(topologySnapshot.toProtoV30.toByteArray),
      sequencerSnapshot = Base64.getEncoder.encodeToString(sequencerSnapshot.toByteArray),
    )
  }

  private def addMediatorToTopologyState(
      mediatorId: MediatorId
  )(implicit traceContext: TraceContext): Future[Unit] =
    for {
      ourParticipant <- participantAdminConnection.getParticipantId()
      globalDomain <- svcStore.getSvcRules().map(_.domain)
      _ <- participantAdminConnection.ensureMediatorDomainStateAdditionProposal(
        globalDomain,
        mediatorId,
        ourParticipant.uid.namespace.fingerprint,
        RetryFor.ClientCalls,
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
      globalDomain <- svcStore.getSvcRules().map(_.domain)
      _ <- retryProvider.waitUntil(
        RetryFor.WaitingOnInitDependency,
        "Mediator has been granted unlimited traffic",
        participantAdminConnection
          .lookupTrafficControlState(
            globalDomain,
            mediatorId,
          )
          .map {
            case None =>
              throw Status.NOT_FOUND
                .withDescription(show"No traffic state for mediator $mediatorId")
                .asRuntimeException
            case Some(traffic) if traffic.mapping.totalExtraTrafficLimit != PositiveLong.MaxValue =>
              throw Status.FAILED_PRECONDITION
                .withDescription(
                  show"Mediator $mediatorId does not have unlimited traffic limit, current limit: abc"
                )
                .asRuntimeException()
            case _ => ()
          },
        logger,
      )
    } yield ()
  }

  private def isCompleted(
      svParty: PartyId,
      svcRules: Contract.Has[SvcRules.ContractId, SvcRules],
  ): Option[definitions.GetSvOnboardingStatusResponse] = {
    Option.when(SvApp.isSvcMemberParty(svParty, svcRules))(
      definitions.SvOnboardingStateCompleted(
        state = "completed",
        name = svcRules.payload.members.get(svParty.toProtoPrimitive).name,
        contractId = Codec.encodeContractId(svcRules.contractId),
      )
    )
  }

  private def isCompleted(
      svParty: String,
      svcRules: Contract.Has[SvcRules.ContractId, SvcRules],
  ): Option[definitions.GetSvOnboardingStatusResponse] = {
    Option.when(SvApp.isSvcMemberName(svParty, svcRules))(
      definitions.SvOnboardingStateCompleted(
        state = "completed",
        name = svParty,
        contractId = Codec.encodeContractId(svcRules.contractId),
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
    } yield definitions.SvOnboardingStateRequested(
      state = "requested",
      name = svOnboardingRequest.payload.candidateName,
      contractId = Codec.encodeContractId(svOnboardingRequest.contractId),
      confirmedBy = confirmedBy,
      requiredNumConfirmations = CNThresholds.requiredNumVotes(svcRules),
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
      definitions.SvOnboardingStateConfirmed(
        state = "confirmed",
        name = svOnboardingConfirmed.payload.svName,
        contractId = Codec.encodeContractId(svOnboardingConfirmed.contractId),
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
      definitions.SvOnboardingStateConfirmed(
        state = "confirmed",
        name = svOnboardingConfirmed.payload.svName,
        contractId = Codec.encodeContractId(svOnboardingConfirmed.contractId),
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
      cmds = Seq(
        svcRules.exercise(
          _.exerciseSvcRules_OnboardValidator(
            svParty.toProtoPrimitive,
            candidateParty.toProtoPrimitive,
          )
        ),
        validatorOnboarding.exercise(
          _.exerciseValidatorOnboarding_Match(secret, candidateParty.toProtoPrimitive)
        ),
      ) map (_.update)
      _ <- svcStoreWithIngestion.connection
        .submit(Seq(svParty), Seq(svcParty), cmds)
        .withDomainId(svcRules.domain)
        .noDedup // No command-dedup required, as the ValidatorOnboarding contract is archived
        .yieldUnit()
    } yield ()

  private def startSvOnboarding(
      candidateName: String,
      candidateParty: PartyId,
      candidateParticipantId: ParticipantId,
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
          case QueryResult(offset, None) =>
            EitherT
              .fromEither[Future](
                SvApp.validateCandidateSv(
                  candidateParty,
                  candidateName,
                  svcRules,
                )
              )
              .leftMap(_.getDescription)
              .semiflatMap { _ =>
                val cmd = svcRules.exercise(
                  _.exerciseSvcRules_StartSvOnboarding(
                    candidateName,
                    candidateParty.toProtoPrimitive,
                    candidateParticipantId.toProtoPrimitive,
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
              }
              .value
        }
      } yield outcome
    }
  }
}
