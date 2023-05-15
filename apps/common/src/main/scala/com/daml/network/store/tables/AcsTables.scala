package com.daml.network.store.tables

import com.digitalasset.canton.ledger.offset.Offset
import com.digitalasset.canton.topology.DomainId
import io.circe.Json
import slick.jdbc.PostgresProfile

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
