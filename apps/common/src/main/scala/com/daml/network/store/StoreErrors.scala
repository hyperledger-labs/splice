package com.daml.network.store

import io.grpc.Status

trait StoreErrors {
  def roundNotAggregated() = {
    Status.NOT_FOUND.withDescription("Round has not been aggregated yet").asRuntimeException
  }

  def txLogNotFound() = {
    Status.NOT_FOUND.withDescription("No matching log indices found").asRuntimeException
  }

  def txLogIsOfWrongType() = {
    Status.INTERNAL.withDescription("Unexpected log entry type").asRuntimeException()
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
