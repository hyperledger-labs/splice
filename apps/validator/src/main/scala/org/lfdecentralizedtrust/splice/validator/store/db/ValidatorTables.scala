// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.store.db

import com.digitalasset.daml.lf.data.Time.Timestamp
import org.lfdecentralizedtrust.splice.store.db.{AcsRowData, AcsTables, IndexColumnValue}
import org.lfdecentralizedtrust.splice.util.Contract
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import org.lfdecentralizedtrust.splice.store.db.AcsRowData.HasIndexColumns

object ValidatorTables extends AcsTables {

  case class ValidatorAcsStoreRowData(
      contract: Contract[?, ?],
      contractExpiresAt: Option[Timestamp] = None,
      userParty: Option[PartyId] = None,
      userName: Option[String] = None,
      providerParty: Option[PartyId] = None,
      validatorParty: Option[PartyId] = None,
      trafficSynchronizerId: Option[SynchronizerId] = None,
  ) extends AcsRowData.AcsRowDataFromContract {
    override def indexColumns: Seq[(String, IndexColumnValue[?])] =
      Seq[(String, IndexColumnValue[?])](
        ValidatorAcsStoreRowData.IndexColumns.user_party -> userParty,
        ValidatorAcsStoreRowData.IndexColumns.user_name -> userName.map(lengthLimited),
        ValidatorAcsStoreRowData.IndexColumns.provider_party -> providerParty,
        ValidatorAcsStoreRowData.IndexColumns.validator_party -> validatorParty,
        ValidatorAcsStoreRowData.IndexColumns.traffic_domain_id -> trafficSynchronizerId,
      )
  }
  object ValidatorAcsStoreRowData {
    implicit val hasIndexColumns: HasIndexColumns[ValidatorAcsStoreRowData] =
      new HasIndexColumns[ValidatorAcsStoreRowData] {
        override def indexColumnNames: Seq[String] = IndexColumns.All
      }
    private object IndexColumns {
      val user_party = "user_party"
      val user_name = "user_name"
      val provider_party = "provider_party"
      val validator_party = "validator_party"
      val traffic_domain_id = "traffic_domain_id"
      val All = Seq(
        user_party,
        user_name,
        provider_party,
        validator_party,
        traffic_domain_id,
      )
    }
  }

  val acsTableName = "validator_acs_store"
}
