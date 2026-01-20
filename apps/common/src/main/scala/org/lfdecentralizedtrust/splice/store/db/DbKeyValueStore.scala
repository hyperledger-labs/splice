// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

import cats.data.OptionT
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.lifecycle.{CloseContext, FutureUnlessShutdown}
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Encoder, Json}
import org.lfdecentralizedtrust.splice.store.KeyValueStore
import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
import slick.jdbc.JdbcProfile
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import scala.concurrent.{ExecutionContext, Future}

class DbKeyValueStore private (
    storage: DbStorage,
    val storeId: Int,
    val loggerFactory: NamedLoggerFactory,
)(implicit
    val ec: ExecutionContext,
    val loggingContext: ErrorLoggingContext,
    val closeContext: CloseContext,
) extends KeyValueStore
    with AcsJdbcTypes
    with NamedLogging {

  val profile: JdbcProfile = storage.profile.jdbc

  override def setValue[T](key: String, value: T)(implicit
      tc: TraceContext,
      encoder: Encoder[T],
  ): Future[Unit] = {
    val jsonValue: Json = value.asJson

    val action = sql"""INSERT INTO key_value_store (key, value, store_id)
        VALUES ($key, $jsonValue, $storeId)
        ON CONFLICT (store_id, key) DO UPDATE
        SET value = excluded.value""".asUpdate
    val updateAction = storage.update(action, "set-validator-internal-config")

    logger.debug(
      s"saving validator config in database with key '$key' and value '$jsonValue'"
    )
    updateAction.map(_ => ())
  }

  override def getValue[T](
      key: String
  )(implicit tc: TraceContext, decoder: Decoder[T]): OptionT[Future, Decoder.Result[T]] = {

    logger.debug(s"Retrieving config key $key")

    val queryAction = sql"""SELECT value
      FROM key_value_store
      WHERE key = $key AND store_id = $storeId
    """.as[Json].headOption

    val jsonOptionT: OptionT[FutureUnlessShutdown, Json] =
      storage.querySingle(queryAction, "get-validator-internal-config")

    val resultOptionT: OptionT[FutureUnlessShutdown, Decoder.Result[T]] =
      jsonOptionT.map { json =>
        val decodeResult = json.as[T]

        decodeResult match {
          case Right(_) =>
            logger.debug(
              s"retrieved validator config from database with key '$key' and value '${json.noSpaces}'"
            )
          case Left(error) =>
            logger.error(
              s"Failed to decode config key '$key' to expected type T: ${error.getMessage}"
            )
        }
        decodeResult
      }

    OptionT(futureUnlessShutdownToFuture(resultOptionT.value))
  }

  override def deleteKey(key: String)(implicit
      tc: TraceContext
  ): Future[Unit] = {
    logger.debug(
      s"Deleting config key $key"
    )
    storage
      .update(
        sqlu"delete from key_value_store WHERE key = $key AND store_id = $storeId",
        "delete config key",
      )
      .map(_.discard)
  }
}

object DbKeyValueStore {

  def apply(
      storeDescriptor: StoreDescriptor,
      storage: DbStorage,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      ec: ExecutionContext,
      lc: ErrorLoggingContext,
      cc: CloseContext,
      tc: TraceContext,
  ): Future[DbKeyValueStore] = {

    StoreDescriptorStore
      .getStoreIdForDescriptor(storeDescriptor, storage)
      .map(storeId => {
        new DbKeyValueStore(
          storage,
          storeId,
          loggerFactory,
        )
      })
  }
}
