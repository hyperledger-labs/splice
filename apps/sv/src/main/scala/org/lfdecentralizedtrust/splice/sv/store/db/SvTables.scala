// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.store.db

import com.digitalasset.daml.lf.data.Time.Timestamp
import org.lfdecentralizedtrust.splice.store.db.AcsRowData.HasIndexColumns
import org.lfdecentralizedtrust.splice.store.db.{AcsRowData, AcsTables, IndexColumnValue}
import org.lfdecentralizedtrust.splice.util.Contract

object SvTables extends AcsTables {

  case class SvAcsStoreRowData(
      contract: Contract[?, ?],
      contractExpiresAt: Option[Timestamp] = None,
      onboardingSecret: Option[String] = None,
      svCandidateName: Option[String] = None,
  ) extends AcsRowData.AcsRowDataFromContract {
    override def indexColumns: Seq[(String, IndexColumnValue[?])] = Seq(
      SvAcsStoreRowData.IndexColumns.onboarding_secret -> onboardingSecret.map(lengthLimited),
      SvAcsStoreRowData.IndexColumns.sv_candidate_name -> svCandidateName.map(lengthLimited),
    )
  }

  object SvAcsStoreRowData {
    implicit val hasIndexColumns: HasIndexColumns[SvAcsStoreRowData] =
      new HasIndexColumns[SvAcsStoreRowData] {
        override def indexColumnNames: Seq[String] = IndexColumns.All
      }
    private object IndexColumns {
      val onboarding_secret = "onboarding_secret"
      val sv_candidate_name = "sv_candidate_name"
      val All = Seq(onboarding_secret, sv_candidate_name)
    }
  }

}
