// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.admin.http

import cats.implicits.catsSyntaxOptionId
import cats.syntax.either.*
import com.daml.ledger.api.v2.interactive
import org.lfdecentralizedtrust.splice.admin.http.HttpErrorHandler
import org.lfdecentralizedtrust.splice.auth.AuthExtractor.TracedUser
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{Amulet, LockedAmulet}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.{
  TransferPreapproval,
  invalidtransferreason,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.ExternalPartySetupProposal
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.install.amuletoperation.CO_CreateExternalPartySetupProposal
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.install.amuletoperationoutcome
import org.lfdecentralizedtrust.splice.environment.ledger.api.DedupOffset
import org.lfdecentralizedtrust.splice.environment.{
  BaseLedgerConnection,
  PackageVersionSupport,
  ParticipantAdminConnection,
  RetryFor,
  RetryProvider,
  SpliceLedgerConnection,
}
import org.lfdecentralizedtrust.splice.http.v0.{definitions, validator_admin as v0}
import org.lfdecentralizedtrust.splice.identities.NodeIdentitiesStore
import org.lfdecentralizedtrust.splice.scan.admin.api.client.ScanConnection
import org.lfdecentralizedtrust.splice.scan.admin.api.client.ScanConnection.GetAmuletRulesDomain
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.QueryResult
import org.lfdecentralizedtrust.splice.util.*
import org.lfdecentralizedtrust.splice.validator.config.ValidatorAppBackendConfig
import org.lfdecentralizedtrust.splice.validator.migration.DomainMigrationDumpGenerator
import org.lfdecentralizedtrust.splice.validator.store.ValidatorStore
import org.lfdecentralizedtrust.splice.validator.util.ValidatorUtil
import org.lfdecentralizedtrust.splice.wallet.UserWalletManager
import org.lfdecentralizedtrust.splice.wallet.treasury.TreasuryService.AmuletOperationDedupConfig
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.crypto.*
import com.digitalasset.canton.crypto.SignatureFormat.Raw
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.sequencing.GrpcSequencerConnection
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.topology.transaction.*
import com.digitalasset.canton.topology.transaction.SignedTopologyTransaction.GenericSignedTopologyTransaction
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.digitalasset.canton.util.HexString
import com.digitalasset.canton.version.ProtocolVersion
import com.google.protobuf.ByteString
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority

import java.time.Instant
import java.util.Base64
import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

class HttpValidatorAdminHandler(
    storeWithIngestion: AppStoreWithIngestion[ValidatorStore],
    identitiesStore: NodeIdentitiesStore,
    validatorUserName: String,
    validatorWalletUserNames: Seq[String],
    walletManagerOpt: Option[UserWalletManager],
    getAmuletRulesDomain: GetAmuletRulesDomain,
    scanConnection: ScanConnection,
    participantAdminConnection: ParticipantAdminConnection,
    packageVersionSupport: PackageVersionSupport,
    config: ValidatorAppBackendConfig,
    clock: Clock,
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
    storeWithIngestion.connection(SpliceLedgerConnectionPriority.Medium),
    participantAdminConnection,
    retryProvider,
    loggerFactory,
  )

  private def requireWalletEnabled[T](handleRequest: UserWalletManager => T): T = {
    walletManagerOpt.fold(
      throw HttpErrorHandler.notImplemented(
        "Validator must be configured with enableWallet=true to serve this endpoint"
      )
    )(handleRequest)
  }

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
      // TODO(DACH-NY/canton-network-node#12550): move away from tracking onboarded users via on-ledger contracts, and create only one WalletAppInstall per user-party
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
        synchronizerId <- getAmuletRulesDomain()(tracedContext)
        res <- dumpGenerator
          .getDomainDataSnapshot(
            Instant.parse(timestamp),
            synchronizerId,
            // TODO(DACH-NY/canton-network-node#9731): get migration id from scan instead of configuring here
            migrationId getOrElse (config.domainMigrationId + 1),
            force.getOrElse(false),
          )
          .map { response =>
            v0.ValidatorAdminResource.GetValidatorDomainDataSnapshotResponse.OK(
              definitions
                // DR dumps don't separate output files
                .GetValidatorDomainDataSnapshotResponse(
                  response.toHttp(outputDirectory = None),
                  response.migrationId,
                )
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
        connectionConfig <- participantAdminConnection.getSynchronizerConnectionConfig(
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
                    _,
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
      validatorWalletUserNames,
      retryProvider,
      logger,
    )
  }

  override def generateExternalPartyTopology(
      respond: v0.ValidatorAdminResource.GenerateExternalPartyTopologyResponse.type
  )(body: definitions.GenerateExternalPartyTopologyRequest)(
      tuser: TracedUser
  ): Future[v0.ValidatorAdminResource.GenerateExternalPartyTopologyResponse] = {
    implicit val TracedUser(_, tracedContext) = tuser
    withSpan(s"$workflowId.generateExternalPartyTopology") { _ => _ =>
      requireWalletEnabled { _ =>
        val publicKey = ValidatorUtil.signingPublicKeyFromHexEd25119(body.publicKey)
        ValidatorUtil
          .createTopologyMappings(
            partyHint = body.partyHint,
            publicKey = publicKey,
            participantAdminConnection = participantAdminConnection,
          )
          .map { case (partyId, topologyTxs) =>
            v0.ValidatorAdminResource.GenerateExternalPartyTopologyResponse.OK(
              definitions.GenerateExternalPartyTopologyResponse(
                partyId.toProtoPrimitive,
                topologyTxs
                  .map(tx =>
                    definitions.TopologyTx(
                      topologyTx = Base64.getEncoder.encodeToString(tx.toByteArray),
                      hash = tx.hash.hash.toHexString,
                    )
                  )
                  .toVector,
              )
            )
          }
      }
    }
  }

  @nowarn("msg=deprecated")
  private def decodeSignedTopologyTx(
      publicKey: SigningPublicKey,
      topologyTx: definitions.SignedTopologyTx,
  ): GenericSignedTopologyTransaction = {
    val tx = TopologyTransaction
      .fromTrustedByteString(
        ByteString.copyFrom(Base64.getDecoder.decode(topologyTx.topologyTx))
      )
      .valueOr(error =>
        throw Status.INVALID_ARGUMENT
          .withDescription(s"failed to construct topology transaction: $error")
          .asRuntimeException()
      )
    SignedTopologyTransaction(
      transaction = tx,
      signatures = NonEmpty.mk(
        Set,
        SingleTransactionSignature(
          tx.hash,
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
            signingAlgorithmSpec = None,
            signatureDelegation = None,
          ),
        ),
      ),
      isProposal = true,
    )(
      SignedTopologyTransaction.versioningTable
        .protocolVersionRepresentativeFor(ProtocolVersion.dev)
    )
  }

  override def submitExternalPartyTopology(
      respond: v0.ValidatorAdminResource.SubmitExternalPartyTopologyResponse.type
  )(body: definitions.SubmitExternalPartyTopologyRequest)(
      tuser: TracedUser
  ): Future[v0.ValidatorAdminResource.SubmitExternalPartyTopologyResponse] = {
    implicit val TracedUser(_, tracedContext) = tuser
    withSpan(s"$workflowId.submitExternalPartyTopology") { _ => _ =>
      requireWalletEnabled { _ =>
        val publicKey = ValidatorUtil.signingPublicKeyFromHexEd25119(body.publicKey)
        val partyToParticipant = body.signedTopologyTxs
          .map(decodeSignedTopologyTx(publicKey, _))
          .flatMap(_.selectMapping[PartyToParticipant])
          .headOption
          .getOrElse(
            throw Status.INVALID_ARGUMENT
              .withDescription(
                s"Failed to determine party id from PartyToParticipant transaction"
              )
              .asRuntimeException()
          )
          .transaction
          .mapping
        val partyId = partyToParticipant.partyId
        for {
          _ <- participantAdminConnection.addTopologyTransactions(
            store = TopologyStoreId.Authorized,
            txs = body.signedTopologyTxs.map(decodeSignedTopologyTx(publicKey, _)),
          )
          // Check the authorized store first
          _ <- participantAdminConnection
            .listPartyToKey(
              filterParty = Some(partyId),
              filterStore = TopologyStoreId.Authorized,
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
          // The PartyToParticipant mapping requires both the external signature from the party namespace but also one from the participant which we create here
          participantId <- participantAdminConnection.getParticipantId()
          _ <- participantAdminConnection.proposeMapping(
            TopologyStoreId.Authorized,
            partyToParticipant,
            serial = PositiveInt.one,
            isProposal = true,
            change = TopologyChangeOp.Replace,
          )
          _ <- participantAdminConnection
            .listPartyToParticipant(
              store = TopologyStoreId.Authorized.some,
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
          // now wait for the topology transactions to be broadcast to the domain.
          synchronizerId <- getAmuletRulesDomain()(tracedContext)
          _ <- retryProvider.retry(
            RetryFor.Automation,
            "broadcast_party_to_key_mapping",
            "PartyToKeyMapping is visible in domain store",
            participantAdminConnection
              .listPartyToKey(
                filterParty = Some(partyId),
                filterStore = TopologyStoreId.Synchronizer(synchronizerId),
              )
              .map { txs =>
                txs.headOption.getOrElse(
                  throw Status.FAILED_PRECONDITION
                    .withDescription(
                      s"No PartyToKey mapping in domain store for $partyId"
                    )
                    .asRuntimeException
                )
              },
            logger,
          )
          _ <- retryProvider.retry(
            RetryFor.Automation,
            "broadcast_party_to_participant",
            "PartyToParticipant is visible in domain store",
            participantAdminConnection
              .listPartyToParticipant(
                filterParty = partyId.filterString,
                store = TopologyStoreId.Synchronizer(synchronizerId).some,
              )
              .map { txs =>
                txs.headOption.getOrElse(
                  throw Status.FAILED_PRECONDITION
                    .withDescription(
                      s"No PartyToParticipant mapping in domain store for $partyId"
                    )
                    .asRuntimeException
                )
              },
            logger,
          )
          _ <- storeWithIngestion
            .connection(SpliceLedgerConnectionPriority.Medium)
            .waitForPartyOnLedgerApi(partyId)
          _ <- storeWithIngestion
            .connection(SpliceLedgerConnectionPriority.Medium)
            .grantUserRights(
              config.ledgerApiUser,
              Seq(partyId),
              Seq.empty,
            )
        } yield v0.ValidatorAdminResource.SubmitExternalPartyTopologyResponseOK(
          definitions.SubmitExternalPartyTopologyResponse(Codec.encode(partyId))
        )
      }
    }
  }

  override def createExternalPartySetupProposal(
      respond: v0.ValidatorAdminResource.CreateExternalPartySetupProposalResponse.type
  )(body: definitions.CreateExternalPartySetupProposalRequest)(
      tuser: TracedUser
  ): Future[v0.ValidatorAdminResource.CreateExternalPartySetupProposalResponse] = {
    implicit val TracedUser(_, tracedContext) = tuser
    requireWalletEnabled { walletManager =>
      val userParty = PartyId.tryFromProtoPrimitive(body.userPartyId)
      val validatorServiceParty = store.key.validatorParty
      for {
        result <- store.lookupExternalPartySetupProposalByUserPartyWithOffset(userParty).flatMap {
          case QueryResult(_, Some(c)) =>
            Future.successful(
              v0.ValidatorAdminResource.CreateExternalPartySetupProposalResponse.Conflict(
                definitions.ErrorResponse(
                  s"ExternalPartySetupProposal contract already exists: ${c.contract.contractId}"
                )
              )
            )
          case QueryResult(offsetESP, None) =>
            store.lookupTransferPreapprovalByReceiverPartyWithOffset(userParty).flatMap {
              case QueryResult(_, Some(c)) =>
                Future.successful(
                  v0.ValidatorAdminResource.CreateExternalPartySetupProposalResponse.Conflict(
                    definitions.ErrorResponse(
                      s"TransferPreapproval contract already exists: ${c.contract.contractId}"
                    )
                  )
                )
              case QueryResult(offsetTP, None) =>
                for {
                  validatorWallet <- ValidatorUtil.getValidatorWallet(store, walletManager)
                  outcome <- validatorWallet.treasury
                    .enqueueAmuletOperation(
                      new CO_CreateExternalPartySetupProposal(
                        userParty.toProtoPrimitive,
                        clock.now
                          .add(config.transferPreapproval.preapprovalLifetime.asJava)
                          .toInstant,
                      ),
                      dedup = Some(
                        AmuletOperationDedupConfig(
                          SpliceLedgerConnection.CommandId(
                            "org.lfdecentralizedtrust.splice.validator.createExternalPartySetupProposal",
                            Seq(validatorServiceParty),
                            BaseLedgerConnection.sanitizeUserIdToPartyString(body.userPartyId),
                          ),
                          DedupOffset(implicitly[Ordering[Long]].min(offsetESP, offsetTP)),
                        )
                      ),
                    )
                  result <- outcome match {
                    case successResult: amuletoperationoutcome.COO_CreateExternalPartySetupProposal =>
                      retryProvider
                        .retryForClientCalls(
                          "ingest_external_party_setup_proposal",
                          s"ExternalPartySetupProposal ${successResult.contractIdValue.contractId} gets ingested in validator store",
                          store.multiDomainAcsStore
                            .lookupContractById(ExternalPartySetupProposal.COMPANION)(
                              Codec.tryDecodeJavaContractId(ExternalPartySetupProposal.COMPANION)(
                                successResult.contractIdValue.contractId
                              )
                            )
                            .map { r =>
                              if (r.isEmpty)
                                throw Status.FAILED_PRECONDITION
                                  .withDescription(
                                    s"ExternalPartySetupProposal ${successResult.contractIdValue.contractId} not yet ingested in validator store"
                                  )
                                  .asRuntimeException
                            },
                          logger,
                        )
                        .map { _ =>
                          v0.ValidatorAdminResource.CreateExternalPartySetupProposalResponse.OK(
                            definitions.CreateExternalPartySetupProposalResponse(
                              successResult.contractIdValue.contractId
                            )
                          )
                        }
                    case failedOperation: amuletoperationoutcome.COO_Error =>
                      failedOperation.invalidTransferReasonValue match {
                        case fundsError: invalidtransferreason.ITR_InsufficientFunds =>
                          val missingStr = s"(missing ${fundsError.missingAmount} CC)"
                          val msg =
                            s"Insufficient funds for the transfer pre-approval purchase $missingStr"
                          Future.failed(
                            Status.FAILED_PRECONDITION.withDescription(msg).asRuntimeException()
                          )
                        case otherError =>
                          val msg =
                            s"Unexpectedly failed to create external party setup proposal due to $otherError"
                          Future.failed(
                            Status.FAILED_PRECONDITION.withDescription(msg).asRuntimeException()
                          )
                      }
                    case unknownResult =>
                      val msg = s"Unexpected amulet-operation result $unknownResult"
                      Future.failed(Status.INTERNAL.withDescription(msg).asRuntimeException())
                  }
                } yield result
            }
        }
      } yield result
    }
  }

  override def listExternalPartySetupProposals(
      respond: v0.ValidatorAdminResource.ListExternalPartySetupProposalsResponse.type
  )()(
      tuser: TracedUser
  ): Future[v0.ValidatorAdminResource.ListExternalPartySetupProposalsResponse] = {
    implicit val TracedUser(_, tracedContext) = tuser
    requireWalletEnabled { _ =>
      for {
        proposals <- store.listExternalPartySetupProposals()
      } yield definitions.ListExternalPartySetupProposalsResponse(
        proposals.map(p => p.toHttp).toVector
      )
    }
  }

  override def prepareAcceptExternalPartySetupProposal(
      respond: v0.ValidatorAdminResource.PrepareAcceptExternalPartySetupProposalResponse.type
  )(body: definitions.PrepareAcceptExternalPartySetupProposalRequest)(
      tuser: TracedUser
  ): Future[v0.ValidatorAdminResource.PrepareAcceptExternalPartySetupProposalResponse] = {
    implicit val TracedUser(_, tracedContext) = tuser
    requireWalletEnabled { _ =>
      val userParty = PartyId.tryFromProtoPrimitive(body.userPartyId)
      for {
        synchronizerId <- getAmuletRulesDomain()(tracedContext)
        result <- store.lookupExternalPartySetupProposalByUserPartyWithOffset(userParty).flatMap {
          case QueryResult(_, Some(externalPartySetupProposal)) => {
            if (externalPartySetupProposal.contract.contractId.contractId != body.contractId)
              Future.successful(
                v0.ValidatorAdminResource.PrepareAcceptExternalPartySetupProposalResponse
                  .BadRequest(
                    definitions.ErrorResponse("Found contract does not match provided contractId.")
                  )
              )
            else {
              val commands = externalPartySetupProposal.toAssignedContract
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
              storeWithIngestion
                .connection(SpliceLedgerConnectionPriority.Medium)
                .prepareSubmission(
                  Some(synchronizerId),
                  Seq(userParty),
                  Seq(userParty),
                  commands,
                  DisclosedContracts.Empty,
                  body.verboseHashing.getOrElse(false),
                )
                .flatMap { r =>
                  Future.successful(
                    v0.ValidatorAdminResource.PrepareAcceptExternalPartySetupProposalResponse.OK(
                      definitions.PrepareAcceptExternalPartySetupProposalResponse(
                        // We convert to a base64 bytestring instead of to JSON
                        // as JSON support for decoding protobuf may be less well supported
                        // across languages.
                        Base64.getEncoder.encodeToString(r.getPreparedTransaction.toByteArray),
                        HexString.toHexString(r.preparedTransactionHash),
                        r.hashingDetails,
                      )
                    )
                  )
                }
            }
          }
          case QueryResult(_, None) =>
            Future.successful(
              v0.ValidatorAdminResource.PrepareAcceptExternalPartySetupProposalResponse.NotFound(
                definitions
                  .ErrorResponse(s"No ExternalPartySetupProposal contract found for ${userParty}")
              )
            )
        }
      } yield result
    }
  }

  override def submitAcceptExternalPartySetupProposal(
      respond: v0.ValidatorAdminResource.SubmitAcceptExternalPartySetupProposalResponse.type
  )(body: definitions.SubmitAcceptExternalPartySetupProposalRequest)(
      tuser: TracedUser
  ): Future[v0.ValidatorAdminResource.SubmitAcceptExternalPartySetupProposalResponse] = {
    implicit val TracedUser(_, tracedContext) = tuser
    requireWalletEnabled { _ =>
      val userParty = PartyId.tryFromProtoPrimitive(body.submission.partyId)
      for {
        updateId <- ValidatorUtil.submitAsExternalParty(
          storeWithIngestion.connection(SpliceLedgerConnectionPriority.Medium),
          body.submission,
        )
        result <- store.lookupTransferPreapprovalByReceiverPartyWithOffset(userParty).flatMap {
          case QueryResult(_, Some(c)) =>
            Future.successful(c.contractId)
          case QueryResult(_, None) =>
            throw Status.FAILED_PRECONDITION
              .withDescription(
                s"${TransferPreapproval.COMPANION.TEMPLATE_ID.getEntityName} contract was not created."
              )
              .asRuntimeException()
        }
      } yield v0.ValidatorAdminResource.SubmitAcceptExternalPartySetupProposalResponse.OK(
        definitions.SubmitAcceptExternalPartySetupProposalResponse(result.contractId, updateId)
      )
    }
  }

  override def lookupTransferPreapprovalByParty(
      respond: v0.ValidatorAdminResource.LookupTransferPreapprovalByPartyResponse.type
  )(
      receiverParty: String
  )(
      tuser: TracedUser
  ): Future[v0.ValidatorAdminResource.LookupTransferPreapprovalByPartyResponse] = {
    implicit val TracedUser(_, tracedContext) = tuser
    val receiverPartyId = PartyId.tryFromProtoPrimitive(receiverParty)
    store.lookupTransferPreapprovalByReceiverPartyWithOffset(receiverPartyId).map {
      case QueryResult(_, None) =>
        v0.ValidatorAdminResource.LookupTransferPreapprovalByPartyResponse
          .NotFound(
            definitions.ErrorResponse(s"No TransferPreapproval found for party: $receiverPartyId")
          )
      case QueryResult(_, Some(preapproval)) =>
        v0.ValidatorAdminResource.LookupTransferPreapprovalByPartyResponse
          .OK(definitions.LookupTransferPreapprovalByPartyResponse(preapproval.toHttp))
    }
  }

  override def cancelTransferPreapprovalByParty(
      respond: v0.ValidatorAdminResource.CancelTransferPreapprovalByPartyResponse.type
  )(
      receiverParty: String
  )(
      tuser: TracedUser
  ): Future[v0.ValidatorAdminResource.CancelTransferPreapprovalByPartyResponse] = {
    implicit val TracedUser(_, tracedContext) = tuser
    val receiverPartyId = PartyId.tryFromProtoPrimitive(receiverParty)
    val validatorParty = store.key.validatorParty
    store.lookupTransferPreapprovalByReceiverPartyWithOffset(receiverPartyId).flatMap {
      case QueryResult(_, None) =>
        Future.successful(
          v0.ValidatorAdminResource.CancelTransferPreapprovalByPartyResponse.NotFound(
            definitions.ErrorResponse(s"No TransferPreapproval found for party: $receiverPartyId")
          )
        )
      case QueryResult(_, Some(preapproval)) =>
        storeWithIngestion
          .connection(SpliceLedgerConnectionPriority.Medium)
          .submit(
            Seq(validatorParty),
            Seq(validatorParty),
            preapproval.toAssignedContract
              .getOrElse(
                throw Status.FAILED_PRECONDITION
                  .withDescription(s"TransferPreapproval contract is not assigned to a domain.")
                  .asRuntimeException
              )
              .exercise(_.exerciseTransferPreapproval_Cancel(validatorParty.toProtoPrimitive)),
          )
          .noDedup
          .yieldResult()
          .map(_ => v0.ValidatorAdminResource.CancelTransferPreapprovalByPartyResponse.OK)
    }
  }

  override def listTransferPreapprovals(
      respond: v0.ValidatorAdminResource.ListTransferPreapprovalsResponse.type
  )()(tuser: TracedUser): Future[v0.ValidatorAdminResource.ListTransferPreapprovalsResponse] = {
    implicit val TracedUser(_, tracedContext) = tuser
    for {
      preapprovals <- store.listTransferPreapprovals()
    } yield definitions.ListTransferPreapprovalsResponse(
      preapprovals.map(p => p.toHttp).toVector
    )
  }

  override def prepareTransferPreapprovalSend(
      respond: v0.ValidatorAdminResource.PrepareTransferPreapprovalSendResponse.type
  )(body: definitions.PrepareTransferPreapprovalSendRequest)(
      tuser: TracedUser
  ): Future[v0.ValidatorAdminResource.PrepareTransferPreapprovalSendResponse] = {
    implicit val TracedUser(_, tracedContext) = tuser
    requireWalletEnabled { _ =>
      val senderParty = PartyId.tryFromProtoPrimitive(body.senderPartyId)
      val receiverParty = PartyId.tryFromProtoPrimitive(body.receiverPartyId)
      for {
        synchronizerId <- getAmuletRulesDomain()(tracedContext)
        // This check is just to make it fail early. The actual preapproval is fixed when the automation
        // executes the transfer but we want the user to get feedback during the prepare step already.
        _ <- scanConnection.lookupTransferPreapprovalByParty(receiverParty).map { preapprovalO =>
          if (preapprovalO.isEmpty) {
            throw Status.INVALID_ARGUMENT
              .withDescription(s"Receiver $receiverParty does not have a TransferPreapproval")
              .asRuntimeException
          }
        }
        externalPartyAmuletRules <- scanConnection.getExternalPartyAmuletRules()
        supportsDescription <- packageVersionSupport
          .supportsDescriptionInTransferPreapprovals(
            Seq(receiverParty, senderParty, store.key.dsoParty),
            clock.now,
          )
          .map(_.supported)
        supportsExpectedDsoParty <- packageVersionSupport
          .supportsExpectedDsoParty(
            Seq(receiverParty, senderParty, store.key.dsoParty),
            clock.now,
          )
          .map(_.supported)
        commands = externalPartyAmuletRules.toAssignedContract
          .getOrElse(
            throw Status.Code.FAILED_PRECONDITION.toStatus
              .withDescription(
                s"ExternalPartyAmuletRules is currently inflight between synchronizers, retry until it is assigned to a synchronizer"
              )
              .asRuntimeException()
          )
          .exercise(
            _.exerciseExternalPartyAmuletRules_CreateTransferCommand(
              senderParty.toProtoPrimitive,
              receiverParty.toProtoPrimitive,
              store.key.validatorParty.toProtoPrimitive,
              body.amount.bigDecimal,
              body.expiresAt.toInstant,
              body.nonce,
              Option.when(supportsDescription)(body.description).flatten.toJava,
              Option.when(supportsExpectedDsoParty)(store.key.dsoParty.toProtoPrimitive).toJava,
            )
          )
          .update
          .commands()
          .asScala
          .toSeq
        r <- storeWithIngestion
          .connection(SpliceLedgerConnectionPriority.Medium)
          .prepareSubmission(
            Some(synchronizerId),
            Seq(senderParty),
            Seq(senderParty),
            commands,
            storeWithIngestion
              .connection(SpliceLedgerConnectionPriority.Medium)
              .disclosedContracts(externalPartyAmuletRules),
            body.verboseHashing.getOrElse(false),
          )
        transferCommandCid = r.preparedTransaction
          .flatMap(_.transaction)
          .toList
          .flatMap(_.nodes)
          .flatMap(n =>
            n.getV1.nodeType match {
              case interactive.transaction.v1.interactive_submission_data.Node.NodeType
                    .Create(create) =>
                Seq(create.contractId)
              case _ => Seq.empty
            }
          )
          .headOption
          .getOrElse(
            throw Status.INTERNAL
              .withDescription("Failed to obtain transferCommandCid from prepared transaction")
              .asRuntimeException()
          )
      } yield {
        v0.ValidatorAdminResource.PrepareTransferPreapprovalSendResponse.OK(
          definitions.PrepareTransferPreapprovalSendResponse(
            Base64.getEncoder.encodeToString(r.getPreparedTransaction.toByteArray),
            HexString.toHexString(r.preparedTransactionHash),
            transferCommandCid,
            r.hashingDetails,
          )
        )
      }
    }
  }

  override def submitTransferPreapprovalSend(
      respond: v0.ValidatorAdminResource.SubmitTransferPreapprovalSendResponse.type
  )(body: definitions.SubmitTransferPreapprovalSendRequest)(
      tuser: TracedUser
  ): Future[v0.ValidatorAdminResource.SubmitTransferPreapprovalSendResponse] = {
    implicit val TracedUser(_, tracedContext) = tuser
    requireWalletEnabled { _ =>
      for {
        updateId <- ValidatorUtil.submitAsExternalParty(
          storeWithIngestion.connection(SpliceLedgerConnectionPriority.Medium),
          body.submission,
          waitForOffset = false,
        )
      } yield v0.ValidatorAdminResource.SubmitTransferPreapprovalSendResponseOK(
        definitions.SubmitTransferPreapprovalSendResponse(updateId)
      )
    }
  }

  def getExternalPartyAmulets(partyId: PartyId)(implicit tc: TraceContext): Future[
    (Seq[Contract[Amulet.ContractId, Amulet]], Seq[Contract[LockedAmulet.ContractId, LockedAmulet]])
  ] = {
    requireWalletEnabled { walletManager =>
      val externalPartyWallet = walletManager.externalPartyWalletManager
        .lookupExternalPartyWallet(partyId)
        .getOrElse(
          throw Status.NOT_FOUND
            .withDescription(s"No wallet for external party $partyId")
            .asRuntimeException
        )
      for {
        amulets <- externalPartyWallet.store.listAmulets()
        lockedAmulets <- externalPartyWallet.store.listLockedAmulets()
      } yield (amulets, lockedAmulets)
    }
  }

  def getExternalPartyBalance(
      respond: v0.ValidatorAdminResource.GetExternalPartyBalanceResponse.type
  )(
      partyIdStr: String
  )(tuser: TracedUser): Future[v0.ValidatorAdminResource.GetExternalPartyBalanceResponse] = {
    implicit val TracedUser(_, tc) = tuser
    withSpan(s"$workflowId.getExternalPartyBalance") { implicit tc => _ =>
      requireWalletEnabled { _ =>
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
          v0.ValidatorAdminResource.GetExternalPartyBalanceResponse.OK(
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
}
