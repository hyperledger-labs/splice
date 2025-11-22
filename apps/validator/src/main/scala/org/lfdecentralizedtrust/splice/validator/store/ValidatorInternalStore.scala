// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.store

import cats.data.OptionT
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import io.circe.{Decoder, Encoder}
import org.lfdecentralizedtrust.splice.validator.store.db.DbValidatorInternalStore
import scala.concurrent.{ExecutionContext, Future}
import com.digitalasset.canton.logging.ErrorLoggingContext
import com.digitalasset.canton.topology.{ParticipantId, PartyId}

trait ValidatorInternalStore {

  def setConfig[T](key: String, value: T)(implicit
      tc: TraceContext,
      encoder: Encoder[T],
  ): Future[Unit]

  def getConfig[T](
      key: String
  )(implicit tc: TraceContext, decoder: Decoder[T]): OptionT[Future, Decoder.Result[T]]

  def deleteConfig(key: String)(implicit
      tc: TraceContext
  ): Future[Unit]

}

object ValidatorInternalStore {

  def apply(
      participant: ParticipantId,
      validatorParty: PartyId,
      storage: DbStorage,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      ec: ExecutionContext,
      lc: ErrorLoggingContext,
      cc: CloseContext,
      tc: TraceContext,
  ): Future[ValidatorInternalStore] = {
    storage match {
      case storage: DbStorage =>
        val dbStore = new DbValidatorInternalStore(
          participant,
          validatorParty,
          storage,
          loggerFactory,
        )
        dbStore.initialize()
      case storageType => throw new RuntimeException(s"Unsupported storage type $storageType")
    }
  }
}
