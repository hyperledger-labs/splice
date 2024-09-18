// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.splitwell.store.db

import com.digitalasset.daml.lf.data.Time.Timestamp
import com.daml.network.codegen.java.splice.wallet.payment.AppPaymentRequest
import com.daml.network.store.db.{AcsRowData, AcsTables, IndexColumnValue}
import com.daml.network.util.Contract
import com.digitalasset.canton.topology.PartyId

object SplitwellTables extends AcsTables {

  case class SplitwellAcsStoreRowData(
      contract: Contract[?, ?],
      contractExpiresAt: Option[Timestamp] = None,
      installUser: Option[PartyId] = None,
      groupId: Option[String] = None,
      groupOwner: Option[PartyId] = None,
      paymentRequestCid: Option[AppPaymentRequest.ContractId] = None,
  ) extends AcsRowData {
    override def indexColumns: Seq[(String, IndexColumnValue[?])] = Seq(
      "install_user" -> installUser,
      "group_id" -> groupId.map(lengthLimited),
      "group_owner" -> groupOwner,
      "payment_request_contract_id" -> paymentRequestCid,
    )
  }

  val acsTableName = "splitwell_acs_store"
}
