// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.syncoperator.store

import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.{NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.{Member, ParticipantId, PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.config.IngestionConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.store.db.AcsInterfaceViewRowData
import org.lfdecentralizedtrust.splice.store.db.AcsJdbcTypes.StoreMemberId
import org.lfdecentralizedtrust.splice.store.{AppStore, Limit, MultiDomainAcsStore}
import org.lfdecentralizedtrust.splice.syncoperator.store.db.DbSyncOperatorStore
import org.lfdecentralizedtrust.splice.syncoperator.store.db.SyncOperatorTables.SyncOperatorAcsStoreRowData
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.OptionConverters.*

/** Store of a sync operator app.
  *
  * Ingests the `MemberTraffic` purchases made for this operator's synchronizer, which the buy choice
  * makes it an observer of.
  */
trait SyncOperatorStore extends AppStore {

  /** The parties and synchronizer this store is scoped to. */
  val key: SyncOperatorStore.Key

  override def multiDomainAcsStore: MultiDomainAcsStore

  /** Total traffic purchased for `memberId` on this operator's synchronizer. */
  def getTotalPurchasedMemberTraffic(memberId: Member)(implicit
      tc: TraceContext
  ): Future[Long]

  /** Total traffic purchased on this operator's synchronizer, per member with a purchase on
    * record.
    */
  def listTotalPurchasedMemberTraffic()(implicit
      tc: TraceContext
  ): Future[Map[Member, Long]]
}

object SyncOperatorStore {

  def apply(
      key: Key,
      storage: DbStorage,
      loggerFactory: NamedLoggerFactory,
      retryProvider: RetryProvider,
      migrationId: Long,
      participantId: ParticipantId,
      ingestionConfig: IngestionConfig,
      defaultLimit: Limit,
  )(implicit
      ec: ExecutionContext,
      templateJsonDecoder: TemplateJsonDecoder,
      close: CloseContext,
  ): SyncOperatorStore =
    new DbSyncOperatorStore(
      key,
      storage,
      loggerFactory,
      retryProvider,
      migrationId,
      participantId,
      ingestionConfig,
      defaultLimit,
    )

  case class Key(
      /** The registered operator of [[synchronizerId]]. */
      operatorParty: PartyId,
      /** The DSO party, sole signatory of `MemberTraffic`. */
      dsoParty: PartyId,
      /** The dedicated synchronizer this operator serves. */
      synchronizerId: SynchronizerId,
  ) extends PrettyPrinting {
    override def pretty: Pretty[Key] = prettyOfClass(
      param("operatorParty", _.operatorParty),
      param("dsoParty", _.dsoParty),
      param("synchronizerId", _.synchronizerId),
    )
  }

  def contractFilter(key: Key, logger: TracedLogger): MultiDomainAcsStore.ContractFilter[
    SyncOperatorAcsStoreRowData,
    AcsInterfaceViewRowData.NoInterfacesIngested,
  ] = {
    import MultiDomainAcsStore.mkFilter
    val operator = key.operatorParty.toProtoPrimitive
    val dso = key.dsoParty.toProtoPrimitive
    val synchronizerId = key.synchronizerId.toProtoPrimitive

    MultiDomainAcsStore.SimpleContractFilter(
      key.operatorParty,
      Map(
        mkFilter(splice.decentralizedsynchronizer.MemberTraffic.COMPANION)(co =>
          co.payload.dso == dso &&
            co.payload.operator.toScala.contains(operator) &&
            co.payload.synchronizerId == synchronizerId &&
            co.payload.migrationId == 0L // A registered synchronizer is pinned to migration id 0.
        ) { contract =>
          SyncOperatorAcsStoreRowData(
            contract,
            memberTrafficMember = Member
              .fromProtoPrimitive_(contract.payload.memberId)
              // we ignore cases where the member id is invalid instead of throwing an exception
              // to avoid killing the entire ingestion pipeline as a result
              .fold(
                error => {
                  // The row encoder gets no trace context.
                  logger.warn(
                    s"Skipping the purchased traffic in ${contract.contractId.contractId} of " +
                      s"member ${contract.payload.memberId}, which does not parse: $error"
                  )(TraceContext.empty)
                  None
                },
                member => Some(StoreMemberId(member)),
              ),
            // the filter has already established these agree, so avoid parsing per contract
            memberTrafficDomain = Some(key.synchronizerId),
            totalTrafficPurchased = Some(contract.payload.totalPurchased),
          )
        }
      ),
    )
  }
}
