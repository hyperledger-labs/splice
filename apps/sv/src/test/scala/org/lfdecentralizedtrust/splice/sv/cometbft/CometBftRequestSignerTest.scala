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

    val cometBftRequestSigner = CometBftRequestSigner.genesisSigner

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

    "validate the correct signature for the request" in {
      cometBftRequestSigner.pubKey.verify(
        requestSignature,
        request.toByteArray,
      )
    }

    "fails validating a bogus signature for the request" in {
      an[GeneralSecurityException] should be thrownBy cometBftRequestSigner.pubKey.verify(
        bogusRequestSignature,
        request.toByteArray,
      )
    }

    "generate the expected fingerprint for the public key" in {
      cometBftRequestSigner.fingerprint shouldBe "12202b5d36b909489e4e00464ae7b558183da96fabc9eca3ddc5e34fbdba246a4be6"
    }

    "fail to instantiate with a bogus public key" in {
      an[IllegalArgumentException] should be thrownBy new CometBftRequestSigner(
        "bogus",
        "tnhylw+c153Cz1NlSWxQxvfjuC4KhVsRGDZ63cfyUdc=",
      )
    }

    "fail to instantiate with a bogus private key" in {
      an[IllegalArgumentException] should be thrownBy new CometBftRequestSigner(
        "xty5jkeWPWXiuByBBxRupxCpsjl84qquvGrXWlekQ9s=",
        "bogus",
      )
    }

    // generated with `scripts/generate-cometbft-governance-keys.sh`
    "instantiate a working signer from a keypair generated with openssl" in {
      val signer = new CometBftRequestSigner(
        "xty5jkeWPWXiuByBBxRupxCpsjl84qquvGrXWlekQ9s=",
        "tnhylw+c153Cz1NlSWxQxvfjuC4KhVsRGDZ63cfyUdc=",
      )
      val signature = signer.signRequest(request)
      // throws if wrong
      signer.pubKey.verify(
        signature,
        request.toByteArray,
      )
    }
  }

}
