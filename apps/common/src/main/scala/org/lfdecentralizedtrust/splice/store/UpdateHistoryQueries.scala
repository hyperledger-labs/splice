// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.UpdateHistoryQueries

import com.daml.ledger.javaapi.data.codegen.ContractId
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.ContractCompanion
import org.lfdecentralizedtrust.splice.store.UpdateHistory.SelectFromCreateEvents
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.toSQLActionBuilderChain
import org.lfdecentralizedtrust.splice.store.db.AcsJdbcTypes
import org.lfdecentralizedtrust.splice.util.Contract
import slick.jdbc.canton.SQLActionBuilder

trait UpdateHistoryQueries extends AcsJdbcTypes {

  protected def contractFromEvent[C, TCId <: ContractId[?], T](companion: C)(
      row: SelectFromCreateEvents
  )(implicit
      companionClass: ContractCompanion[C, TCId, T]
  ): Contract[TCId, T] = {
    companionClass
      .fromCreatedEvent(companion)(row.toCreatedEvent.event)
      .getOrElse(throw new RuntimeException("Failed to decode contract"))
  }

  def selectFromUpdateCreatesTableResult(
      historyId: Long,
      where: SQLActionBuilder = sql"",
      orderLimit: SQLActionBuilder = sql"",
  ) = {
    val query = (sql"""
       select update_row_id,
              event_id,
              contract_id,
              created_at,
              template_id_package_id,
              template_id_module_name,
              template_id_entity_name,
              package_name,
              create_arguments,
              signatories,
              observers,
              contract_key
       from update_history_creates
       where history_id = $historyId and """ ++ where ++
      sql"""
       """ ++ orderLimit).toActionBuilder.as[SelectFromCreateEvents]
    query
  }
}
