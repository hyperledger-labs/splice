// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.store.db

import cats.data.OptionT
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import org.lfdecentralizedtrust.splice.validator.store.ValidatorInternalStore
import slick.jdbc.JdbcProfile
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import scala.concurrent.{ExecutionContext, Future}
import io.circe.Json
import org.lfdecentralizedtrust.splice.store.db.AcsJdbcTypes

class DbValidatorInternalStore(
    storage: DbStorage,
    implicit val loggingContext: ErrorLoggingContext,
    implicit val closeContext: CloseContext,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit val ec: ExecutionContext)
    extends ValidatorInternalStore
    with AcsJdbcTypes
    with NamedLogging {

  val profile: JdbcProfile = storage.profile.jdbc

  override def setConfig(key: String, value: Json)(implicit tc: TraceContext): Future[Unit] = {
    val action = sql"""INSERT INTO validator_internal_config (config_key, config_value)
        VALUES ($key, $value)
        ON CONFLICT (config_key) DO UPDATE
        SET config_value = excluded.config_value""".asUpdate
    val updateAction = storage.update(action, "set-validator-internal-config")
    logger.debug(
      s"saving validator config in database with key '$key' and value '${value.noSpaces}'"
    )
    updateAction.map(_ => ())
  }

  override def getConfig(key: String)(implicit tc: TraceContext): OptionT[Future, Json] = {
    val queryAction = sql"""SELECT config_value
        FROM validator_internal_config
        WHERE config_key = $key
      """.as[Json].headOption

    val jsonOptionF: OptionT[FutureUnlessShutdown, Json] =
      storage.querySingle(queryAction, "get-validator-internal-config")
    val futureOptionJson: Future[Option[Json]] = jsonOptionF.value
    OptionT(futureOptionJson)
  }
}
