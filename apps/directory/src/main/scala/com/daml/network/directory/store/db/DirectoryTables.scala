package com.daml.network.directory.store.db

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.lf.data.Time.Timestamp
import com.daml.network.codegen.java.cn.wallet.subscriptions as subsCodegen
import com.daml.network.store.db.{AcsRowData, AcsTables, IndexColumnValue}
import com.daml.network.util.Contract
import com.digitalasset.canton.topology.PartyId

object DirectoryTables extends AcsTables {

  case class DirectoryAcsStoreRowData(
      contract: Contract[?, ?],
      contractExpiresAt: Option[Timestamp] = None,
      directoryInstallUser: Option[PartyId] = None,
      directoryEntryName: Option[String] = None,
      directoryEntryOwner: Option[PartyId] = None,
      subscriptionReferenceContractId: Option[ContractId[subsCodegen.SubscriptionRequest]] = None,
      subscriptionNextPaymentDueAt: Option[Timestamp] = None,
  ) extends AcsRowData {
    override def indexColumns: Seq[(String, IndexColumnValue[?])] = Seq(
      "directory_install_user" -> directoryInstallUser,
      "directory_entry_name" -> directoryEntryName.map(lengthLimited),
      "directory_entry_owner" -> directoryEntryOwner,
      "subscription_reference_contract_id" -> subscriptionReferenceContractId,
      "subscription_next_payment_due_at" -> subscriptionNextPaymentDueAt,
    )
  }

  val acsTableName = "directory_acs_store"
}
