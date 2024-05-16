package com.daml.network.http

import com.daml.network.http.UrlValidator.InvalidScheme
import com.digitalasset.canton.BaseTest
import org.scalatest.wordspec.AnyWordSpec

class UrlValidatorTest extends AnyWordSpec with BaseTest {

  "the url validator" should {

    "accept valid http urls" in {
      forAll(
        Table(
          "url",
          "http://example.com",
          "http://example.com/url",
          "http://example.com:80/url",
        )
      ) { url =>
        UrlValidator.isValid(url).value shouldBe url
      }
    }

    "accept valid https urls" in {
      forAll(
        Table(
          "url",
          "https://example.com",
          "https://example.com/url",
          "https://example.com:443/url",
        )
      ) { url =>
        UrlValidator.isValid(url).value shouldBe url
      }
    }

    "reject urls without a scheme" in {
      UrlValidator.isValid("example.com").left.value shouldBe InvalidScheme
    }

    "reject invalid urls" in {
      forAll(
        Table(
          "url",
          "http://",
          "https://",
          "http:/example.com",
          "https:/example.com",
          "https://:443",
          "http://:80",
          ":443",
        )
      ) { url =>
        leftOrFail(UrlValidator.isValid(url))(s"expected invalid url $url")
      }
    }

  }

}
