// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.syncoperator.store.db

import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.daml.lf.data.Time.Timestamp
import org.lfdecentralizedtrust.splice.store.db.AcsJdbcTypes.StoreMemberId
import org.lfdecentralizedtrust.splice.store.db.AcsRowData.HasIndexColumns
import org.lfdecentralizedtrust.splice.store.db.{AcsRowData, AcsTables, IndexColumnValue}
import org.lfdecentralizedtrust.splice.util.Contract

object SyncOperatorTables extends AcsTables {

  case class SyncOperatorAcsStoreRowData(
      contract: Contract[?, ?],
      contractExpiresAt: Option[Timestamp] = None,
      memberTrafficMember: Option[StoreMemberId] = None,
      memberTrafficDomain: Option[SynchronizerId] = None,
      totalTrafficPurchased: Option[Long] = None,
  ) extends AcsRowData.AcsRowDataFromContract {
    override def indexColumns: Seq[(String, IndexColumnValue[?])] =
      Seq(
        SyncOperatorAcsStoreRowData.IndexColumns.member_traffic_member -> memberTrafficMember,
        SyncOperatorAcsStoreRowData.IndexColumns.member_traffic_domain -> memberTrafficDomain,
        SyncOperatorAcsStoreRowData.IndexColumns.total_traffic_purchased -> totalTrafficPurchased,
      )
  }

  object SyncOperatorAcsStoreRowData {
    implicit val hasIndexColumns: HasIndexColumns[SyncOperatorAcsStoreRowData] =
      new HasIndexColumns[SyncOperatorAcsStoreRowData] {
        override def indexColumnNames: Seq[String] = IndexColumns.All
      }
    private object IndexColumns {
      val member_traffic_member = "member_traffic_member"
      val member_traffic_domain = "member_traffic_domain"
      val total_traffic_purchased = "total_traffic_purchased"

      val All: Seq[String] = Seq(
        member_traffic_member,
        member_traffic_domain,
        total_traffic_purchased,
      )
    }
  }

  val acsTableName = "sync_operator_acs_store"
}
