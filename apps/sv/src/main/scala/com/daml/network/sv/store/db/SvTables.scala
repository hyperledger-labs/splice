// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.store.db

import com.digitalasset.daml.lf.data.Time.Timestamp
import com.daml.network.store.db.{AcsRowData, AcsTables, IndexColumnValue}
import com.daml.network.util.Contract

object SvTables extends AcsTables {

  case class SvAcsStoreRowData(
      contract: Contract[?, ?],
      contractExpiresAt: Option[Timestamp] = None,
      onboardingSecret: Option[String] = None,
      svCandidateName: Option[String] = None,
  ) extends AcsRowData {
    override def indexColumns: Seq[(String, IndexColumnValue[?])] = Seq(
      "onboarding_secret" -> onboardingSecret.map(lengthLimited),
      "sv_candidate_name" -> svCandidateName.map(lengthLimited),
    )
  }

}
