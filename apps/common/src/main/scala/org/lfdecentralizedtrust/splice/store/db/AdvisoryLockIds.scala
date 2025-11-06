// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

import com.digitalasset.canton.resource.DbStorage
import slick.dbio.{DBIOAction, Effect, NoStream}
import slick.jdbc.JdbcProfile
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import scala.concurrent.ExecutionContext

/** Registry for advisory lock identifiers used in splice applications.
  */
object AdvisoryLockIds {
  // 0x73706c equals ASCII encoded "spl". Modeled after Canton's HaConfig, which uses ASCII "dml".
  private val base: Long = 0x73706c00

  final case class AdvisoryLockId(value: Long) extends AnyVal
  final case class FailedToAcquireAdvisoryLock(lockId: AdvisoryLockId)
      extends Exception(s"Failed to acquire exclusive lock with id ${lockId.value}")

  final val acsSnapshotDataInsert = AdvisoryLockId(base + 1)

  final val sqlIndexInitializationTrigger = AdvisoryLockId(base + 2)

  /** Executes the given action in a transaction while holding an exclusive advisory transaction lock with the given ID.
    *
    * An advisory lock only conflicts with other advisory locks with the same ID.
    * It does not interfere with regular row-level or table-level locks.
    * In contrast, regular locks (e.g. obtained via `LOCK TABLE ... IN EXCLUSIVE MODE`) would conflict with harmless
    * background operations like autovacuum.
    *
    * If the lock cannot be acquired, the action fails immediately with a [[FailedToAcquireAdvisoryLock]].
    * It does not wait for the lock to become available, nor does it retry.
    *
    * If the transaction is aborted for any reason, the lock is released automatically by Postgres.
    * In case the application crashes while holding the lock, the server _should_ close the connection
    * and abort the transaction (thus freeing the lock) as soon as it detects a disconnect.
    * Note, however, that in some cases it may take a while for the server to detect the disconnect.
    */
  def withExclusiveTransactionLock[T, E <: Effect](
      storage: DbStorage,
      lockId: AdvisoryLockId,
      action: DBIOAction[T, NoStream, E],
  )(implicit
      ec: ExecutionContext
  ): DBIOAction[T, NoStream, Effect.Read & Effect.Transactional & E] = {
    val profile: JdbcProfile = storage.profile.jdbc
    import profile.api.jdbcActionExtensionMethods
    (for {
      lockResult <-
        sql"SELECT pg_try_advisory_xact_lock(${lockId.value})"
          .as[Boolean]
          .head
      result <-
        if (lockResult) {
          action
        } else {
          DBIOAction.failed(FailedToAcquireAdvisoryLock(lockId))
        }
    } yield result).transactionally
  }
}
