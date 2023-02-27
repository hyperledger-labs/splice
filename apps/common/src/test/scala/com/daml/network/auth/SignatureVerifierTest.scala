package com.daml.network.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.digitalasset.canton.BaseTest
import org.scalatest.wordspec.AnyWordSpecLike

class SignatureVerifierTest extends AnyWordSpecLike with BaseTest {
  "A SignatureVerifier" should {
    "Not throw an exception when audience is not set" in {
      val secret = "test"
      noException should be thrownBy {
        new HMACVerifier(audience = "", secret = secret).verify(
          JWT
            .create()
            .withSubject("user")
            .sign(Algorithm.HMAC256(secret))
        )
      }
    }
  }
}
