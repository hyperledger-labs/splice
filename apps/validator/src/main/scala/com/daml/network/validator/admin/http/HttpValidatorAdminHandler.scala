// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.validator.admin.http

import com.daml.network.auth.AuthExtractor.TracedUser
import com.daml.network.codegen.java.splice.wallet.externalparty.ExternalPartySetupProposal
import com.daml.network.environment.{
  BaseLedgerConnection,
  ParticipantAdminConnection,
  RetryProvider,
  SpliceLedgerConnection,
}
import com.daml.network.http.v0.definitions.{
  CreateExternalPartySetupProposalRequest,
  CreateNamespaceDelegationAndPartyTxsRequest,
  SubmitNamespaceDelegationAndPartyTxsRequest,
}
import com.daml.network.http.v0.validator_admin.ValidatorAdminResource
import com.daml.network.http.v0.{definitions, validator_admin as v0}
import com.daml.network.identities.NodeIdentitiesStore
import com.daml.network.scan.admin.api.client.ScanConnection.GetAmuletRulesDomain
import com.daml.network.store.AppStoreWithIngestion
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.validator.config.ValidatorAppBackendConfig
import com.daml.network.validator.migration.DomainMigrationDumpGenerator
import com.daml.network.validator.store.ValidatorStore
import com.daml.network.validator.util.ValidatorUtil
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.sequencing.GrpcSequencerConnection
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.grpc.StatusRuntimeException
import io.opentelemetry.api.trace.Tracer

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

class HttpValidatorAdminHandler(
    storeWithIngestion: AppStoreWithIngestion[ValidatorStore],
    identitiesStore: NodeIdentitiesStore,
    validatorUserName: String,
    validatorWalletUserName: Option[String],
    getAmuletRulesDomain: GetAmuletRulesDomain,
    participantAdminConnection: ParticipantAdminConnection,
    config: ValidatorAppBackendConfig,
    retryProvider: RetryProvider,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
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

  override def createNamespaceDelegationAndPartyTxs(
      respond: ValidatorAdminResource.CreateNamespaceDelegationAndPartyTxsResponse.type
  )(body: CreateNamespaceDelegationAndPartyTxsRequest)(
      extracted: TracedUser
  ): Future[ValidatorAdminResource.CreateNamespaceDelegationAndPartyTxsResponse] = ???

  override def submitNamespaceDelegationAndPartyTxs(
      respond: ValidatorAdminResource.SubmitNamespaceDelegationAndPartyTxsResponse.type
  )(body: SubmitNamespaceDelegationAndPartyTxsRequest)(
      extracted: TracedUser
  ): Future[ValidatorAdminResource.SubmitNamespaceDelegationAndPartyTxsResponse] = ???

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
        case QueryResult(offset, None) => {
          // TODO(#14156): check for existing TransferPreapproval
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
              deduplicationOffset =
                offset, // TODO(#14156): replace with min of this and offset of TransferPreapproval
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
        case QueryResult(_, Some(c)) =>
          Future.successful(
            ValidatorAdminResource.CreateExternalPartySetupProposalResponse.Conflict(
              definitions.ErrorResponse(
                s"ExternalPartySetupProposal contract already exists: ${c.contract.contractId}"
              )
            )
          )
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
}
