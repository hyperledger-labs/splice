// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.admin.http

import cats.data.{EitherT, OptionT}
import cats.syntax.applicative.*
import cats.syntax.option.*
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.*
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.topology.transaction.SequencerSynchronizerState
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.google.protobuf.ByteString
import io.grpc.Status.Code
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.admin.http.HttpErrorHandler
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.svonboarding.SvOnboardingRequest
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatoronboarding.ValidatorOnboarding
import org.lfdecentralizedtrust.splice.config.Thresholds
import org.lfdecentralizedtrust.splice.environment.*
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyResult
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyTransactionType.AuthorizedState
import org.lfdecentralizedtrust.splice.http.HttpVotesHandler
import org.lfdecentralizedtrust.splice.http.v0.{definitions, sv as v0}
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.QueryResult
import org.lfdecentralizedtrust.splice.store.{ActiveVotesStore, AppStoreWithIngestion}
import org.lfdecentralizedtrust.splice.sv.cometbft.CometBftClient
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig
import org.lfdecentralizedtrust.splice.sv.onboarding.DsoPartyHosting
import org.lfdecentralizedtrust.splice.sv.onboarding.sponsor.DsoPartyMigration
import org.lfdecentralizedtrust.splice.sv.store.{SvDsoStore, SvSvStore}
import org.lfdecentralizedtrust.splice.sv.util.SvUtil.generateRandomOnboardingSecret
import org.lfdecentralizedtrust.splice.sv.util.{Secrets, SvOnboardingToken}
import org.lfdecentralizedtrust.splice.sv.{LocalSynchronizerNode, SvApp}
import org.lfdecentralizedtrust.splice.util.{Codec, Contract}

import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

