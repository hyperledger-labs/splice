// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import io.grpc.{Status, StatusRuntimeException}
import org.lfdecentralizedtrust.splice.util.PrettyInstances.PrettyContractId

trait StoreErrors {
  def roundNotAggregated(): StatusRuntimeException = {
    Status.NOT_FOUND.withDescription("Round has not been aggregated yet").asRuntimeException
  }

  def txLogNotFound() = {
    Status.NOT_FOUND.withDescription("No matching log indices found").asRuntimeException
  }

  def contractIdNotFound(cid: PrettyContractId) = {
    Status.NOT_FOUND.withDescription(s"Contract id not found: $cid").asRuntimeException
  }

  def txLogIsOfWrongType(name: String) = {
    Status.INTERNAL.withDescription(s"Unexpected log entry type $name").asRuntimeException()
  }

  def offsetExpectedError() = {
    Status.INTERNAL.withDescription("Offset was expected to be present").asRuntimeException()
  }

  def txDecodingFailed() = {
    Status.INTERNAL.withDescription(s"Log entry could not be decoded").asRuntimeException
  }

  def txEncodingFailed() = {
    Status.INTERNAL.withDescription(s"Log entry could not be encoded").asRuntimeException
  }

  def txMissingField() = {
    Status.INTERNAL.withDescription(s"Log entry is missing a required field").asRuntimeException
  }
}
