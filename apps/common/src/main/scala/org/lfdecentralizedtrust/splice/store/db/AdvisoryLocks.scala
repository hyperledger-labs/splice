// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

import slick.dbio.{DBIOAction, Effect, NoStream}
import slick.jdbc.JdbcProfile
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import scala.concurrent.ExecutionContext

object AdvisoryLocks {
  final case class FailedToAcquireLockException(
      lockType: String,
      lockId: Long,
  ) extends RuntimeException(s"Failed to acquire $lockType advisory lock $lockId.")

  private def withLock[T, E <: Effect](lockType: String, lockId: Long)(
      acquire: DBIOAction[Boolean, NoStream, Effect.Read],
      onAcquired: DBIOAction[T, NoStream, E],
  )(implicit ec: ExecutionContext): DBIOAction[T, NoStream, Effect.Read & E] =
    acquire.flatMap(acquired =>
      if (acquired) onAcquired
      else DBIOAction.failed(FailedToAcquireLockException(lockType, lockId))
    )

  private def acquireSessionLock(lockId: Long): DBIOAction[Boolean, NoStream, Effect.Read] =
    sql"select pg_try_advisory_lock($lockId)".as[Boolean].head

  private def releaseSessionLock(lockId: Long): DBIOAction[Boolean, NoStream, Effect.Read] =
    sql"select pg_advisory_unlock($lockId)".as[Boolean].head

  /** Wraps the given action in a session-scoped advisory lock; useful for acquiring locks for
    * queries like DDL that can't run in a transaction.
    */
  def withSessionLock[T, E <: Effect](lockId: Long, action: DBIOAction[T, NoStream, E])(implicit
      ec: ExecutionContext
  ): DBIOAction[T, NoStream, Effect.Read & E] =
    withLock("session-scoped", lockId)(
      acquireSessionLock(lockId),
      action.andFinally(releaseSessionLock(lockId)),
    ).withPinnedSession

  def withDdlLock[T, E <: Effect](action: DBIOAction[T, NoStream, E])(implicit
      ec: ExecutionContext
  ): DBIOAction[T, NoStream, Effect.Read & E] =
    withSessionLock(AdvisoryLockIds.ddlStatement, action)

  private def acquireTransactionalLock(lockId: Long): DBIOAction[Boolean, NoStream, Effect.Read] =
    sql"SELECT pg_try_advisory_xact_lock($lockId)".as[Boolean].head

  /** Wraps the given action in a transactional advisory lock. */
  def withTransactionalLock[T, E <: Effect](
      profile: JdbcProfile,
      lockId: Long,
      action: DBIOAction[T, NoStream, E],
  )(implicit
      ec: ExecutionContext
  ): DBIOAction[T, NoStream, Effect.Read & Effect.Transactional & E] = {
    import profile.api.jdbcActionExtensionMethods
    withLock("transactional", lockId)(acquireTransactionalLock(lockId), action).transactionally
  }

  /** Like [[withTransactionalLock]], but waits for the lock instead of failing while another
    * session holds it. For this reason, use only when the update will be quick.
    */
  def withBlockingTransactionalLock[T, E <: Effect](
      profile: JdbcProfile,
      lockId: Long,
      action: DBIOAction[T, NoStream, E],
  ): DBIOAction[T, NoStream, Effect.Read & Effect.Transactional & E] = {
    import profile.api.jdbcActionExtensionMethods
    sql"SELECT pg_advisory_xact_lock($lockId)"
      .as[Unit]
      .head
      .andThen(action)
      .transactionally
  }
}
