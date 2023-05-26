package com.daml.network.store.db

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.lf.data.Time.Timestamp
import com.digitalasset.canton.admin.api.client.data.TemplateId
import com.digitalasset.canton.ledger.offset.Offset
import com.digitalasset.canton.topology.DomainId
import io.circe.Json
import shapeless.HNil
import slick.jdbc.{GetResult, PostgresProfile}

trait AcsTables extends AcsJdbcTypes {
  val profile: slick.jdbc.JdbcProfile = PostgresProfile

  import profile.api.*

  lazy val acsBaseSchema: profile.SchemaDescription =
    StoreDescriptors.schema ++ StoreIngestionStates.schema

  case class StoreDescriptorsRow(id: Int, descriptor: Json)

  class StoreDescriptors(_tableTag: Tag)
      extends profile.api.Table[StoreDescriptorsRow](_tableTag, "store_descriptors") {
    def * = (id, descriptor).<>(StoreDescriptorsRow.tupled, StoreDescriptorsRow.unapply)

    val id: Rep[Int] = column[Int]("id", O.AutoInc, O.PrimaryKey)

    val descriptor: Rep[Json] = column[Json]("descriptor")
  }

  lazy val StoreDescriptors = new TableQuery(tag => new StoreDescriptors(tag))

  case class StoreIngestionStatesRow(
      id: Int,
      storeId: Int,
      domainId: DomainId,
      lastIngestedOffset: Offset,
  )

  class StoreIngestionStates(_tableTag: Tag)
      extends profile.api.Table[StoreIngestionStatesRow](_tableTag, "store_ingestion_states") {
    def * = (id, storeId, domainId, lastIngestedOffset).<>(
      StoreIngestionStatesRow.tupled,
      StoreIngestionStatesRow.unapply,
    )

    val id: Rep[Int] = column[Int]("id", O.AutoInc, O.PrimaryKey)

    val storeId: Rep[Int] = column[Int]("store_id")

    val domainId: Rep[DomainId] = column[DomainId]("domain_id")

    val lastIngestedOffset: Rep[Offset] = column[Offset]("last_ingested_offset")
  }

  lazy val StoreIngestionStates = new TableQuery(tag => new StoreIngestionStates(tag))

}

object AcsTables extends AcsTables {
  case class AcsStoreRowTemplate(
      storeId: Int,
      eventNumber: Long,
      contractId: ContractId[Any],
      templateId: TemplateId,
      createArguments: Json,
      contractMetadataCreatedAt: Timestamp,
      contractMetadataContractKeyHash: Option[String] = None,
      contractMetadataDriverInternal: Array[Byte],
      contractExpiresAt: Option[Timestamp] = None,
  )

  object AcsStoreRowTemplate {
    implicit val GetResultAcsStoreTemplateRow: GetResult[AcsStoreRowTemplate] = GetResult { prs =>
      import prs.*
      (AcsStoreRowTemplate.apply _).tupled(
        (
          <<[Int],
          <<[Long],
          <<[ContractId[Any]],
          <<[TemplateId],
          <<[Json],
          <<[Timestamp],
          <<[Option[String]],
          <<[Array[Byte]],
          <<?[Timestamp],
        )
      )
    }
  }

  import profile.api.*

  abstract class AcsStoreTemplate[Row](_tableTag: Tag, tableName: String)
      extends profile.api.Table[Row](_tableTag, tableName) {

    val storeId: Rep[Int] = column[Int]("store_id")

    val eventNumber: Rep[Long] = column[Long]("event_number", O.AutoInc, O.PrimaryKey)

    val contractId: Rep[ContractId[Any]] = column[ContractId[Any]]("contract_id")

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

    protected def templateColumns =
      storeId ::
        eventNumber ::
        contractId ::
        templateId ::
        createArguments ::
        contractMetadataCreatedAt ::
        contractMetadataContractKeyHash ::
        contractMetadataDriverInternal ::
        contractExpiresAt :: HNil

  }

  abstract class TxLogStoreTemplate[Row](_tableTag: Tag, tableName: String)
      extends profile.api.Table[Row](_tableTag, tableName) {

    val storeId: Rep[Int] = column[Int]("store_id")

    val entryNumber: Rep[Long] = column[Long]("entry_number", O.AutoInc, O.PrimaryKey)

    val eventId: Rep[String] = column[String]("event_id")

    protected def templateColumns =
      storeId ::
        entryNumber ::
        eventId ::
        HNil
  }
}
