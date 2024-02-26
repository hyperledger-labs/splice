package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.svlocal.approvedsvidentity.ApprovedSvIdentity

class SvIdentityIntegrationTest extends SvIntegrationTestBase {

  "SVs create approval contracts for configured approved SV identities" in { implicit env =>
    initSvc()
    clue("SV1 has created an ApprovedSvIdentity contract as it's configured to.") {
      inside(
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(ApprovedSvIdentity.COMPANION)(sv1Backend.getSvcInfo().svParty)
      ) {
        case approvedSvIds => {
          // if this check fails:
          // make sure that the values (especially the key) are in sync with sv1's config file
          approvedSvIds should have size 3
          val maybeSv2ApprovedSvId =
            approvedSvIds.find(_.data.candidateName == "Canton-Foundation-2")
          inside(maybeSv2ApprovedSvId) { case Some(sv2ApprovedSvId) =>
            sv2ApprovedSvId.data.candidateKey shouldBe "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEVdt8tLAfv+6H6s6EGpYMbthSdtEbykUO2Fau0k2wipf/6C0A/+xzKtqKJlBkybcBiICG/ZonGkuKgWBAC1jVAg=="
          }
          val maybeSv3ApprovedSvId =
            approvedSvIds.find(_.data.candidateName == "Canton-Foundation-3")
          inside(maybeSv3ApprovedSvId) { case Some(sv2ApprovedSvId) =>
            sv2ApprovedSvId.data.candidateKey shouldBe "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE7sHQDYkVisVznuFqvjWWxH3u8S+f07f1HCZ+mx+yj28ysRJjbatPNnsVAbiFDu2XOqyITx+os/Gd39piOfyw2w=="
          }
          val maybeSv4ApprovedSvId =
            approvedSvIds.find(_.data.candidateName == "Canton-Foundation-4")
          inside(maybeSv4ApprovedSvId) { case Some(sv4ApprovedSvId) =>
            sv4ApprovedSvId.data.candidateKey shouldBe "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEZMNsDJr1uTwMTIIlzUZpUexTLqVGMsD7cR4Y8sqYYFYhldVMeHG5zSubf+p+WZbLEyMUCT5nBCCBh0oiUY9crA=="
          }
        }
      }
    }
  }

}
