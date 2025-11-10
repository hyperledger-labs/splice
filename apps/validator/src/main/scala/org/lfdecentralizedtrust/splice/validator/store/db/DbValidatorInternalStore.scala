// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.store.db

import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.ErrorLoggingContext
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
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
)(implicit val ec: ExecutionContext)
    extends ValidatorInternalStore
    with AcsJdbcTypes {

  val tableName = "validator_internal_config"

  val profile: JdbcProfile = storage.profile.jdbc

  def setConfig(key: String, value: Json)(implicit tc: TraceContext): Future[Unit] = {
    val action = sql"""INSERT INTO #$tableName (config_key, config_value)
        VALUES ($key, $value)
        ON CONFLICT (config_key) DO UPDATE
        SET config_value = excluded.config_value""".asUpdate

    storage.update(action, "set-validator-internal-config").map(_ => ())
  }

  override def getConfig(key: String)(implicit tc: TraceContext): Future[Json] = {
    val queryAction = sql"""SELECT config_value
        FROM #$tableName
        WHERE config_key = $key
      """.as[Json].headOption

    val jsonOptionF = storage.querySingle(queryAction, "get-validator-internal-config")

    jsonOptionF.value.map {
      _.getOrElse(Json.obj())
    }
  }
}
