// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.store.db

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.digitalasset.canton.config.CantonRequireTypes.String255
import com.digitalasset.canton.topology.{DomainId, PartyId}
import io.circe.Json
import slick.jdbc.{GetResult, PostgresProfile}

trait AcsTables extends AcsJdbcTypes {
  val profile: slick.jdbc.JdbcProfile = PostgresProfile

  import profile.api.*

  lazy val acsBaseSchema: profile.SchemaDescription =
    StoreDescriptors.schema

  case class StoreDescriptorsRow(id: Int, descriptor: Json)

  class StoreDescriptors(_tableTag: Tag)
      extends profile.api.Table[StoreDescriptorsRow](_tableTag, "store_descriptors") {
    def * = (id, descriptor).<>(
      StoreDescriptorsRow.tupled,
      StoreDescriptorsRow.unapply,
    )

    val id: Rep[Int] = column[Int]("id", O.AutoInc, O.PrimaryKey)

    val descriptor: Rep[Json] = column[Json]("descriptor")
  }

  lazy val StoreDescriptors = new TableQuery(tag => new StoreDescriptors(tag))

}

object AcsTables extends AcsTables {

  case class ContractStateRowData(
      assignedDomain: Option[DomainId],
      reassignmentCounter: Long,
      reassignmentTargetDomain: Option[DomainId],
      reassignmentSourceDomain: Option[DomainId],
      reassignmentSubmitter: Option[PartyId],
      reassignmentUnassignId: Option[String255],
  )

  case class TxLogStoreRowTemplate(
      storeId: Int,
      entryNumber: Long,
      eventId: String,
      domainId: DomainId,
      acsContractId: Option[ContractId[Any]],
  )

  object TxLogStoreRowTemplate {
    implicit val GetResultTxLogStoreTemplateRow: GetResult[TxLogStoreRowTemplate] = GetResult {
      prs =>
        import prs.*
        (TxLogStoreRowTemplate.apply _).tupled(
          (
            <<[Int],
            <<[Long],
            <<[String],
            <<[DomainId],
            <<[Option[ContractId[Any]]],
          )
        )
    }
  }
}
