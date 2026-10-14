// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.http

/** Handler-side enforcement of the `maxItems` bounds declared on request-body arrays in the OpenAPI
  * specs, as the generated servers do not validate them.
  */
object HttpRequestLimits {

  /** The `maxItems` declared on every bounded request-body array, matching the 1000 used for page
    * size caps and default limits elsewhere in scan.
    */
  val MaxRequestArrayItems: Int = 1000

  /** @param fieldName
    *   the name of the array as it appears in the request body, for the error message.
    * @return
    *   `items` unchanged, so that this can wrap the field where it is consumed.
    * @throws io.grpc.StatusRuntimeException
    *   `INVALID_ARGUMENT`, reported as HTTP 400, if `items` exceeds [[MaxRequestArrayItems]].
    */
  @throws[io.grpc.StatusRuntimeException]
  def maxSizeOrFail(fieldName: String, items: Seq[?]): items.type =
    if (items.sizeIs > MaxRequestArrayItems)
      throw io.grpc.Status.INVALID_ARGUMENT
        .withDescription(
          s"Expected '$fieldName' to contain at most $MaxRequestArrayItems items, but contained ${items.size}."
        )
        .asRuntimeException()
    else items
}
