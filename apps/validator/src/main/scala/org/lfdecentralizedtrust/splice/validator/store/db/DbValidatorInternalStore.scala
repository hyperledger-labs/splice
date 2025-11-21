// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.store.db

import cats.data.OptionT
import com.digitalasset.canton.lifecycle.{CloseContext, FutureUnlessShutdown}
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
import com.digitalasset.canton.topology.ParticipantId
import org.lfdecentralizedtrust.splice.validator.store.{ValidatorInternalStore, ValidatorStore}
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Encoder, Json}
import org.lfdecentralizedtrust.splice.store.db.AcsJdbcTypes
import slick.jdbc.JdbcProfile
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import scala.concurrent.{ExecutionContext, Future}
import org.lfdecentralizedtrust.splice.store.db.{StoreDescriptor, StoreDescriptorStore}
import java.util.concurrent.atomic.AtomicReference

class DbValidatorInternalStore(
    participant: ParticipantId,
    key: ValidatorStore.Key,
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
  private val storeDescriptor = StoreDescriptor(
    version = 2,
    name = "DbValidatorInternalConfigStore",
    party = key.validatorParty,
    participant = participant,
    key = Map(
      "validatorParty" -> key.validatorParty.toProtoPrimitive
    ),
  )

  private case class ValidatorInternalStoreState(
      storeId: Option[Int]
  )

  private object ValidatorInternalStoreState {
    def empty(): ValidatorInternalStoreState = ValidatorInternalStoreState(
      storeId = None
    )
  }

  private val internalState =
    new AtomicReference[ValidatorInternalStoreState](ValidatorInternalStoreState.empty())

  def storeId: Int =
    internalState
      .get()
      .storeId
      .getOrElse(throw new RuntimeException("Using storeId before it was assigned"))

  def initializeState()(implicit tc: TraceContext): Future[Unit] = {
    for {
      result <- StoreDescriptorStore
        .getStoreIdForDescriptor(storeDescriptor, storage)
    } yield {
      internalState.updateAndGet(_.copy(storeId = Some(result)))
      ()
    }
  }

  override def setConfig[T](key: String, value: T)(implicit
      tc: TraceContext,
      encoder: Encoder[T],
  ): Future[Unit] = {
    val jsonValue: Json = value.asJson

    val action = sql"""INSERT INTO validator_internal_config (config_key, config_value, store_id)
        VALUES ($key, $jsonValue, $storeId)
        ON CONFLICT (store_id, config_key) DO UPDATE
        SET config_value = excluded.config_value""".asUpdate

    val updateAction = storage.update(action, "set-validator-internal-config")

    logger.debug(
      s"saving validator config in database with key '$key' and value '$jsonValue'"
    )
    updateAction.map(_ => ())
  }

  override def getConfig[T](
      key: String
  )(implicit tc: TraceContext, decoder: Decoder[T]): OptionT[Future, Decoder.Result[T]] = {

    logger.debug(s"Retrieving config key $key")

    val queryAction = sql"""SELECT config_value
      FROM validator_internal_config
      WHERE config_key = $key AND store_id = $storeId
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

  override def deleteConfig(key: String)(implicit
      tc: TraceContext
  ): Future[Unit] = {
    logger.debug(
      s"Deleting config key $key"
    )
    storage
      .update(
        sqlu"delete from validator_internal_config WHERE config_key = $key AND store_id = $storeId",
        "delete config key",
      )
      .map(_.discard)
  }
}
