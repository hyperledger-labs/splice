// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.util

import com.digitalasset.canton.topology.PartyId
import io.circe.parser.decode
import io.circe.syntax.*
import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.util.Try

object Secrets {
  private def decodeJsonSecret(str: String): Option[ValidatorOnboardingSecret] =
    decode[JsonOnboardingSecret](str).toOption.map { json =>
      ValidatorOnboardingSecret(
        PartyId.tryFromProtoPrimitive(json.sv),
        json.secret,
        Some(json.validator_party_hint),
      )
    }

  private def decodeBase64Secret(str: String): Option[ValidatorOnboardingSecret] =
    Try(Base64.getDecoder.decode(str)).toOption.flatMap { decodedBytes =>
      val decodedStr = new String(decodedBytes, StandardCharsets.UTF_8)
      decode[ValidatorOnboardingSecret](decodedStr).toOption
    }

  // There are two ways to create secrets:
  // 1. Through the UI/API endpoints. The actual secret (excluding the wrapper that adds the SV party id) is a base64 encoded string of length 30.
  // 2. Through `expected-validator-onboardings`. In that case, the secret can be whatever the user chose so it must not be a base64 string.
  // So for backwards compatibility we interpret a secret that is not base64 or does not decode to a JSON object
  // as a legacy token without the wrapper including the SV party id.
  def decodeValidatorOnboardingSecret(
      secret: String,
      fallbackSv: PartyId,
  ): ValidatorOnboardingSecret =
    (secret match {
      case s if s.startsWith("{") => decodeJsonSecret(s)
      case s => decodeBase64Secret(s)
    }).getOrElse(ValidatorOnboardingSecret(fallbackSv, secret, None))

  def encodeValidatorOnboardingSecret(
      secret: ValidatorOnboardingSecret
  ): String = (
    secret.partyHint match {
      case None => secret.secret;
      case Some(hint) =>
        JsonOnboardingSecret(
          secret.sponsoringSv.toProtoPrimitive,
          secret.secret,
          hint,
        ).asJson.noSpaces
    }
  )
}
