import com.daml.network.sv.util.{SvOnboardingToken, SvUtil}
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.topology.PartyId
import org.scalatest.wordspec.AsyncWordSpec

class SvOnboardingTokenTest extends AsyncWordSpec with BaseTest {

  "SV onboarding tokens are built and validated correctly" in {
    // random values for test
    val candidateParty = PartyId
      .fromProtoPrimitive(
        "svX::122020c99a2f48cd66782404648771eeaa104f108131c0c876a6ed04dd2e4175f27d"
      )
      .value
    val svcParty = PartyId
      .fromProtoPrimitive(
        "svc::122020c99a2f48cd66782404648771eeaa104f108131c0c876a6ed04dd2e4175f27d"
      )
      .value

    // keys generated using scripts/generate-sv-keys.sc
    val publicKey =
      "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE+U0mKc9YlTZFESl0A4tac6fhxFfXflo6lkNUhLMvgal0054Lm4mOsqQOigpIroALXha4u25bZ7PDvT32cZx/9g=="
    val privateKey =
      "MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCCOrvvs/R+VKFZ/eET1CkgwY15apSI7FSWngvEs44+xiQ=="

    val token = SvOnboardingToken("SvX", publicKey, candidateParty, svcParty)

    val encodedToken = token.signAndEncode(SvUtil.parsePrivateKey(privateKey).value).value
    val decodedToken = SvOnboardingToken.verifyAndDecode(encodedToken).value

    decodedToken should equal(token)
  }
}
