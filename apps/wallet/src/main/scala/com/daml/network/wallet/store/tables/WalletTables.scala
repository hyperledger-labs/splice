package com.daml.network.wallet.store.tables

import com.daml.lf.data.Time.Timestamp
import com.daml.lf.value.Value.ContractId
import com.daml.network.store.tables.AcsTables
import com.digitalasset.canton.admin.api.client.data.TemplateId
import io.circe.Json

object WalletTables extends AcsTables {
  import profile.api.*

  lazy val schema: profile.SchemaDescription = acsBaseSchema ++ UserWalletAcsStore.schema

  case class UserWalletAcsStoreRow(
      storeId: Int,
      eventNumber: Long,
      contractId: ContractId,
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
      extends profile.api.Table[UserWalletAcsStoreRow](_tableTag, "user_wallet_acs_store") {
    def * = (
      storeId,
      eventNumber,
      contractId,
      templateId,
      createArguments,
      contractMetadataCreatedAt,
      contractMetadataContractKeyHash,
      contractMetadataDriverInternal,
      contractExpiresAt,
      rewardCouponRoundNumber,
      subscriptionReadyForRenewalAt,
    ).<>(UserWalletAcsStoreRow.tupled, UserWalletAcsStoreRow.unapply)

    val storeId: Rep[Int] = column[Int]("store_id")

    val eventNumber: Rep[Long] = column[Long]("event_number", O.AutoInc, O.PrimaryKey)

    val contractId: Rep[ContractId] = column[ContractId]("contract_id")

    val templateId: Rep[TemplateId] = column[TemplateId]("template_id")

    val createArguments: Rep[Json] = column[Json]("create_arguments")

    val contractMetadataCreatedAt: Rep[Timestamp] =
      column[Timestamp]("contract_metadata_created_at")

    val contractMetadataContractKeyHash: Rep[Option[String]] =
      column[Option[String]]("contract_metadata_contract_key_hash", O.Default(None))

    val contractMetadataDriverInternal: Rep[Array[Byte]] =
      column[Array[Byte]]("contract_metadata_driver_internal")

    val contractExpiresAt: Rep[Option[Timestamp]] =
      column[Option[Timestamp]]("contract_expires_at", O.Default(None))

    val rewardCouponRoundNumber: Rep[Option[Long]] =
      column[Option[Long]]("reward_coupon_round_number", O.Default(None))

    val subscriptionReadyForRenewalAt: Rep[Option[Timestamp]] =
      column[Option[Timestamp]]("subscription_ready_for_renewal_at", O.Default(None))
  }

  lazy val UserWalletAcsStore = new TableQuery(tag => new UserWalletAcsStore(tag))

}
