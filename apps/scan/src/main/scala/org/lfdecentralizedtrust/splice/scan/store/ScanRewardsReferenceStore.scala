// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.scan.store.db.ScanRewardsReferenceTables.ScanRewardsReferenceStoreRowData
import org.lfdecentralizedtrust.splice.store.{AppStore, MultiDomainAcsStore}
import org.lfdecentralizedtrust.splice.store.db.AcsInterfaceViewRowData

import scala.concurrent.Future

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
    * It is expected that all record_time are present in returned map
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