class HttpSvHandler(
    svUserName: String,
    svStoreWithIngestion: AppStoreWithIngestion[SvSvStore],
    dsoStoreWithIngestion: AppStoreWithIngestion[SvDsoStore],
    isDevNet: Boolean,
    config: SvAppBackendConfig,
    clock: Clock,
    participantAdminConnection: ParticipantAdminConnection,
    localSynchronizerNode: Option[LocalSynchronizerNode],
    retryProvider: RetryProvider,
    dsoPartyMigration: DsoPartyMigration,
    cometBftClient: Option[CometBftClient],
    protected val loggerFactory: NamedLoggerFactory,
    isBftSequencer: Boolean,
    initialRound: String,
)(implicit
    ec: ExecutionContext,
    protected val tracer: Tracer,
) extends v0.SvHandler[TraceContext]
    with Spanning
    with NamedLogging
    with HttpVotesHandler {

  private val svStore = svStoreWithIngestion.store
  private val dsoStore = dsoStoreWithIngestion.store
  private val svParty = dsoStore.key.svParty
  private val dsoParty = dsoStore.key.dsoParty

  override protected val votesStore: ActiveVotesStore = dsoStore
  override protected val workflowId: String = this.getClass.getSimpleName

  def onboardValidator(
      respond: v0.SvResource.OnboardValidatorResponse.type
  )(
      body: definitions.OnboardValidatorRequest
  )(extracted: TraceContext): Future[v0.SvResource.OnboardValidatorResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.onboardValidator") { _ => _ =>
      Codec.decode(Codec.Party)(body.partyId) match {
        case Right(partyId) =>
          val providedSecret = Secrets.decodeValidatorOnboardingSecret(body.secret, svParty)
          if (providedSecret.sponsoringSv == svParty) {
            svStore.lookupValidatorOnboardingBySecret(providedSecret.secret).flatMap {
              case None =>
                svStore.lookupUsedSecret(providedSecret.secret).flatMap {
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
                val storedSecret =
                  Secrets.decodeValidatorOnboardingSecret(vo.payload.candidateSecret, svParty);

                if (storedSecret.partyHint.exists(_ != partyId.uid.identifier.str)) {
                  Future.failed(
                    HttpErrorHandler.badRequest(
                      s"The onboarding secret entered does not match the secret issued for validatorPartyHint: ${storedSecret.partyHint
                          .getOrElse("<missing>")}"
                    )
                  )
                } else {
                  // Check whether a validator license already exists for this party,
                  // because when recovering from an ACS snapshot "used secret" information will get lost.
                  dsoStore
                    .lookupValidatorLicenseWithOffset(PartyId.tryFromProtoPrimitive(body.partyId))
                    .flatMap {
                      case QueryResult(_, Some(_)) =>
                        // This validator is already onboarded - nothing to do
                        Future.successful(v0.SvResource.OnboardValidatorResponseOK)
                      case QueryResult(_, None) =>
                        for {
                          // We retry here because this mutates the AmuletRules and rounds contracts,
                          // which can lead to races.
                          _ <- retryProvider.retryForClientCalls(
                            "onboard_validator",
                            "onboard validator via DsoRules",
                            onboardValidator(
                              partyId,
                              Secrets.encodeValidatorOnboardingSecret(storedSecret),
                              vo,
                              body.version,
                              body.contactPoint,
                            ),
                            logger,
                          )
                        } yield v0.SvResource.OnboardValidatorResponseOK
                    }
                }
            }
          } else {
            Future.failed(
              HttpErrorHandler.badRequest(
                s"Secret is for SV ${providedSecret.sponsoringSv} but this SV is ${svParty}, validate your SV sponsor URL"
              )
            )
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
            dsoRules <- dsoStore.getDsoRules()
            isCandidatePartyHostedOnParticipant <- participantAdminConnection
              .listPartyToParticipant(
                TopologyStoreId.Synchronizer(dsoRules.domain).some,
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
                // Conflict instead of not authorized because this can happen if our participant just has not yet caught up
                // and the client can just retry on that.
                Future.failed(
                  HttpErrorHandler.conflict(
                    s"Candidate party ${token.candidateParty} is not authorized by participant ${token.candidateParticipantId}"
                  )
                )
              else
                SvApp
                  .isApprovedSvIdentity(
                    token.candidateName,
                    token.candidateParty,
                    body.token,
                    config,
                    svStore,
                    logger,
                  ) match {
                  case Left(reason) =>
                    Future.failed(
                      HttpErrorHandler.unauthorized(
                        s"Could not approve SV Identity because of reason: $reason"
                      )
                    )
                  case Right(_) =>
                    // We retry here because the DsoRules can change while attempting this.
                    retryProvider
                      .retryForClientCalls(
                        "start_sv_onboarding",
                        s"start SV ${token.candidateName} onboarding via DsoRules",
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
            dsoRules <- dsoStore.getDsoRules()
            result <- OptionT
              .fromOption[Future](
                isCompleted(svPartyOrName, dsoRules)
              )
              .orElse(isConfirmed(svPartyOrName, dsoStore))
              .orElse(isRequested(svPartyOrName, dsoRules))
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
            dsoRules <- dsoStore.getDsoRules()
            result <- OptionT
              .fromOption[Future](
                isCompleted(svPartyId, dsoRules)
              )
              .orElse(
                isConfirmed(svPartyId, dsoStore)
              )
              .orElse(
                isRequested(svPartyId, dsoRules)
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
        val secret = generateRandomOnboardingSecret(svStore.key.svParty, None)
        val expiresIn = NonNegativeFiniteDuration.ofHours(1)
        dsoStore
          .getDsoRules()
          .flatMap { dsoRules =>
            SvApp
              .prepareValidatorOnboarding(
                secret,
                expiresIn,
                svStoreWithIngestion,
                dsoRules.domain,
                clock,
                logger,
                retryProvider,
              )
          }
          .flatMap {
            case Left(reason) =>
              Future.failed(
                HttpErrorHandler.internalServerError(s"Could not prepare onboarding: $reason")
              )
            case Right(()) =>
              Future.successful(
                v0.SvResource.DevNetOnboardValidatorPrepareResponseOK(secret.toApiResponse)
              )
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

  def getDsoInfo(
      respond: v0.SvResource.GetDsoInfoResponse.type
  )()(extracted: TraceContext): Future[v0.SvResource.GetDsoInfoResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getDsoInfo") { _ => _ =>
      for {
        latestOpenMiningRound <- dsoStore.getLatestActiveOpenMiningRound()
        amuletRules <- dsoStore.getAssignedAmuletRules()
        rulesAndStates <- dsoStore.getDsoRulesWithStateWithSvNodeStates()
        dsoRules = rulesAndStates.dsoRules
      } yield definitions.GetDsoInfoResponse(
        svUser = svUserName,
        svPartyId = svParty.toProtoPrimitive,
        dsoPartyId = dsoParty.toProtoPrimitive,
        votingThreshold = Thresholds.requiredNumVotes(dsoRules),
        latestMiningRound = latestOpenMiningRound.toContractWithState.toHttp,
        amuletRules = amuletRules.toContractWithState.toHttp,
        dsoRules = dsoRules.toHttp,
        svNodeStates = rulesAndStates.svNodeStates.values.map(_.toHttp).toVector,
        initialRound = Some(initialRound),
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
          s"Candidate party is not an sv and no `SvOnboardingConfirmed` for the candidate party is found."
        for {
          isCandidateOnboardingConfirmed <- isOnboardingConfirmed(candidateParty)
          dsoRules <- dsoStore.getDsoRules()
          isCandidateSv = SvApp.isSvParty(candidateParty, dsoRules)
          contracts <- dsoStore.lookupSvOnboardingConfirmedByParty(candidateParty)
          candidateParticipantId = contracts
            .getOrElse(
              throw Status.NOT_FOUND
                .withDescription(errorMessage)
                .asRuntimeException()
            )
          res <-
            if (!isCandidateOnboardingConfirmed && !isCandidateSv)
              Future.failed(
                HttpErrorHandler.unauthorized(
                  errorMessage
                )
              )
            else
              authorizeParticipantForHostingDsoParty(
                ParticipantId.tryFromProtoPrimitive(candidateParticipantId.payload.svParticipantId)
              )
        } yield res
      }).fold(errMsg => Future.failed(HttpErrorHandler.badRequest(errMsg)), identity)
    }
  }

  private def authorizeParticipantForHostingDsoParty(
      participantId: ParticipantId
  )(implicit tc: TraceContext): Future[v0.SvResource.OnboardSvPartyMigrationAuthorizeResponse] = {
    dsoPartyMigration
      .authorizeParticipantForHostingDsoParty(
        participantId
      )
      .fold(
        {
          case DsoPartyHosting
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

  def onboardSvSequencer(
      respond: v0.SvResource.OnboardSvSequencerResponse.type
  )(
      body: definitions.OnboardSvSequencerRequest
  )(extracted: TraceContext): Future[v0.SvResource.OnboardSvSequencerResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.onboardSvSequencer") { _ => _ =>
      (for {
        sequencerId <- Codec.decode(Codec.Sequencer)(body.sequencerId)
        sequencerConnection <- localSynchronizerNode
          .map(_.sequencerAdminConnection)
          .toRight("Onboarding sequencer configured to use X nodes but sponsoring SV is not")
      } yield {
        getSequencerOnboardingState(sequencerConnection, sequencerId)
      }).fold(
        errMsg => Future.failed(HttpErrorHandler.badRequest(errMsg)),
        _.map(onboardingState =>
          v0.SvResource.OnboardSvSequencerResponseOK(
            definitions.OnboardSvSequencerResponse(
              Base64.getEncoder.encodeToString(onboardingState.toByteArray)
            )
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
        "wait_onboarding_contract",
        s"wait for onboarding contract for party $party",
        for {
          maybeConfirmed <- dsoStore
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

  /** Returns the sequencing time the first topology transaction where the new sequencer is active */
  private def waitForNewSequencerObservedByExistingSequencer(
      sequencerAdminConnection: SequencerAdminConnection,
      sequencerId: SequencerId,
  )(implicit traceContext: TraceContext): Future[Unit] = {
    for {
      decentralizedSynchronizer <- dsoStore.getDsoRules().map(_.domain)
      _ <- retryProvider.getValueWithRetries(
        RetryFor.WaitingOnInitDependency, // the trigger runs every 30s, so this should be enough to observe the new sequencer
        "sequencer_added_to_topology_state",
        "New sequencer is observed in SequencerSynchronizerState through existing sequencer",
        sequencerAdminConnection
          .listSequencerSynchronizerState(
            decentralizedSynchronizer,
            store.TimeQuery.Range(None, None),
            AuthorizedState,
          )
          .map { result =>
            result
              .sortBy(_.base.serial)
              .foldLeft[Option[TopologyResult[SequencerSynchronizerState]]](None) {
                case (_, newMapping) if !newMapping.mapping.allSequencers.contains(sequencerId) =>
                  None
                case (None, newMapping) if newMapping.mapping.allSequencers.contains(sequencerId) =>
                  Some(newMapping)
                case (Some(mapping), newMapping)
                    if newMapping.mapping.allSequencers.contains(sequencerId) =>
                  Some(mapping)
                case _ => None
              } match {
              case Some(activeMapping) =>
                activeMapping.base.validFrom
              case None =>
                throw Status.NOT_FOUND
                  .withDescription(
                    s"Sequencer $sequencerId is not in active sequencers $result"
                  )
                  .asRuntimeException()
            }
          },
        logger,
      )
      _ <-
        if (isBftSequencer) {
          retryProvider.waitUntil(
            RetryFor.WaitingOnInitDependency,
            "sequencer_ordering_topology",
            s"Wait for $sequencerId to be in the ordering topology",
            sequencerAdminConnection
              .getSequencerOrderingTopology()
              .map(topology =>
                if (topology.sequencerIds.contains(sequencerId)) ()
                else
                  throw Status.NOT_FOUND
                    .withDescription(
                      s"Sequencer $sequencerId is not in the ordering topology $topology"
                    )
                    .asRuntimeException()
              ),
            logger,
          )
        } else Future.unit
    } yield ()
  }

  private def getSequencerOnboardingState(
      sequencerAdminConnection: SequencerAdminConnection,
      sequencerId: SequencerId,
  )(implicit traceContext: TraceContext): Future[ByteString] = {
    logger.info(
      s"Waiting for sequencer $sequencerId to be onboarded before querying its onboarding state"
    )
    for {
      _ <- waitForNewSequencerObservedByExistingSequencer(
        sequencerAdminConnection,
        sequencerId,
      )
      _ = logger.info(s"Downloading sequencer onboarding state for $sequencerId")
      onboardingState <- sequencerAdminConnection.getOnboardingState(sequencerId)
    } yield onboardingState
  }

  private def isCompleted(
      svParty: PartyId,
      dsoRules: Contract.Has[DsoRules.ContractId, DsoRules],
  ): Option[definitions.GetSvOnboardingStatusResponse] = {
    Option.when(SvApp.isSvParty(svParty, dsoRules))(
      definitions.SvOnboardingStateCompleted(
        state = "completed",
        name = dsoRules.payload.svs.get(svParty.toProtoPrimitive).name,
        contractId = Codec.encodeContractId(dsoRules.contractId),
      )
    )
  }

  private def isCompleted(
      svParty: String,
      dsoRules: Contract.Has[DsoRules.ContractId, DsoRules],
  ): Option[definitions.GetSvOnboardingStatusResponse] = {
    Option.when(SvApp.isSvName(svParty, dsoRules))(
      definitions.SvOnboardingStateCompleted(
        state = "completed",
        name = svParty,
        contractId = Codec.encodeContractId(dsoRules.contractId),
      )
    )
  }

  private def isRequested(
      svParty: PartyId,
      dsoRules: Contract.Has[DsoRules.ContractId, DsoRules],
  )(implicit tc: TraceContext): OptionT[Future, definitions.GetSvOnboardingStatusResponse] = {
    for {
      svOnboardingRequest <- OptionT(dsoStore.lookupSvOnboardingRequestByCandidateParty(svParty))
      result <- getOnboardedStatus(svOnboardingRequest, dsoRules)
    } yield result
  }

  private def isRequested(
      svParty: String,
      dsoRules: Contract.Has[DsoRules.ContractId, DsoRules],
  )(implicit tc: TraceContext): OptionT[Future, definitions.GetSvOnboardingStatusResponse] = {
    for {
      svOnboardingRequest <- OptionT(dsoStore.lookupSvOnboardingRequestByCandidateName(svParty))
      result <- getOnboardedStatus(svOnboardingRequest, dsoRules)
    } yield result
  }

  private def getOnboardedStatus(
      svOnboardingRequest: Contract[SvOnboardingRequest.ContractId, SvOnboardingRequest],
      dsoRules: Contract.Has[DsoRules.ContractId, DsoRules],
  )(implicit tc: TraceContext) = {
    val candidateName = svOnboardingRequest.payload.candidateName
    val weight = config
      .rewardWeightBpsOf(candidateName)
      .getOrElse(
        throw Status.NOT_FOUND
          .withDescription(
            s"Candidate $candidateName not found in approved SV identities."
          )
          .asRuntimeException()
      )
    for {
      confirmations <- OptionT.liftF(
        dsoStore.listSvOnboardingConfirmations(svOnboardingRequest, weight)
      )
      confirmedBy = confirmations
        .map(c =>
          dsoRules.payload.svs.asScala.get(c.payload.confirmer) match {
            case Some(sv) => sv.name
            case None => c.payload.confirmer
          }
        )
        .toVector
    } yield definitions.SvOnboardingStateRequested(
      state = "requested",
      name = candidateName,
      contractId = Codec.encodeContractId(svOnboardingRequest.contractId),
      confirmedBy = confirmedBy,
      requiredNumConfirmations = Thresholds.requiredNumVotes(dsoRules),
    )
  }

  private def isConfirmed(
      svParty: PartyId,
      dsoStore: SvDsoStore,
  )(implicit tc: TraceContext): OptionT[Future, definitions.GetSvOnboardingStatusResponse] = {
    OptionT(
      dsoStore
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
      dsoStore: SvDsoStore,
  )(implicit tc: TraceContext): OptionT[Future, definitions.GetSvOnboardingStatusResponse] = {
    OptionT(
      dsoStore
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
      version: Option[String],
      contactPoint: Option[String],
  )(implicit tc: TraceContext): Future[Unit] =
    for {
      dsoRules <- dsoStore.getDsoRules()
      now = clock.now
      cmds = Seq(
        dsoRules.exercise(
          _.exerciseDsoRules_OnboardValidator(
            svParty.toProtoPrimitive,
            candidateParty.toProtoPrimitive,
            version.toJava,
            contactPoint.toJava,
          )
        ),
        validatorOnboarding.exercise(
          _.exerciseValidatorOnboarding_Match(secret, candidateParty.toProtoPrimitive)
        ),
      ) map (_.update)
      _ <- dsoStoreWithIngestion
        .connection(SpliceLedgerConnectionPriority.Low)
        .submit(Seq(svParty), Seq(dsoParty), cmds)
        .withSynchronizerId(dsoRules.domain)
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
        dsoRules <- dsoStore.getDsoRules()
        lookup <- dsoStore.lookupSvOnboardingRequestByTokenWithOffset(token)
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
                  dsoRules,
                )
              )
              .leftMap(_.getDescription)
              .semiflatMap { _ =>
                val cmd = dsoRules.exercise(
                  _.exerciseDsoRules_StartSvOnboarding(
                    candidateName,
                    candidateParty.toProtoPrimitive,
                    candidateParticipantId.toProtoPrimitive,
                    token,
                    svParty.toProtoPrimitive,
                  )
                )
                dsoStoreWithIngestion
                  .connection(SpliceLedgerConnectionPriority.Low)
                  .submit(actAs = Seq(svParty), readAs = Seq(dsoParty), cmd)
                  .withDedup(
                    commandId = SpliceLedgerConnection.CommandId(
                      "org.lfdecentralizedtrust.splice.sv.startSvOnboarding",
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
