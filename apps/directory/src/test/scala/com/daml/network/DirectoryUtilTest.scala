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
        val invalidNames = Table(
          ("statement", "name"),
          ("empty string", ""),
          ("empty string with suffix", ".unverified.cns"),
          ("restricted special characters", "aliceentry~$!@%^&*()+.unverified.cns"),
          ("dots before the required suffix", "alice.entry.unverified.cns"),
          ("incorrect suffix", "alice.cns"),
          ("uppercase characters", "alice_Entry.unverified.cns"),
          ("too many characters", "my_very_l0ng_cns_nam3_with_over_40_characters.unverified.cns"),
        )

        forAll(invalidNames) { (statement, name) =>
          s"$statement" in {
            DirectoryUtil.isValidEntryName(name) shouldBe false
          }
        }

      }
    }

    "isValidEntryUrl" should {
      "be truthy for url with" should {
        val validUrls = Table(
          ("statement", "url"),
          ("empty string", ""),
          ("https urls", "https://alice.cns.test.com"),
          ("http urls", "http://alice.cns.test.com"),
          ("alpha numeric urls", "https://alice123.cns.test.com"),
        )

        forAll(validUrls) { (statement, url) =>
          s"$statement" in {
            DirectoryUtil.isValidEntryUrl(url) shouldBe true
          }
        }
      }

      "be falsy for url with" should {
        val longUrl = s"https://${"alice-" * 60}entry.cns.com"
        val invalidUrls = Table(
          ("statement", "url"),
          ("invalid URL format", "https://alice.cns domain%"),
          ("wrong url scheme", "ftp://alice.cns.test.com"),
          ("url with no scheme", "alice.cns"),
          ("url longer than 255 characters", longUrl),
        )

        forAll(invalidUrls) { (statement, url) =>
          s"$statement" in {
            DirectoryUtil.isValidEntryUrl(url) shouldBe false
          }
        }
      }
    }

    "isValidEntryDescription" should {
      "be truthy for description with" should {
        val validDescriptions = Table(
          ("statement", "description"),
          ("empty string", ""),
          ("alpha numeric description", "alice 1 Sample CNS Directory Entry Description"),
        )

        forAll(validDescriptions) { (statement, desc) =>
          s"$statement" in {
            DirectoryUtil.isValidEntryDescription(desc) shouldBe true
          }
        }
      }

      "be falsy for description with" should {
        val longDescription = s"${"Sample CNS Directory Entry Description" * 20}"
        val invalidDescriptions = Table(
          ("statement", "url"),
          ("description longer than 140 characters", longDescription),
        )

        forAll(invalidDescriptions) { (statement, desc) =>
          s"$statement" in {
            DirectoryUtil.isValidEntryDescription(desc) shouldBe false
          }
        }
      }
    }

  }
}
