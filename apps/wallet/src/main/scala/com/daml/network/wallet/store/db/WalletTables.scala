package com.daml.network.wallet.store.db

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.lf.data.Time.Timestamp
import com.daml.network.store.db.AcsTables
import com.digitalasset.canton.admin.api.client.data.TemplateId
import io.circe.Json
import shapeless.HNil

object WalletTables extends AcsTables {
  import profile.api.*

  lazy val schema: profile.SchemaDescription = acsBaseSchema ++ UserWalletAcsStore.schema

  case class UserWalletAcsStoreRow(
      storeId: Int,
      eventNumber: Long,
      contractId: ContractId[Any],
      templateId: TemplateId,
      createArguments: Json,
      contractMetadataCreatedAt: Timestamp,
      contractMetadataContractKeyHash: Option[String] = None,
      contractMetadataDriverInternal: Array[Byte],
      contractExpiresAt: Option[Timestamp] = None,
      rewardCouponRoundNumber: Option[Long] = None,
      subscriptionReadyForRenewalAt: Option[Timestamp] = None,
  )

  class UserWalletAcsStore(_tableTag: Tag)
      extends AcsStoreTemplate[UserWalletAcsStoreRow](_tableTag, "user_wallet_acs_store") {
    def * =
      (templateColumns ::: rewardCouponRoundNumber :: subscriptionReadyForRenewalAt :: HNil).tupled
        .<>(UserWalletAcsStoreRow.tupled, UserWalletAcsStoreRow.unapply)

    val rewardCouponRoundNumber: Rep[Option[Long]] =
      column[Option[Long]]("reward_coupon_round_number", O.Default(None))

    val subscriptionReadyForRenewalAt: Rep[Option[Timestamp]] =
      column[Option[Timestamp]]("subscription_ready_for_renewal_at", O.Default(None))
  }

  lazy val UserWalletAcsStore = new TableQuery(tag => new UserWalletAcsStore(tag))

  case class UserWalletTxLogStoreRow(
      storeId: Int,
      entryNumber: Long,
      eventId: String,
  )

  class UserWalletTxLogStore(_tableTag: Tag)
      extends TxLogStoreTemplate[UserWalletTxLogStoreRow](_tableTag, "user_wallet_txlog_store") {
    def * =
      (templateColumns ::: HNil).tupled
        .<>(UserWalletTxLogStoreRow.tupled, UserWalletTxLogStoreRow.unapply)
  }

  lazy val UserWalletTxLogStore = new TableQuery(tag => new UserWalletTxLogStore(tag))
}
