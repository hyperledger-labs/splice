// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import com.digitalasset.daml.lf.data.Time.Timestamp
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.scan.store.db.ScanTcsTables.ScanTcsStoreRowData
import org.lfdecentralizedtrust.splice.store.{AppStore, MultiDomainAcsStore}
import org.lfdecentralizedtrust.splice.store.db.AcsInterfaceViewRowData

trait ScanTcsStore extends AppStore {

  def key: ScanStore.Key

  override lazy val acsContractFilter: MultiDomainAcsStore.ContractFilter[
    ScanTcsStoreRowData,
    AcsInterfaceViewRowData.NoInterfacesIngested,
  ] =
    ScanTcsStore.contractFilter(key)
}

object ScanTcsStore {

  def contractFilter(
      key: ScanStore.Key
  ): MultiDomainAcsStore.ContractFilter[
    ScanTcsStoreRowData,
    AcsInterfaceViewRowData.NoInterfacesIngested,
  ] = {
    import MultiDomainAcsStore.mkFilter
    val dso = key.dsoParty.toProtoPrimitive

    MultiDomainAcsStore.SimpleContractFilter(
      key.dsoParty,
      Map(
        mkFilter(splice.amuletrules.AmuletRules.COMPANION)(co => co.payload.dso == dso)(
          ScanTcsStoreRowData(_)
        ),
        mkFilter(splice.round.OpenMiningRound.COMPANION)(co => co.payload.dso == dso) { contract =>
          ScanTcsStoreRowData(
            contract = contract,
            contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.targetClosesAt)),
            round = Some(contract.payload.round.number),
          )
        },
        mkFilter(splice.amulet.FeaturedAppRight.COMPANION)(co => co.payload.dso == dso) {
          contract =>
            ScanTcsStoreRowData(
              contract = contract,
              featuredAppRightProvider =
                Some(PartyId.tryFromProtoPrimitive(contract.payload.provider)),
            )
        },
      ),
    )
  }
}
