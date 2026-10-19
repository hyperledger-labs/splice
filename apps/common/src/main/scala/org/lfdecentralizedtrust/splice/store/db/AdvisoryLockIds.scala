// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

/** Registry for session-level advisory lock identifiers used in Splice applications.
  *
  * Note: these are *session-level* advisory locks (acquired via pg_advisory_lock / pg_advisory_unlock),
  * distinct from the *connection-level* advisory locks that [[SpliceDbLockCounters]] registers
  * with Canton's [[com.digitalasset.canton.resource.DbStorageMulti]] write-connection pool.
  * The connection-level locks guard against multiple app instances sharing a database; the
  * session-level locks here guard short critical sections within a single instance.
  */
object AdvisoryLockIds {
  // 0x73706c equals ASCII encoded "spl". Modeled after Canton's HaConfig, which uses ASCII "dml".
  private val base: Long = 0x73706c00

  final val acsSnapshotDataInsert: Long = base + 1
  final val ddlStatement: Long = base + 2
  final val acsSnapshotIndex: Long = base + 3
}
