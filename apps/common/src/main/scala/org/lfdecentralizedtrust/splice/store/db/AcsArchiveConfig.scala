// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

/** Configuration for archiving deleted ACS rows into a separate table.
  * @param archiveTableName The name of the archive table to copy deleted rows into.
  * @param baseColumns Comma-separated column names to copy from the ACS table to the archive table.
  */
case class AcsArchiveConfig(
    archiveTableName: String,
    baseColumns: String,
)

object AcsArchiveConfig {

  /** Default base columns matching acs_store_template (18 columns). */
  val defaultBaseColumns: String =
    "store_id, migration_id, event_number, contract_id, " +
      "template_id_package_id, template_id_qualified_name, package_name, " +
      "create_arguments, created_event_blob, created_at, contract_expires_at, " +
      "state_number, assigned_domain, reassignment_counter, " +
      "reassignment_target_domain, reassignment_source_domain, " +
      "reassignment_submitter, reassignment_unassign_id"

  def withIndexColumns(
      archiveTableName: String,
      indexColumns: Seq[String],
  ): AcsArchiveConfig =
    AcsArchiveConfig(
      archiveTableName,
      if (indexColumns.isEmpty) defaultBaseColumns
      else defaultBaseColumns + indexColumns.mkString(", ", ", ", ""),
    )
}
