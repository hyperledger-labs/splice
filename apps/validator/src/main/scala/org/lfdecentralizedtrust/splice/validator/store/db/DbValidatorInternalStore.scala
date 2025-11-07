// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.store.db

import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.ErrorLoggingContext
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil
import org.lfdecentralizedtrust.splice.validator.store.ValidatorInternalStore
import org.lfdecentralizedtrust.splice.validator.store.db.ValidatorInternalTables.*
import slick.jdbc.{GetResult, JdbcProfile}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import scala.concurrent.{ExecutionContext, Future}

object DbValidatorInternalStore {

  implicit val getResultValidatorInternalConfig: GetResult[ValidatorInternalConfig] = GetResult {
    r =>
      val _ = r.nextString() // cannot ignore this because we have to update the cursor
      val jsonString = r.nextString()

      io.circe.parser.parse(jsonString) match {
        case Left(err) => throw new Exception(s"Failed to parse jsonb string '$jsonString': $err")
        case Right(json) =>
          ValidatorInternalConfig
            .fromJson(json)
            .fold(
              err =>
                throw new Exception(
                  s"Failed to decode JSON to ValidatorInternalConfig: ${err.getMessage}"
                ),
              identity,
            )
      }
  }
}

class DbValidatorInternalStore(
    storage: DbStorage,
    implicit val loggingContext: ErrorLoggingContext,
    implicit val closeContext: CloseContext,
)(implicit val ec: ExecutionContext)
    extends ValidatorInternalStore {

  import DbValidatorInternalStore.*

  val tableName = "validator_internal_config"

  val profile: JdbcProfile = storage.profile.jdbc

  override def setConfig(key: String, values: Map[String, String])(implicit
      tc: TraceContext
  ): Future[Unit] = {
    val configData: ValidatorInternalConfig =
      ValidatorInternalTables.ValidatorInternalConfig(key, values)

    val jsonString: String = configData.toJson.noSpaces

    val action = sql"""INSERT INTO #$tableName (config_key, config_value)
        VALUES ($key, $jsonString::jsonb)
        ON CONFLICT (config_key) DO UPDATE
        SET config_value = excluded.config_value""".asUpdate

    val f = storage.update(action, "set-validator-config")
    FutureUnlessShutdownUtil.futureUnlessShutdownToFuture(f).map(_ => ())
  }

  override def getConfig(key: String)(implicit tc: TraceContext): Future[Map[String, String]] = {

    val queryAction = sql"""SELECT config_key, config_value
        FROM #$tableName
        WHERE config_key = $key
      """.as[ValidatorInternalConfig].headOption

    val validatorConfigF = storage.querySingle(queryAction, "get-validator-config")

    FutureUnlessShutdownUtil.futureUnlessShutdownToFuture(validatorConfigF.value).map {
      _.map(_.values).getOrElse(Map.empty[String, String])
    }
  }
}
