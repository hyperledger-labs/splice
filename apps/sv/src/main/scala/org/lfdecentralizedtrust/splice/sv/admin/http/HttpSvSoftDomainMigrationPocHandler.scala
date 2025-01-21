// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.admin.http

import org.lfdecentralizedtrust.splice.auth.AuthExtractor.TracedUser
import org.lfdecentralizedtrust.splice.config.SharedSpliceAppParameters
import org.lfdecentralizedtrust.splice.environment.{ParticipantAdminConnection, RetryProvider}
import org.lfdecentralizedtrust.splice.http.v0.sv_soft_domain_migration_poc as v0
import org.lfdecentralizedtrust.splice.http.v0.sv_soft_domain_migration_poc.SvSoftDomainMigrationPocResource
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion
import org.lfdecentralizedtrust.splice.sv.ExtraSynchronizerNode
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.sv.onboarding.SynchronizerNodeReconciler
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{DomainId, ParticipantId, UniqueIdentifier}
import com.digitalasset.canton.topology.store.{TopologyStoreId}
import com.digitalasset.canton.tracing.Spanning
import io.grpc.Status
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters.*

// TODO(#13301) Validate that topology reads return the right amount of data
class HttpSvSoftDomainMigrationPocHandler(
    dsoStoreWithIngestion: AppStoreWithIngestion[SvDsoStore],
    synchronizerNodes: Map[String, ExtraSynchronizerNode],
    participantAdminConnection: ParticipantAdminConnection,
    migrationId: Long,
    legacyMigrationId: Option[Long],
    clock: Clock,
    retryProvider: RetryProvider,
    protected val loggerFactory: NamedLoggerFactory,
    val amuletAppParameters: SharedSpliceAppParameters,
)(implicit
    ec: ExecutionContextExecutor
) extends v0.SvSoftDomainMigrationPocHandler[TracedUser]
    with Spanning
    with NamedLogging {

  private val dsoStore = dsoStoreWithIngestion.store

  override def reconcileSynchronizerDamlState(
      respond: SvSoftDomainMigrationPocResource.ReconcileSynchronizerDamlStateResponse.type
  )(domainIdPrefix: String)(
      extracted: TracedUser
  ): Future[SvSoftDomainMigrationPocResource.ReconcileSynchronizerDamlStateResponse] = {
    implicit val TracedUser(_, traceContext) = extracted
    val domainId = DomainId(
      UniqueIdentifier.tryCreate(
        domainIdPrefix,
        dsoStore.key.dsoParty.uid.namespace,
      )
    )
    val synchronizerNodeReconciler = new SynchronizerNodeReconciler(
      dsoStore,
      dsoStoreWithIngestion.connection,
      legacyMigrationId,
      clock,
      retryProvider,
      logger,
    )
    val node = synchronizerNodes
      .get(domainIdPrefix)
      .getOrElse(
        throw Status.NOT_FOUND
          .withDescription(s"No synchronizer node for $domainIdPrefix configured")
          .asRuntimeException()
      )
    synchronizerNodeReconciler
      .reconcileSynchronizerNodeConfigIfRequired(
        Some(node),
        domainId,
        SynchronizerNodeReconciler.SynchronizerNodeState.OnboardedImmediately,
        migrationId,
      )
      .map(_ => SvSoftDomainMigrationPocResource.ReconcileSynchronizerDamlStateResponse.OK)
  }

  override def signDsoPartyToParticipant(
      respond: SvSoftDomainMigrationPocResource.SignDsoPartyToParticipantResponse.type
  )(domainIdPrefix: String)(
      extracted: TracedUser
  ): Future[SvSoftDomainMigrationPocResource.SignDsoPartyToParticipantResponse] = {
    implicit val TracedUser(_, traceContext) = extracted
    val domainId = DomainId(
      UniqueIdentifier.tryCreate(
        domainIdPrefix,
        dsoStore.key.dsoParty.uid.namespace,
      )
    )
    for {
      dsoRules <- dsoStore.getDsoRules()
      participantIds = dsoRules.payload.svs.values.asScala
        .map(sv => ParticipantId.tryFromProtoPrimitive(sv.participantId))
        .toSeq
      // We resign the PartyToParticipant mapping instead of replaying it to limit the topology
      // transactions that need to be transferred across protocol versions to the bare minimum.
      // We retry as it might take a bit until the SV participants are all known on the new domain
      // and until they are this fails topology validation.
      _ <- retryProvider.retryForClientCalls(
        "sign_dso_party_to_participant",
        "sign_dso_party_to_participant",
        participantAdminConnection.proposeInitialPartyToParticipant(
          TopologyStoreId.DomainStore(domainId),
          dsoStore.key.dsoParty,
          participantIds,
          isProposal = true,
        ),
        logger,
      )
    } yield SvSoftDomainMigrationPocResource.SignDsoPartyToParticipantResponse.OK
  }
}
