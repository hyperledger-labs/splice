// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.auth

import com.auth0.jwk.JwkProvider
import com.auth0.jwt.interfaces.RSAKeyProvider

import java.security.PublicKey
import java.security.interfaces.RSAPublicKey

@SuppressWarnings(Array("org.wartremover.warts.Null"))
class JwksRSAKeyProvider(provider: JwkProvider) extends RSAKeyProvider {
  override def getPublicKeyById(kid: String): RSAPublicKey = {
    // Received 'kid' value might be null if it wasn't defined in the Token's header
    val publicKey = provider.get(kid).getPublicKey()

    publicKey match {
      case rsaPublicKey: RSAPublicKey => rsaPublicKey
      case _: PublicKey =>
        throw new Error(
          "Expected a JWKS Provider with an RSA public key, found a non-RSA public key"
        )
    }
  }

  override def getPrivateKey() = null

  override def getPrivateKeyId() = null
}
