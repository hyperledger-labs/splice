// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.splitwell.store.db

import com.digitalasset.daml.lf.data.Time.Timestamp
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.payment.AppPaymentRequest
import org.lfdecentralizedtrust.splice.store.db.{AcsRowData, AcsTables, IndexColumnValue}
import org.lfdecentralizedtrust.splice.util.Contract
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.store.db.AcsRowData.HasIndexColumns

object SplitwellTables extends AcsTables {

  case class SplitwellAcsStoreRowData(
      contract: Contract[?, ?],
      contractExpiresAt: Option[Timestamp] = None,
      installUser: Option[PartyId] = None,
      groupId: Option[String] = None,
      groupOwner: Option[PartyId] = None,
      paymentRequestCid: Option[AppPaymentRequest.ContractId] = None,
  ) extends AcsRowData.AcsRowDataFromContract {
    override def indexColumns: Seq[(String, IndexColumnValue[?])] =
      Seq(
        SplitwellAcsStoreRowData.IndexColumns.install_user -> installUser,
        SplitwellAcsStoreRowData.IndexColumns.group_id -> groupId,
        SplitwellAcsStoreRowData.IndexColumns.group_owner -> groupOwner,
        SplitwellAcsStoreRowData.IndexColumns.payment_request_contract_id -> paymentRequestCid,
      )
  }
  object SplitwellAcsStoreRowData {
    implicit val hasIndexColumns: HasIndexColumns[SplitwellAcsStoreRowData] =
      new HasIndexColumns[SplitwellAcsStoreRowData] {
        override def indexColumnNames: Seq[String] = IndexColumns.All
      }
    private object IndexColumns {
      val install_user = "install_user"
      val group_id = "group_id"
      val group_owner = "group_owner"
      val payment_request_contract_id = "payment_request_contract_id"

      val All: Seq[String] = Seq(
        install_user,
        group_id,
        group_owner,
        payment_request_contract_id,
      )
    }
  }

  val acsTableName = "splitwell_acs_store"
}
