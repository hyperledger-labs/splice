// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.{ParticipantId, PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.config.IngestionConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.scan.store.db.ScanRewardsReferenceTables.ScanRewardsReferenceStoreRowData
import org.lfdecentralizedtrust.splice.store.{AppStore, Limit, MultiDomainAcsStore}
import org.lfdecentralizedtrust.splice.store.db.AcsInterfaceViewRowData
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder

import scala.concurrent.{ExecutionContext, Future}

/** This is a temporal contract store (TcsStore) to provide efficient asOf round
  * lookups of FeaturedAppRight and OpenMiningRound contracts
  * necessary for rewards calculations. It is a separate store with its own
  * tables to enable it to have its own indexing scheme and pruning schedule to
  * ensure consistent performance.
  */
trait ScanRewardsReferenceStore extends AppStore {

  def key: ScanRewardsReferenceStore.Key

  /** For a batch of record times, resolve the oldest open mining round at each time.
    * Returns map from record_time to (roundNumber, roundOpensAt).
    * This will wait till the round info could be obtained for record_times
    * which are yet to be ingested.
    *
    * On the other hand if round info could not be obtained for a particular record_time
    * then the Map will not contain the entry for that.
    * This could happen in two scenarios
    * 1. If the record_time is before the ingestion start.
    * 2. When the ingestion start could not be determined
    *    This will happen if no contracts ingestion has happened in the archived table,
    *    ie the store ingestion has just begun and no OpenMiningRound archival has been observed.
    */
  def lookupActiveOpenMiningRounds(
      recordTimes: Seq[CantonTimestamp]
  )(implicit tc: TraceContext): Future[Map[CantonTimestamp, (Long, CantonTimestamp)]]

  def lookupFeaturedAppPartiesAsOf(
      asOf: CantonTimestamp
  )(implicit tc: TraceContext): Future[Set[String]]

  override lazy val acsContractFilter: MultiDomainAcsStore.ContractFilter[
    ScanRewardsReferenceStoreRowData,
    AcsInterfaceViewRowData.NoInterfacesIngested,
  ] =
    ScanRewardsReferenceStore.contractFilter(key)
}

object ScanRewardsReferenceStore {

  def apply(
      key: ScanRewardsReferenceStore.Key,
      storage: DbStorage,
      loggerFactory: NamedLoggerFactory,
      retryProvider: RetryProvider,
      domainMigrationInfo: DomainMigrationInfo,
      participantId: ParticipantId,
      ingestionConfig: IngestionConfig,
      defaultLimit: Limit,
  )(implicit
      ec: ExecutionContext,
      templateJsonDecoder: TemplateJsonDecoder,
      closeContext: CloseContext,
  ): ScanRewardsReferenceStore = {
    val dbStore = new db.DbScanRewardsReferenceStore(
      key = key,
      storage = storage,
      loggerFactory = loggerFactory,
      retryProvider = retryProvider,
      domainMigrationInfo = domainMigrationInfo,
      participantId = participantId,
      ingestionConfig = ingestionConfig,
      defaultLimit = defaultLimit,
    )
    new CachingScanRewardsReferenceStore(dbStore, loggerFactory)
  }

  case class Key(
      dsoParty: PartyId,
      synchronizerId: SynchronizerId,
  ) extends PrettyPrinting {
    override def pretty: Pretty[Key] = prettyOfClass(
      param("dsoParty", _.dsoParty),
      param("synchronizerId", _.synchronizerId),
    )
  }

  def contractFilter(
      key: ScanRewardsReferenceStore.Key
  ): MultiDomainAcsStore.ContractFilter[
    ScanRewardsReferenceStoreRowData,
    AcsInterfaceViewRowData.NoInterfacesIngested,
  ] = {
    import MultiDomainAcsStore.mkFilter
    val dso = key.dsoParty.toProtoPrimitive

    MultiDomainAcsStore.SimpleContractFilter[
      ScanRewardsReferenceStoreRowData,
      AcsInterfaceViewRowData.NoInterfacesIngested,
    ](
      key.dsoParty,
      templateFilters = Map(
        mkFilter(splice.round.OpenMiningRound.COMPANION)(co => co.payload.dso == dso) { contract =>
          ScanRewardsReferenceStoreRowData(
            contract = contract,
            round = Some(contract.payload.round.number),
          )
        },
        mkFilter(splice.amulet.FeaturedAppRight.COMPANION)(co => co.payload.dso == dso) {
          contract =>
            ScanRewardsReferenceStoreRowData(
              contract = contract,
              featuredAppRightProvider =
                Some(PartyId.tryFromProtoPrimitive(contract.payload.provider)),
            )
        },
      ),
      interfaceFilters = Map.empty,
      synchronizerFilter = Some(key.synchronizerId),
    )
  }
}
