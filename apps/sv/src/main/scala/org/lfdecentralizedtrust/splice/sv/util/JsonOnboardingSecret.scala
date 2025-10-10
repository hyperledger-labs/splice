// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.util

import io.circe.*
import io.circe.generic.semiauto.*

final case class JsonOnboardingSecret(
    sv: String,
    secret: String,
    validator_party_hint: String,
)

object JsonOnboardingSecret {
  implicit val decoder: Decoder[JsonOnboardingSecret] = deriveDecoder
  implicit val encoder: Encoder[JsonOnboardingSecret] = deriveEncoder
}
