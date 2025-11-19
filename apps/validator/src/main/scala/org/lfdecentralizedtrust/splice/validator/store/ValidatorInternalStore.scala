// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.store

import cats.data.OptionT
import com.digitalasset.canton.tracing.TraceContext
import io.circe.{Decoder, Encoder}

import scala.concurrent.{ExecutionContext, Future}
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.ParticipantId
import org.lfdecentralizedtrust.splice.validator.store.db.DbValidatorInternalStore

trait ValidatorInternalStore {

  def setConfig[T: Encoder](key: String, value: T)(implicit tc: TraceContext): Future[Unit]

  def getConfig[T: Decoder](key: String)(implicit tc: TraceContext): OptionT[Future, T]
}

object ValidatorInternalStore {

  def apply(
      key: ValidatorStore.Key,
      participantId: ParticipantId,
      storage: DbStorage,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      ec: ExecutionContext,
      lc: ErrorLoggingContext,
      cc: CloseContext,
  ): ValidatorInternalStore = {
    storage match {
      case storage: DbStorage =>
        new DbValidatorInternalStore(
          key,
          participantId,
          storage,
          loggerFactory,
        )
      case storageType => throw new RuntimeException(s"Unsupported storage type $storageType")
    }
  }
}
