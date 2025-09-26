// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

/** Registry for advisory lock identifiers used in splice applications.
  */
object AdvisoryLockIds {
  // 0x73706c equals ASCII encoded "spl". Modeled after Canton's HaConfig, which uses ASCII "dml".
  private val base: Long = 0x73706c00

  final val acsSnapshotDataInsert: Long = base + 1
}
