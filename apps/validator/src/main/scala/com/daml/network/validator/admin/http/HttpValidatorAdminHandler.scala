// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.validator.admin.http

import cats.syntax.either.*
import com.daml.network.auth.AuthExtractor.TracedUser
import com.daml.network.codegen.java.splice
import com.daml.network.codegen.java.splice.amulet.{Amulet, LockedAmulet}
import com.daml.network.codegen.java.splice.amuletrules.transferinput.InputAmulet
import com.daml.network.codegen.java.splice.transferpreapproval as transferPreapprovalCodegen
import com.daml.network.codegen.java.splice.wallet.externalparty.ExternalPartySetupProposal
import com.daml.network.environment.{
  BaseLedgerConnection,
  ParticipantAdminConnection,
  RetryProvider,
  SpliceLedgerConnection,
}
import com.daml.network.http.v0.definitions.{
  CreateExternalPartySetupProposalRequest,
  GenerateExternalPartyTopologyRequest,
  GenerateExternalPartyTopologyResponse,
  PrepareAcceptExternalPartySetupProposalRequest,
  PrepareTransferPreapprovalSendRequest,
  SubmitAcceptExternalPartySetupProposalRequest,
  SubmitExternalPartyTopologyRequest,
  SubmitTransferPreapprovalSendRequest,
  TopologyTx,
}
import com.daml.network.http.v0.validator_admin.ValidatorAdminResource
import com.daml.network.http.v0.{definitions, validator_admin as v0}
import com.daml.network.identities.NodeIdentitiesStore
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.scan.admin.api.client.ScanConnection.GetAmuletRulesDomain
import com.daml.network.store.AppStoreWithIngestion
import com.daml.network.store.MultiDomainAcsStore.{IngestionFilter, QueryResult}
import com.daml.network.util.*
import com.daml.network.validator.config.ValidatorAppBackendConfig
import com.daml.network.validator.migration.DomainMigrationDumpGenerator
import com.daml.network.validator.store.ValidatorStore
import com.daml.network.validator.util.ValidatorUtil
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.crypto.*
import com.digitalasset.canton.crypto.SignatureFormat.Raw
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.sequencing.GrpcSequencerConnection
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.topology.store.TopologyStoreId
import com.digitalasset.canton.topology.store.TopologyStoreId.AuthorizedStore
import com.digitalasset.canton.topology.transaction.*
import com.digitalasset.canton.topology.transaction.SignedTopologyTransaction.GenericSignedTopologyTransaction
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.digitalasset.canton.util.HexString
import com.digitalasset.canton.version.ProtocolVersion
import com.google.protobuf.ByteString
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import java.time.Instant
import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class HttpValidatorAdminHandler(
    storeWithIngestion: AppStoreWithIngestion[ValidatorStore],
    identitiesStore: NodeIdentitiesStore,
    validatorUserName: String,
    validatorWalletUserName: Option[String],
    getAmuletRulesDomain: GetAmuletRulesDomain,
    scanConnection: ScanConnection,
    participantAdminConnection: ParticipantAdminConnection,
    config: ValidatorAppBackendConfig,
    retryProvider: RetryProvider,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends v0.ValidatorAdminHandler[TracedUser]
    with Spanning
    with NamedLogging {
  private val workflowId = this.getClass.getSimpleName
  private val store = storeWithIngestion.store
  private val dumpGenerator = new DomainMigrationDumpGenerator(
    participantAdminConnection,
    retryProvider,
    loggerFactory,
  )

  def onboardUser(
      respond: v0.ValidatorAdminResource.OnboardUserResponse.type
  )(
      body: definitions.OnboardUserRequest
  )(tuser: TracedUser): Future[v0.ValidatorAdminResource.OnboardUserResponse] = {
    implicit val TracedUser(_, tracedContext) = tuser
    withSpan(s"$workflowId.onboardUser") { _ => span =>
      val name = body.name
      span.setAttribute("name", name)
      onboard(name, body.partyId.map(PartyId.tryFromProtoPrimitive)).map(p =>
        definitions.OnboardUserResponse(p)
      )
    }
  }

  def listUsers(
      respond: v0.ValidatorAdminResource.ListUsersResponse.type
  )()(tuser: TracedUser): Future[
    v0.ValidatorAdminResource.ListUsersResponse
  ] = {
    implicit val TracedUser(_, tracedContext) = tuser
    withSpan(s"$workflowId.listUsers") { _ => _ =>
      // TODO(#12550): move away from tracking onboarded users via on-ledger contracts, and create only one WalletAppInstall per user-party
      store.listUsers().map(us => definitions.ListUsersResponse(us.toVector))
    }
  }

  def offboardUser(
      respond: v0.ValidatorAdminResource.OffboardUserResponse.type
  )(username: String)(tuser: TracedUser): Future[
    v0.ValidatorAdminResource.OffboardUserResponse
  ] = {
    implicit val TracedUser(_, tracedContext) = tuser
    withSpan(s"$workflowId.offboardUser") { _ => _ =>
      offboardUser(username)
        .map(_ => v0.ValidatorAdminResource.OffboardUserResponse.OK)
        .recover({
          case e: StatusRuntimeException if e.getStatus.getCode == io.grpc.Status.Code.NOT_FOUND =>
            v0.ValidatorAdminResource
              .OffboardUserResponseNotFound(definitions.ErrorResponse(e.getMessage()))
        })
    }
  }

  def dumpParticipantIdentities(
      respond: v0.ValidatorAdminResource.DumpParticipantIdentitiesResponse.type
  )()(
      tuser: TracedUser
  ): Future[v0.ValidatorAdminResource.DumpParticipantIdentitiesResponse] = {
    implicit val TracedUser(_, tracedContext) = tuser
    withSpan(s"$workflowId.dumpParticipantIdentities") { _ => _ =>
      for {
        response <- identitiesStore.getNodeIdentitiesDump()
      } yield v0.ValidatorAdminResource.DumpParticipantIdentitiesResponse.OK(response.toHttp)
    }
  }

  override def getValidatorDomainDataSnapshot(
      respond: v0.ValidatorAdminResource.GetValidatorDomainDataSnapshotResponse.type
  )(timestamp: String, migrationId: Option[Long], force: Option[Boolean])(
      tuser: TracedUser
  ): Future[v0.ValidatorAdminResource.GetValidatorDomainDataSnapshotResponse] = {
    implicit val TracedUser(_, tracedContext) = tuser
    withSpan(s"$workflowId.getValidatorDomainDataSnapshot") { _ => _ =>
      for {
        domainId <- getAmuletRulesDomain()(tracedContext)
        res <- dumpGenerator
          .getDomainDataSnapshot(
            Instant.parse(timestamp),
            domainId,
            // TODO(#9731): get migration id from scan instead of configuring here
            migrationId getOrElse (config.domainMigrationId + 1),
            force.getOrElse(false),
          )
          .map { response =>
            v0.ValidatorAdminResource.GetValidatorDomainDataSnapshotResponse.OK(
              definitions
                .GetValidatorDomainDataSnapshotResponse(response.toHttp, response.migrationId)
            )
          }
      } yield res
    }
  }

  override def getDecentralizedSynchronizerConnectionConfig(
      respond: v0.ValidatorAdminResource.GetDecentralizedSynchronizerConnectionConfigResponse.type
  )()(
      tuser: TracedUser
  ): Future[v0.ValidatorAdminResource.GetDecentralizedSynchronizerConnectionConfigResponse] = {
    implicit val TracedUser(_, tracedContext) = tuser
    withSpan(s"$workflowId.getDecentralizedSynchronizerConnectionConfig") { _ => _ =>
      for {
        connectionConfig <- participantAdminConnection.getDomainConnectionConfig(
          config.domains.global.alias
        )
      } yield v0.ValidatorAdminResource.GetDecentralizedSynchronizerConnectionConfigResponse.OK(
        definitions.GetDecentralizedSynchronizerConnectionConfigResponse(
          definitions.SequencerConnections(
            connectionConfig.sequencerConnections.aliasToConnection.values.map {
              case GrpcSequencerConnection(
                    endpoints,
                    transportSecurity,
                    _,
                    sequencerAlias,
                  ) =>
                definitions.SequencerAliasToConnections(
                  sequencerAlias.toProtoPrimitive,
                  endpoints.map(_.toString).toVector,
                  transportSecurity,
                )
            }.toVector,
            connectionConfig.sequencerConnections.sequencerTrustThreshold.value,
            definitions.SequencerSubmissionRequestAmplification(
              connectionConfig.sequencerConnections.submissionRequestAmplification.factor.value,
              connectionConfig.sequencerConnections.submissionRequestAmplification.patience.duration.toSeconds,
            ),
          )
        )
      )
    }
  }

  private def onboard(name: String, partyId: Option[PartyId])(implicit
      traceContext: TraceContext
  ): Future[String] = {
    ValidatorUtil
      .onboard(
        name,
        partyId,
        storeWithIngestion,
        validatorUserName,
        getAmuletRulesDomain,
        participantAdminConnection,
        retryProvider,
        logger,
      )
      .map(p => p.filterString)
  }

  private def offboardUser(
      user: String
  )(implicit ec: ExecutionContext, traceContext: TraceContext): Future[Unit] = {
    ValidatorUtil.offboard(
      user,
      storeWithIngestion,
      validatorUserName,
      validatorWalletUserName,
      retryProvider,
      logger,
    )
  }

  override def generateExternalPartyTopology(
      respond: ValidatorAdminResource.GenerateExternalPartyTopologyResponse.type
  )(body: GenerateExternalPartyTopologyRequest)(
      tuser: TracedUser
  ): Future[ValidatorAdminResource.GenerateExternalPartyTopologyResponse] = {
    implicit val TracedUser(_, tracedContext) = tuser
    withSpan(s"$workflowId.generateExternalPartyTopology") { _ => _ =>
      val publicKey = ValidatorUtil.signingPublicKeyFromHexEd25119(body.publicKey)
      ValidatorUtil
        .createTopologyMappings(
          partyHint = body.partyHint,
          publicKey = publicKey,
          participantAdminConnection = participantAdminConnection,
        )
        .map { topologyTxs =>
          ValidatorAdminResource.GenerateExternalPartyTopologyResponse.OK(
            GenerateExternalPartyTopologyResponse(
              topologyTxs
                .map(tx =>
                  TopologyTx(
                    topologyTx = Base64.getEncoder.encodeToString(tx.toByteArray),
                    hash = tx.hash.hash.toHexString,
                  )
                )
                .toVector
            )
          )
        }
    }
  }

  private def decodeSignedTopologyTx(
      publicKey: SigningPublicKey,
      topologyTx: definitions.SignedTopologyTx,
  ): GenericSignedTopologyTransaction =
    SignedTopologyTransaction(
      transaction = TopologyTransaction
        .fromTrustedByteString(
          ByteString.copyFrom(Base64.getDecoder.decode(topologyTx.topologyTx))
        )
        .valueOr(error =>
          throw Status.INVALID_ARGUMENT
            .withDescription(s"failed to construct topology transaction: $error")
            .asRuntimeException()
        ),
      signatures = NonEmpty.mk(
        Set,
        Signature(
          Raw,
          HexString
            .parseToByteString(topologyTx.signedHash)
            .getOrElse(
              throw Status.INVALID_ARGUMENT
                .withDescription(
                  s"Failed to decode hex-encoded tx signature: ${topologyTx.signedHash}"
                )
                .asRuntimeException
            ),
          signedBy = publicKey.fingerprint,
        ),
      ),
      isProposal = true,
    )(
      SignedTopologyTransaction.supportedProtoVersions
        .protocolVersionRepresentativeFor(ProtocolVersion.dev)
    )

  override def submitExternalPartyTopology(
      respond: ValidatorAdminResource.SubmitExternalPartyTopologyResponse.type
  )(body: SubmitExternalPartyTopologyRequest)(
      tuser: TracedUser
  ): Future[ValidatorAdminResource.SubmitExternalPartyTopologyResponse] = {
    implicit val TracedUser(_, tracedContext) = tuser
    withSpan(s"$workflowId.submitExternalPartyTopology") { _ => _ =>
      val publicKey = ValidatorUtil.signingPublicKeyFromHexEd25119(body.publicKey)
      for {
        _ <- participantAdminConnection.addTopologyTransactions(
          store = TopologyStoreId.AuthorizedStore,
          txs = body.signedTopologyTxs.map(decodeSignedTopologyTx(publicKey, _)),
        )
        partyId = body.signedTopologyTxs
          .map(decodeSignedTopologyTx(publicKey, _))
          .flatMap(_.selectMapping[PartyToParticipant])
          .headOption
          .getOrElse(
            throw Status.INVALID_ARGUMENT
              .withDescription(s"Failed to determine party id from PartyToParticipant transaction")
              .asRuntimeException()
          )
          .transaction
          .mapping
          .partyId
        participantId <- participantAdminConnection.getParticipantId()
        // The PartyToParticipant mapping requires both the external signature from the party namespace but also one from the participant which we create here
        _ <- participantAdminConnection.proposeMapping(
          AuthorizedStore,
          PartyToParticipant
            .create(
              partyId = partyId,
              domainId = None,
              threshold = PositiveInt.one,
              participants = Seq(
                HostingParticipant(participantId, ParticipantPermission.Submission)
              ),
              groupAddressing = false,
            )
            .valueOr(error =>
              throw Status.INVALID_ARGUMENT
                .withDescription(s"failed to construct party to participant mapping: $error")
                .asRuntimeException()
            ),
          signedBy = participantId.fingerprint,
          serial = PositiveInt.one,
          isProposal = true,
          change = TopologyChangeOp.Replace,
        )
        _ <- participantAdminConnection
          .listPartyToParticipant(
            filterStore = TopologyStoreId.AuthorizedStore.filterName,
            filterParty = partyId.filterString,
          )
          .map { txs =>
            txs.headOption.getOrElse(
              throw Status.INVALID_ARGUMENT
                .withDescription(
                  s"No PartyToParticipant state in Authorized Store for $partyId, check the Canton logs to find why the transactions got rejected"
                )
                .asRuntimeException
            )
          }
        _ <- participantAdminConnection
          .listPartyToKey(
            filterParty = partyId.filterString
          )
          .map { txs =>
            txs.headOption.getOrElse(
              throw Status.INVALID_ARGUMENT
                .withDescription(
                  s"No PartyToKey mapping in Authorized Store for $partyId, check the Canton logs to find why the transactions got rejected"
                )
                .asRuntimeException
            )
          }
        _ <- storeWithIngestion.connection.waitForPartyOnLedgerApi(partyId)
        _ <- storeWithIngestion.connection.grantUserRights(
          config.ledgerApiUser,
          Seq(),
          Seq(partyId),
        )
      } yield ValidatorAdminResource.SubmitExternalPartyTopologyResponseOK(
        definitions.SubmitExternalPartyTopologyResponse(Codec.encode(partyId))
      )

    }
  }

  override def createExternalPartySetupProposal(
      respond: ValidatorAdminResource.CreateExternalPartySetupProposalResponse.type
  )(body: CreateExternalPartySetupProposalRequest)(
      tuser: TracedUser
  ): Future[ValidatorAdminResource.CreateExternalPartySetupProposalResponse] = {
    implicit val TracedUser(_, tracedContext) = tuser
    val userParty = PartyId.tryFromProtoPrimitive(body.userPartyId)
    val validatorServiceParty = store.key.validatorParty
    val dsoParty = store.key.dsoParty
    for {
      domainId <- getAmuletRulesDomain()(tracedContext)
      result <- store.lookupExternalPartySetupProposalByUserPartyWithOffset(userParty).flatMap {
        case QueryResult(_, Some(c)) =>
          Future.successful(
            ValidatorAdminResource.CreateExternalPartySetupProposalResponse.Conflict(
              definitions.ErrorResponse(
                s"ExternalPartySetupProposal contract already exists: ${c.contract.contractId}"
              )
            )
          )
        case QueryResult(offsetSP, None) => {
          store.lookupTransferPreapprovalByReceiverPartyWithOffset(userParty).flatMap {
            case QueryResult(_, Some(c)) =>
              Future.successful(
                ValidatorAdminResource.CreateExternalPartySetupProposalResponse.Conflict(
                  definitions.ErrorResponse(
                    s"TransferPreapproval contract already exists: ${c.contract.contractId}"
                  )
                )
              )
            case QueryResult(offsetTP, None) =>
              storeWithIngestion.connection
                .submit(
                  Seq(validatorServiceParty),
                  Seq(validatorServiceParty),
                  ExternalPartySetupProposal.create(
                    validatorServiceParty.toProtoPrimitive,
                    userParty.toProtoPrimitive,
                    dsoParty.toProtoPrimitive,
                  ),
                )
                .withDedup(
                  commandId = SpliceLedgerConnection.CommandId(
                    "com.daml.network.validator.createExternalPartySetupProposal",
                    Seq(validatorServiceParty),
                    BaseLedgerConnection.sanitizeUserIdToPartyString(body.userPartyId),
                  ),
                  deduplicationOffset = Ordering.String.min(
                    offsetSP,
                    offsetTP,
                  ),
                )
                .withDomainId(domainId)
                .yieldResult()
                .map(contract =>
                  ValidatorAdminResource.CreateExternalPartySetupProposalResponse.OK(
                    definitions.CreateExternalPartySetupProposalResponse(
                      contract.contractId.contractId
                    )
                  )
                )
          }
        }

      }

    } yield result
  }

  override def listExternalPartySetupProposal(
      respond: ValidatorAdminResource.ListExternalPartySetupProposalResponse.type
  )()(
      tuser: TracedUser
  ): Future[ValidatorAdminResource.ListExternalPartySetupProposalResponse] = {
    implicit val TracedUser(_, tracedContext) = tuser
    for {
      proposals <- store.listExternalPartySetupProposals()
    } yield definitions.ListExternalPartySetupProposalsResponse(
      proposals.map(p => p.toHttp).toVector
    )
  }

  override def prepareAcceptExternalPartySetupProposal(
      respond: ValidatorAdminResource.PrepareAcceptExternalPartySetupProposalResponse.type
  )(body: PrepareAcceptExternalPartySetupProposalRequest)(
      tuser: TracedUser
  ): Future[ValidatorAdminResource.PrepareAcceptExternalPartySetupProposalResponse] = {
    implicit val TracedUser(_, tracedContext) = tuser
    val userParty = PartyId.tryFromProtoPrimitive(body.userPartyId)
    for {
      domainId <- getAmuletRulesDomain()(tracedContext)
      result <- store.lookupExternalPartySetupProposalByUserPartyWithOffset(userParty).flatMap {
        case QueryResult(_, Some(contractWithState)) => {
          if (contractWithState.contract.contractId.contractId != body.contractId)
            Future.successful(
              ValidatorAdminResource.PrepareAcceptExternalPartySetupProposalResponse.BadRequest(
                definitions.ErrorResponse("Found contract does not match provided contractId.")
              )
            )
          else {
            val commands = contractWithState.toAssignedContract
              .getOrElse(
                throw Status.Code.FAILED_PRECONDITION.toStatus
                  .withDescription(s"Invalid contract")
                  .asRuntimeException()
              )
              .exercise(_.exerciseExternalPartySetupProposal_Accept())
              .update
              .commands()
              .asScala
              .toSeq
            storeWithIngestion.connection
              .prepareSubmission(
                Some(domainId),
                Seq(userParty),
                Seq(userParty),
                commands,
                DisclosedContracts.Empty,
              )
              .flatMap { r =>
                Future.successful(
                  ValidatorAdminResource.PrepareAcceptExternalPartySetupProposalResponse.OK(
                    definitions.PrepareAcceptExternalPartySetupProposalResponse(
                      Base64.getEncoder.encodeToString(r.preparedTransaction.toByteArray),
                      HexString.toHexString(r.preparedTransactionHash),
                    )
                  )
                )
              }
          }
        }
        case QueryResult(_, None) =>
          Future.successful(
            ValidatorAdminResource.PrepareAcceptExternalPartySetupProposalResponse.NotFound(
              definitions.ErrorResponse("Contract not found.")
            )
          )
      }
    } yield result
  }

  override def submitAcceptExternalPartySetupProposal(
      respond: ValidatorAdminResource.SubmitAcceptExternalPartySetupProposalResponse.type
  )(body: SubmitAcceptExternalPartySetupProposalRequest)(
      tuser: TracedUser
  ): Future[ValidatorAdminResource.SubmitAcceptExternalPartySetupProposalResponse] = {
    implicit val TracedUser(_, tracedContext) = tuser
    val userParty = PartyId.tryFromProtoPrimitive(body.submission.partyId)
    for {
      updateId <- ValidatorUtil.submitAsExternalParty(
        storeWithIngestion.connection,
        body.submission,
      )
      result <- store.lookupTransferPreapprovalByReceiverPartyWithOffset(userParty).flatMap {
        case QueryResult(_, Some(c)) =>
          Future.successful(c.contractId)
        case QueryResult(_, None) =>
          throw Status.Code.FAILED_PRECONDITION.toStatus
            .withDescription(
              s"${transferPreapprovalCodegen.TransferPreapproval.COMPANION.TEMPLATE_ID.getEntityName} contract was not created."
            )
            .asRuntimeException()
      }
    } yield ValidatorAdminResource.SubmitAcceptExternalPartySetupProposalResponse.OK(
      definitions.SubmitAcceptExternalPartySetupProposalResponse(result.contractId, updateId)
    )
  }

  override def listTransferPreapproval(
      respond: ValidatorAdminResource.ListTransferPreapprovalResponse.type
  )()(tuser: TracedUser): Future[ValidatorAdminResource.ListTransferPreapprovalResponse] = {
    implicit val TracedUser(_, tracedContext) = tuser
    for {
      preapprovals <- store.listTransferPreapprovals()
    } yield definitions.ListTransferPreapprovalsResponse(
      preapprovals.map(p => p.toHttp).toVector
    )
  }

  override def prepareTransferPreapprovalSend(
      respond: ValidatorAdminResource.PrepareTransferPreapprovalSendResponse.type
  )(body: PrepareTransferPreapprovalSendRequest)(
      tuser: TracedUser
  ): Future[ValidatorAdminResource.PrepareTransferPreapprovalSendResponse] = {
    implicit val TracedUser(_, tracedContext) = tuser
    val senderParty = PartyId.tryFromProtoPrimitive(body.senderPartyId)
    val receiverParty = PartyId.tryFromProtoPrimitive(body.receiverPartyId)
    for {
      domainId <- getAmuletRulesDomain()(tracedContext)
      result <- store.lookupTransferPreapprovalByReceiverPartyWithOffset(receiverParty).flatMap {
        case QueryResult(_, None) => {
          Future.failed(
            Status.INVALID_ARGUMENT
              .withDescription(s"Receiver $receiverParty does not have a TransferPreapproval")
              .asRuntimeException
          )
        }
        case QueryResult(_, Some(contractWithState)) =>
          scanConnection
            .getPaymentTransferContext(
              storeWithIngestion.connection,
              PartyId.tryFromProtoPrimitive(contractWithState.contract.payload.provider),
            )
            .flatMap { paymentTransferContext =>
              getExternalPartyAmulets(senderParty).flatMap { amuletContracts =>
                val transferInput = amuletContracts._1
                  .map[splice.amuletrules.TransferInput](c => new InputAmulet(c.contractId))

                val commands = contractWithState.toAssignedContract
                  .getOrElse(
                    throw Status.Code.FAILED_PRECONDITION.toStatus
                      .withDescription(s"Invalid contract")
                      .asRuntimeException()
                  )
                  .exercise(
                    _.exerciseTransferPreapproval_Send(
                      paymentTransferContext._1,
                      transferInput.asJava,
                      body.amount.bigDecimal,
                      senderParty.toProtoPrimitive,
                    )
                  )
                  .update
                  .commands()
                  .asScala
                  .toSeq

                storeWithIngestion.connection
                  .prepareSubmission(
                    Some(domainId),
                    Seq(senderParty),
                    Seq(senderParty),
                    commands,
                    storeWithIngestion.connection
                      .disclosedContracts(contractWithState)
                      .merge(paymentTransferContext._2),
                  )
                  .flatMap { r =>
                    Future.successful(
                      ValidatorAdminResource.PrepareTransferPreapprovalSendResponse.OK(
                        definitions.PrepareTransferPreapprovalSendResponse(
                          Base64.getEncoder.encodeToString(r.preparedTransaction.toByteArray),
                          HexString.toHexString(r.preparedTransactionHash),
                        )
                      )
                    )
                  }
              }
            }
      }
    } yield result
  }

  override def submitTransferPreapprovalSend(
      respond: ValidatorAdminResource.SubmitTransferPreapprovalSendResponse.type
  )(body: SubmitTransferPreapprovalSendRequest)(
      tuser: TracedUser
  ): Future[ValidatorAdminResource.SubmitTransferPreapprovalSendResponse] = {
    implicit val TracedUser(_, tracedContext) = tuser
    for {
      updateId <- ValidatorUtil.submitAsExternalParty(
        storeWithIngestion.connection,
        body.submission,
      )
    } yield v0.ValidatorAdminResource.SubmitTransferPreapprovalSendResponseOK(
      definitions.SubmitTransferPreapprovalSendResponse(updateId)
    )
  }

  def getExternalPartyAmulets(partyId: PartyId)(implicit tc: TraceContext): Future[
    (Seq[Contract[Amulet.ContractId, Amulet]], Seq[Contract[LockedAmulet.ContractId, LockedAmulet]])
  ] = {
    for {
      domainId <- getAmuletRulesDomain()(tc)
      participantId <- participantAdminConnection.getParticipantId()
      results <- participantAdminConnection.listPartyToParticipant(
        filterStore = TopologyStoreId.DomainStore(domainId).filterName,
        filterParty = partyId.toProtoPrimitive,
      )
      _ = results match {
        case Seq() =>
          throw Status.NOT_FOUND
            .withDescription(s"Could not find topology mapping for party id $partyId")
            .asRuntimeException
        case Seq(result) =>
          val hostingParticipants = result.mapping.participants.map(_.participantId)
          if (!hostingParticipants.contains(participantId)) {
            throw Status.INVALID_ARGUMENT
              .withDescription(
                s"Party $partyId is not hosted on participant $participantId but on participants $hostingParticipants"
              )
              .asRuntimeException
          }
        case _ =>
          throw Status.INTERNAL
            .withDescription(s"Invalid PartyToParticipant mapping: $results")
            .asRuntimeException
      }
      ledgerEnd <- storeWithIngestion.connection.ledgerEnd()
      acs <- storeWithIngestion.connection.activeContracts(
        IngestionFilter(
          partyId,
          Set(
            PackageQualifiedName(Amulet.TEMPLATE_ID),
            PackageQualifiedName(LockedAmulet.TEMPLATE_ID),
          ),
        ),
        ledgerEnd,
      )
    } yield {
      acs._1.partitionMap { c =>
        (
          Contract.fromCreatedEvent(Amulet.COMPANION)(c.createdEvent),
          Contract.fromCreatedEvent(LockedAmulet.COMPANION)(c.createdEvent),
        ) match {
          case (Some(amulet), _) => Left(amulet)
          case (_, Some(lockedAmulet)) => Right(lockedAmulet)
          case _ =>
            throw Status.INTERNAL
              .withDescription(
                s"Unexpected contract ${c.createdEvent}, expected either Amulet or LockedAmulet"
              )
              .asRuntimeException
        }
      }
    }
  }

  def getExternalPartyBalance(respond: ValidatorAdminResource.GetExternalPartyBalanceResponse.type)(
      partyIdStr: String
  )(tuser: TracedUser): Future[ValidatorAdminResource.GetExternalPartyBalanceResponse] = {
    implicit val TracedUser(_, tc) = tuser
    withSpan(s"$workflowId.getExternalPartyBalance") { implicit tc => _ =>
      val partyId = PartyId.tryFromProtoPrimitive(partyIdStr)
      for {
        openRounds <- scanConnection.getOpenAndIssuingMiningRounds().map(_._1)
        contracts <- getExternalPartyAmulets(partyId)
      } yield {
        val earliestOpenRound = openRounds
          .minByOption(_.payload.round.number)
          .fold(
            throw Status.NOT_FOUND
              .withDescription("No open mining round found")
              .asRuntimeException()
          )(_.payload.round.number)
        val amuletsSummary = contracts._1.foldLeft(HoldingsSummary.Empty) { case (acc, amulet) =>
          acc.addAmulet(amulet.payload, earliestOpenRound)
        }
        val summary = contracts._2.foldLeft(amuletsSummary) { case (acc, amulet) =>
          acc.addLockedAmulet(amulet.payload, earliestOpenRound)
        }
        ValidatorAdminResource.GetExternalPartyBalanceResponse.OK(
          definitions.ExternalPartyBalanceResponse(
            partyId = partyIdStr,
            totalUnlockedCoin = Codec.encode(summary.totalUnlockedCoin),
            totalLockedCoin = Codec.encode(summary.totalLockedCoin),
            totalCoinHoldings = Codec.encode(summary.totalCoinHoldings),
            accumulatedHoldingFeesUnlocked = Codec.encode(summary.accumulatedHoldingFeesUnlocked),
            accumulatedHoldingFeesLocked = Codec.encode(summary.accumulatedHoldingFeesLocked),
            accumulatedHoldingFeesTotal = Codec.encode(summary.accumulatedHoldingFeesTotal),
            totalAvailableCoin = Codec.encode(summary.totalAvailableCoin),
            computedAsOfRound = earliestOpenRound,
          )
        )
      }
    }
  }
}
