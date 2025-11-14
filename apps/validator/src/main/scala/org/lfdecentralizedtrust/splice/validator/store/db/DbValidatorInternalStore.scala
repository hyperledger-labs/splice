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
import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax.*
import org.lfdecentralizedtrust.splice.store.db.AcsJdbcTypes

class DbValidatorInternalStore(
    storage: DbStorage,
    val loggerFactory: NamedLoggerFactory,
)(implicit
    val ec: ExecutionContext,
    val loggingContext: ErrorLoggingContext,
    val closeContext: CloseContext,
) extends ValidatorInternalStore
    with AcsJdbcTypes
    with NamedLogging {

  val profile: JdbcProfile = storage.profile.jdbc

  override def setConfig[T: Encoder](key: String, value: T)(implicit
      tc: TraceContext
  ): Future[Unit] = {
    val jsonValue: Json = value.asJson

    val action = sql"""INSERT INTO validator_internal_config (config_key, config_value)
        VALUES ($key, $jsonValue)
        ON CONFLICT (config_key) DO UPDATE
        SET config_value = excluded.config_value""".asUpdate
    val updateAction = storage.update(action, "set-validator-internal-config")

    logger.debug(
      s"saving validator config in database with key '$key' and value '${jsonValue.noSpaces}'"
    )
    updateAction.map(_ => ())
  }

  override def getConfig[T: Decoder](key: String)(implicit tc: TraceContext): OptionT[Future, T] = {
    val queryAction = sql"""SELECT config_value
        FROM validator_internal_config
        WHERE config_key = $key
      """.as[Json].headOption

    val jsonOptionF: OptionT[FutureUnlessShutdown, Json] =
      storage.querySingle(queryAction, "get-validator-internal-config")

    val typedOptionT: OptionT[FutureUnlessShutdown, T] = jsonOptionF.subflatMap { json =>
      json.as[T] match {
        case Right(typedValue) => Some(typedValue)
        case Left(error) => {
          logger.error(
            s"Failed to decode config key '$key' to expected type T: ${error.getMessage}"
          )
          None
        }
      }
    }

    OptionT(futureUnlessShutdownToFuture(typedOptionT.value))
  }
}
