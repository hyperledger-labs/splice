package org.lfdecentralizedtrust.splice.config

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.config.CantonConfig
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AsyncWordSpec

class SpliceConfigTest extends AsyncWordSpec with BaseTest {
  private implicit val elc: com.digitalasset.canton.logging.ErrorLoggingContext = SpliceConfig.elc
  val config = ConfigFactory.parseFile(
    new java.io.File("apps/app/src/test/resources/simple-topology-1sv.conf")
  )

  "Validator config is rejected when topup interval < pollingInterval" in {
    SpliceConfig.loadAndValidate(config) shouldBe a[Right[?, ?]]
    val overwrite = ConfigFactory.parseString(
      """
      |canton.validator-apps.aliceValidator.domains.global.buy-extra-traffic.target-throughput = 500000
      |canton.validator-apps.aliceValidator.domains.global.buy-extra-traffic.min-topup-interval = 1s
     """.stripMargin
    )
    val buggyConfig = CantonConfig.mergeConfigs(config, Seq(overwrite))
    SpliceConfig.loadAndValidate(buggyConfig).left.value.toString should include(
      "topup interval 1 second must not be smaller than the polling interval 30 seconds"
    )
  }
  "disableSvValidatorBftSequencerConnection" should {
    "be rejected if svValidator is not true" in {
      val overwrite = ConfigFactory.parseString(
        """
      |canton.validator-apps.aliceValidator.disable-sv-validator-bft-sequencer-connection = true
     """.stripMargin
      )
      val buggyConfig = CantonConfig.mergeConfigs(config, Seq(overwrite))
      SpliceConfig.loadAndValidate(buggyConfig).left.value.toString should include(
        "disableSvValidatorBftSequencerConnection must not be set for non-sv validators"
      )
    }
    "be rejected if sequencer url is not set" in {
      val overwrite = ConfigFactory.parseString(
        """
      |canton.validator-apps.sv1Validator.disable-sv-validator-bft-sequencer-connection = true
     """.stripMargin
      )
      val buggyConfig = CantonConfig.mergeConfigs(config, Seq(overwrite))
      SpliceConfig.loadAndValidate(buggyConfig).left.value.toString should include(
        "disableSvValidatorBftSequencerConnection must be set together with domains.global.url"
      )
    }
    "be rejected if set to false and url is set" in {
      val overwrite = ConfigFactory.parseString(
        """
      |canton.validator-apps.sv1Validator.domains.global.url = "http://example.com"
     """.stripMargin
      )
      val buggyConfig = CantonConfig.mergeConfigs(config, Seq(overwrite))
      SpliceConfig.loadAndValidate(buggyConfig).left.value.toString should include(
        "domains.global.url must not be set for an SV unless disableSvValidatorBftSequencerConnection is also set"
      )
    }
    "be accepted if set to false for non-sv validator and url is set" in {
      val overwrite = ConfigFactory.parseString(
        """
      |canton.validator-apps.aliceValidator.domains.global.url = "http://example.com"
     """.stripMargin
      )
      val buggyConfig = CantonConfig.mergeConfigs(config, Seq(overwrite))
      SpliceConfig.loadAndValidate(buggyConfig) shouldBe a[Right[?, ?]]
    }
    "be accepted if set to true for sv validator and url is set" in {
      val overwrite = ConfigFactory.parseString(
        """
      |canton.validator-apps.sv1Validator.disable-sv-validator-bft-sequencer-connection = true
      |canton.validator-apps.sv1Validator.domains.global.url = "http://example.com"
     """.stripMargin
      )
      val buggyConfig = CantonConfig.mergeConfigs(config, Seq(overwrite))
      SpliceConfig.loadAndValidate(buggyConfig) shouldBe a[Right[?, ?]]
    }
  }
}
