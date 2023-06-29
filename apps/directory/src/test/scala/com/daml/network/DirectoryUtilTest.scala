package com.daml.network

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.BaseTestWordSpec
import com.daml.network.directory.DirectoryUtil

class DirectoryUtilTest extends BaseTest with BaseTestWordSpec {
  "DirectoryUtil" should {

    "isValidEntryName" should {

      "be truthy for names with" should {
        val validNames = Table(
          ("statement", "name"),
          ("no hyphen and underscore", "aliceentry.unverified.cns"),
          ("hyphen and underscore", "alice_entry-new.unverified.cns"),
          ("numbers", "alice_entry-101.unverified.cns"),
          ("correct suffix", "alice.unverified.cns"),
        )

        forAll(validNames) { (statement, name) =>
          s"$statement" in {
            DirectoryUtil.isValidEntryName(name) shouldBe true
          }
        }

      }

      "be false for names with" should {
        val validNames = Table(
          ("statement", "name"),
          ("restricted special characters", "aliceentry~$!@%^&*()+.unverified.cns"),
          ("dots before the required suffix", "alice.entry.unverified.cns"),
          ("incorrect suffix", "alice.cns"),
          ("too many characters", "my_very_l0ng_cns_nam3_with_over_40_characters.unverified.cns"),
        )

        forAll(validNames) { (statement, name) =>
          s"$statement" in {
            DirectoryUtil.isValidEntryName(name) shouldBe false
          }
        }

      }
    }
  }
}
