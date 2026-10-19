// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.digitalasset.canton.logging.ErrorLoggingContext
import com.digitalasset.canton.util.ErrorUtil
import io.grpc.{Status, StatusRuntimeException}

object ErrorUtilExtensions {
  implicit class `ErrorUtil extensions`(private val self: ErrorUtil.type) extends AnyVal {

    /** Like [[ErrorUtil.internalError]], but throws a gRPC `INTERNAL` status with `msg`. */
    def internalErrorGrpc(msg: String)(implicit loggingContext: ErrorLoggingContext): Nothing =
      self.internalError(new StatusRuntimeException(Status.INTERNAL.withDescription(msg)))
  }
}
