// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.http

import org.apache.pekko.http.scaladsl.marshalling.Marshaller
import org.apache.pekko.http.scaladsl.model.ResponseEntity

/* An implicit marshaller to prevent guardrail from panicking over a non-json ResponseEntity */
object ResponseEntityGuardrailSupport {
  implicit val responseEntityMarshaller: Marshaller[ResponseEntity, ResponseEntity] =
    Marshaller.opaque(identity)
}
