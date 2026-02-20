// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.db

import com.digitalasset.daml.lf.data.Time.Timestamp
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.store.db.{AcsRowData, AcsTables, IndexColumnValue}
import org.lfdecentralizedtrust.splice.util.Contract

object ScanTemporalAcsTables extends AcsTables {

  case class ScanTemporalAcsStoreRowData(
      contract: Contract[?, ?],
      contractExpiresAt: Option[Timestamp] = None,
      round: Option[Long] = None,
      featuredAppRightProvider: Option[PartyId] = None,
  ) extends AcsRowData.AcsRowDataFromContract {
    override def indexColumns: Seq[(String, IndexColumnValue[?])] = Seq(
      "round" -> round,
      "featured_app_right_provider" -> featuredAppRightProvider,
    )
  }

  object ScanTemporalAcsStoreRowData {
    implicit val hasIndexColumns: AcsRowData.HasIndexColumns[ScanTemporalAcsStoreRowData] =
      new AcsRowData.HasIndexColumns[ScanTemporalAcsStoreRowData] {
        override val indexColumnNames: Seq[String] = Seq(
          "round",
          "featured_app_right_provider",
        )
      }
  }

  val acsTableName = "scan_tcs_store_active"
  val archiveTableName = "scan_tcs_store_archived"
}
