package org.lfdecentralizedtrust.splice.sv.cometbft

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.drivers.cometbft.{
  NetworkConfigChangeRequest,
  SvNodeConfig,
  SvNodeConfigChange,
  SvNodeConfigChangeRequest,
}
import com.digitalasset.canton.drivers.cometbft.NetworkConfigChangeRequest.Kind.NodeConfigChangeRequest
import com.digitalasset.canton.drivers.cometbft.SvNodeConfigChange.Kind.SetConfig

import java.security.GeneralSecurityException
import org.scalatest.wordspec.AnyWordSpec

import java.util.Base64

class CometBftRequestSignerTest extends AnyWordSpec with BaseTest {

  "Signing requests" should {

    val cometBftRequestSigner = CometBftRequestSigner.getGenesisSigner

    val request = NetworkConfigChangeRequest(
      "id",
      "id",
      NodeConfigChangeRequest(
        SvNodeConfigChangeRequest.of(
          "id",
          currentConfigRevision = 0L,
          change = Some(
            SvNodeConfigChange.of(
              SetConfig(
                SvNodeConfig.of(
                  Map.empty,
                  Seq.empty,
                  Seq.empty,
                )
              )
            )
          ),
        )
      ),
    )

    val requestSignature = cometBftRequestSigner.signRequest(
      request
    )
    val bogusRequestSignature = {
      val x = requestSignature.clone()
      x.update(0, 13) // just change the first byte to see that it fails
      x
    }

    "generate the expected signature for a message" in {
      Base64.getEncoder.encodeToString(
        requestSignature
      ) shouldBe "OtG+Fsd8uhq3EERZdvywEADUW980isxCerhABRvlE7frCnbO10FZvEF84BaSSga1/eqmcN7lHpm2QeVDrEWXCg=="
    }

    "match public keys keys" in {
      cometBftRequestSigner.PubKeyBytes shouldBe cometBftRequestSigner.PubKeyBytesFromPrivateKey
    }

    "validate the correct signature for the request" in {
      cometBftRequestSigner.PubKey.verify(
        requestSignature,
        request.toByteArray,
      )
    }

    "fails validating a bogus signature for the request" in {
      an[GeneralSecurityException] should be thrownBy cometBftRequestSigner.PubKey.verify(
        bogusRequestSignature,
        request.toByteArray,
      )
    }

    "generate the expected fingerprint for the public key" in {
      cometBftRequestSigner.Fingerprint shouldBe "12202b5d36b909489e4e00464ae7b558183da96fabc9eca3ddc5e34fbdba246a4be6"
    }

  }

}
