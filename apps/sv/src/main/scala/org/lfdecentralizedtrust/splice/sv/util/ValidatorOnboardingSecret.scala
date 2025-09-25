// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.util

import com.digitalasset.canton.topology.PartyId
import io.circe.*
import io.circe.generic.semiauto.*
import io.circe.syntax.*
import java.nio.charset.StandardCharsets
import java.util.Base64
import JsonCodec.*

final case class ValidatorOnboardingSecret(
    sponsoringSv: PartyId,
    secret: String,
    partyHint: Option[String],
) {
  // We encode the secret as base64 instead of return a JSON object as they are often copy pasted in terminals
  // and this avoids the need to worry about string escaping.
  // To users these secrets are opaque anyway and don't need to be decoded outside of debugging needs.
  def toApiResponse: String =
    Base64.getEncoder().encodeToString(this.asJson.noSpaces.getBytes(StandardCharsets.UTF_8))
}

object ValidatorOnboardingSecret {
  implicit val encoder: Encoder[ValidatorOnboardingSecret] = deriveEncoder
  implicit val decoder: Decoder[ValidatorOnboardingSecret] = deriveDecoder
}
