package com.daml.network.store

import io.grpc.Status

trait TxLogStoreErrors {
  def txLogNotFound() = {
    Status.NOT_FOUND.withDescription("No matching log indices found").asRuntimeException
  }

  def txLogIsOfWrongType() = {
    Status.INTERNAL.withDescription("Unexpected log entry type").asRuntimeException()
  }

}
