// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.store

import com.digitalasset.canton.tracing.TraceContext
import io.circe.Json

import scala.concurrent.Future

trait ValidatorInternalStore {

  def setConfig(key: String, value: Json)(implicit tc: TraceContext): Future[Unit]

  def getConfig(key: String)(implicit tc: TraceContext): Future[Json]

}
