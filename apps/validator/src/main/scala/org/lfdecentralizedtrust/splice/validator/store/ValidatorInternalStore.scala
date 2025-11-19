// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.store

import cats.data.OptionT
import com.digitalasset.canton.tracing.TraceContext
import io.circe.{Decoder, Encoder}

import scala.concurrent.Future

trait ValidatorInternalStore {
  def setConfig[T: Encoder](key: String, value: T)(implicit tc: TraceContext): Future[Unit]

  def getConfig[T: Decoder](key: String)(implicit tc: TraceContext): OptionT[Future, T]
}
