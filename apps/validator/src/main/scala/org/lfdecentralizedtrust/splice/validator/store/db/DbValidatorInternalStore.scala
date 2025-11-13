// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.store.db

import cats.data.OptionT
import cats.~>
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.lifecycle.{CloseContext, FutureUnlessShutdown}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Encoder, Json}
import org.lfdecentralizedtrust.splice.store.db.AcsJdbcTypes
import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil
import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
import org.lfdecentralizedtrust.splice.validator.store.ValidatorInternalStore
import slick.jdbc.JdbcProfile
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import scala.concurrent.{ExecutionContext, Future}

class DbValidatorInternalStore(
    storage: DbStorage,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext, closeContext: CloseContext)
    extends ValidatorInternalStore
    with AcsJdbcTypes
    with NamedLogging {

  val profile: JdbcProfile = storage.profile.jdbc

  override def setConfig[T](key: String, value: T)(implicit
      tc: TraceContext,
      encoder: Encoder[T],
  ): Future[Unit] = {
    val json = value.asJson
    val action = sql"""INSERT INTO validator_internal_config (config_key, config_value)
        VALUES ($key, $json)
        ON CONFLICT (config_key) DO UPDATE
        SET config_value = excluded.config_value""".asUpdate
    val updateAction = storage.update(action, "set-validator-internal-config")
    logger.debug(
      s"saving validator config in database with key '$key' and value '$json'"
    )
    updateAction.map(_ => ())
  }

  override def getConfig[T](
      key: String
  )(implicit tc: TraceContext, decoder: Decoder[T]): OptionT[Future, Decoder.Result[T]] = {
    storage
      .querySingle(
        sql"""SELECT config_value
        FROM validator_internal_config
        WHERE config_key = $key
      """.as[Json].headOption,
        "get-validator-internal-config",
      )
      .mapK(new ~>[FutureUnlessShutdown, Future] {
        def apply[A](fa: FutureUnlessShutdown[A]): Future[A] =
          FutureUnlessShutdownUtil.futureUnlessShutdownToFuture(fa)
      })
      .map(_.as[T])
  }

  def deleteConfig(key: String)(implicit tc: TraceContext): Future[Unit] = {
    logger.debug(
      s"Deleting config key $key"
    )
    storage
      .update(
        sqlu"delete from validator_internal_config WHERE config_key = $key",
        "delete config key",
      )
      .map(_.discard)
  }
}
