// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.auth

import java.net.URL

sealed trait AuthConfig {
  val audience: String
}

object AuthConfig {
  case class Hs256Unsafe(
      audience: String,
      secret: String,
  ) extends AuthConfig

  case class Rs256(
      audience: String,
      jwksUrl: URL,
  ) extends AuthConfig

  def hideConfidential(config: AuthConfig): AuthConfig = {
    val hidden = "****"
    config match {
      case Hs256Unsafe(audience, _) => Hs256Unsafe(audience, hidden)
      // being explicit here to avoid accidental leaks if we extend
      // `AuthConfig` at some point
      case Rs256(audience, jwksUrl) => Rs256(audience, jwksUrl)
    }
  }
}
