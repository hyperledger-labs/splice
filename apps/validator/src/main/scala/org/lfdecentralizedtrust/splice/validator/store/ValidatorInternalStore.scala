// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.store

import cats.data.OptionT
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.tracing.TraceContext
import io.circe.{Decoder, Encoder}
import org.lfdecentralizedtrust.splice.validator.store.db.DbValidatorInternalStore

import scala.concurrent.{ExecutionContext, Future}

trait ValidatorInternalStore {

  def deleteConfig(key: String)(implicit
      tc: TraceContext
  ): Future[Unit]

  def setConfig[T](key: String, value: T)(implicit
      tc: TraceContext,
      encoder: Encoder[T],
  ): Future[Unit]

  def getConfig[T](
      key: String
  )(implicit tc: TraceContext, decoder: Decoder[T]): OptionT[Future, Decoder.Result[T]]

}

object ValidatorInternalStore {

  def apply(storage: Storage, loggerFactory: NamedLoggerFactory)(implicit
      ec: ExecutionContext,
      cc: CloseContext,
  ): ValidatorInternalStore = {
    storage match {
      case _: MemoryStorage =>
        throw new IllegalArgumentException("Memory storage not supported for internal store")
      case storage: DbStorage => new DbValidatorInternalStore(storage, loggerFactory)
    }
  }
}
