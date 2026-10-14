package org.lfdecentralizedtrust.splice.config

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.config.CantonConfig
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AsyncWordSpec

import org.lfdecentralizedtrust.splice.wallet.config.{
  AppRewardBeneficiaryConfig,
  RewardSharingConfig,
}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.topology.PartyId

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

  "rate limiting config" should {

    def perClientIpOf(cfg: SpliceConfig) =
      cfg.scanApps.values.headOption.value.parameters.rateLimiting.global.perClientIp

    "parse the per client IP CIDR overrides" in {
      val overwrite = ConfigFactory.parseString(
        """
          |canton.scan-apps.sv1Scan.parameters.rate-limiting.global.per-client-ip {
          |  enabled = true
          |  limit.rate-per-second = 10
          |  ip-overrides {
          |    "10.0.0.0/8" = { rate-per-second = 100 }
          |    "192.0.2.0/24" = { enabled = false, rate-per-second = 0 }
          |    "10.1.2.3" = { rate-per-second = 200 }
          |    "2001:db8::/32" = { rate-per-second = 300 }
          |    "2001:db8:1::/48" = { enabled = false, rate-per-second = 0 }
          |    "2001:db8:2:3:4:5:6:7" = { rate-per-second = 400 }
          |    "::/0" = { rate-per-second = 500 }
          |  }
          |}
          """.stripMargin
      )
      val loaded =
        SpliceConfig.loadAndValidate(CantonConfig.mergeConfigs(config, Seq(overwrite))).value
      val perClientIp = perClientIpOf(loaded)
      perClientIp.limit.ratePerSecond should be(10d)
      perClientIp.attributeOverrides.keySet should be(
        Set(
          "10.0.0.0/8",
          "192.0.2.0/24",
          "10.1.2.3",
          "2001:db8::/32",
          "2001:db8:1::/48",
          "2001:db8:2:3:4:5:6:7",
          "::/0",
        )
      )
      perClientIp.attributeOverrides("10.0.0.0/8").ratePerSecond should be(100d)
      perClientIp.attributeOverrides("192.0.2.0/24").enabled should be(false)
      perClientIp.attributeOverrides("10.1.2.3").ratePerSecond should be(200d)
      perClientIp.attributeOverrides("2001:db8::/32").ratePerSecond should be(300d)
      perClientIp.attributeOverrides("2001:db8:1::/48").enabled should be(false)
      perClientIp.attributeOverrides("2001:db8:2:3:4:5:6:7").ratePerSecond should be(400d)
      perClientIp.attributeOverrides("::/0").ratePerSecond should be(500d)
    }

    Seq(
      "not-an-ip/8",
      "10.0.0.0/33",
      "10.0.0.256/8",
      "2001:db8::/129",
      "2001:db8:::1/32",
      "not-an-ip/32",
    ).foreach { cidr =>
      s"reject the invalid per client IP CIDR override '$cidr'" in {
        val overwrite = ConfigFactory.parseString(
          s"""
             |canton.scan-apps.sv1Scan.parameters.rate-limiting.rate-limiters.getDsoInfo.per-client-ip {
             |  ip-overrides {
             |    "$cidr" = { rate-per-second = 100 }
             |  }
             |}
          """.stripMargin
        )
        SpliceConfig
          .loadAndValidate(CantonConfig.mergeConfigs(config, Seq(overwrite)))
          .left
          .value
          .toString should include(cidr)
      }
    }

    "reject unknown per client IP keys" in {
      val overwrite = ConfigFactory.parseString(
        """
          |canton.scan-apps.sv1Scan.parameters.rate-limiting.global.per-client-ip {
          |  attribute-overrides {
          |    "10.0.0.0/8" = { rate-per-second = 100 }
          |  }
          |}
          """.stripMargin
      )
      SpliceConfig
        .loadAndValidate(CantonConfig.mergeConfigs(config, Seq(overwrite)))
        .left
        .value
        .toString should include("attribute-overrides")
    }

    "default to no per client IP overrides" in {
      perClientIpOf(SpliceConfig.loadAndValidate(config).value).attributeOverrides should be(empty)
    }
  }

  // Shared helper for RewardSharingConfig tests
  private def mkSharingCfg(percentages: BigDecimal*): RewardSharingConfig.BuiltIn =
    RewardSharingConfig.BuiltIn(
      minTtlAfterSharing = NonNegativeFiniteDuration.ofHours(30),
      beneficiaries = percentages.zipWithIndex.map { case (pct, i) =>
        AppRewardBeneficiaryConfig(
          PartyId.tryFromProtoPrimitive(s"party$i::1220"),
          pct,
        )
      },
    )

  private val provider = PartyId.tryFromProtoPrimitive("provider::1220")

  "RewardSharingConfig.providerRemainder" should {
    Seq(
      ("no beneficiaries", Seq.empty[BigDecimal], BigDecimal(1.0)),
      ("single beneficiary", Seq(BigDecimal(0.3)), BigDecimal(0.7)),
      ("two beneficiaries", Seq(BigDecimal(0.3), BigDecimal(0.2)), BigDecimal(0.5)),
      ("full allocation", Seq(BigDecimal(1.0)), BigDecimal(0.0)),
      ("near-total", Seq(BigDecimal(0.5), BigDecimal(0.49)), BigDecimal(0.01)),
    ).foreach { case (desc, percentages, expected) =>
      s"return $expected for $desc" in {
        mkSharingCfg(percentages*).providerRemainder shouldBe expected
      }
    }
  }

  "RewardSharingConfig.allBeneficiaries" should {
    "include provider with remainder" in {
      val all = mkSharingCfg(BigDecimal(0.3), BigDecimal(0.2)).allBeneficiaries(provider)
      all should have size 3
      all.last.beneficiary shouldBe provider
      all.last.percentage shouldBe BigDecimal(0.5)
    }

    "exclude provider when fully allocated" in {
      val all = mkSharingCfg(BigDecimal(1.0)).allBeneficiaries(provider)
      all should have size 1
      all.headOption.value.beneficiary shouldBe PartyId.tryFromProtoPrimitive("party0::1220")
    }

    "return only provider when no beneficiaries" in {
      val all = mkSharingCfg().allBeneficiaries(provider)
      all should have size 1
      all.headOption.value.beneficiary shouldBe provider
      all.headOption.value.percentage shouldBe BigDecimal(1.0)
    }
  }

  "RewardSharingConfig.allDamlBeneficiaries" should {
    "convert percentages to Daml Decimal scale 10" in {
      val all = mkSharingCfg(BigDecimal(0.3), BigDecimal(0.2)).allDamlBeneficiaries(provider)
      all should have size 3
      all.map(_._2.scale()) shouldBe Seq(10, 10, 10)
    }

    Seq(
      ("two-way split", Seq(BigDecimal(0.3), BigDecimal(0.2))),
      ("three-way split", Seq(BigDecimal(0.33), BigDecimal(0.33), BigDecimal(0.33))),
      ("high precision", Seq(BigDecimal(0.123456789), BigDecimal(0.876543210))),
      ("single beneficiary", Seq(BigDecimal(0.5))),
      ("full allocation", Seq(BigDecimal(1.0))),
      ("no beneficiaries", Seq.empty[BigDecimal]),
    ).foreach { case (desc, percentages) =>
      s"$desc sums to exactly 1.0 at Daml precision" in {
        val all = mkSharingCfg(percentages*).allDamlBeneficiaries(provider)
        val sum = all.map(_._2).foldLeft(java.math.BigDecimal.ZERO)(_.add(_))
        sum.compareTo(java.math.BigDecimal.ONE) shouldBe 0
      }
    }
  }

  "rewardSharingConfigByParty" should {

    def mkHoconConfig(beneficiaries: String): String =
      s"""
        |canton.validator-apps.aliceValidator.reward-sharing-config-by-party = {
        |  "alice::1220abc" = {
        |    type = "built-in"
        |    beneficiaries = [$beneficiaries]
        |    min-ttl-after-sharing = 30h
        |  }
        |}
        """.stripMargin

    def mkBeneficiary(name: String, percentage: String): String =
      s"""{ beneficiary = "$name::1220", percentage = $percentage }"""

    def beneficiariesFromPcts(percentages: String): String =
      percentages
        .split(",")
        .map(_.trim)
        .filter(_.nonEmpty)
        .zipWithIndex
        .map { case (pct, i) => mkBeneficiary(s"party$i", pct) }
        .mkString(", ")

    Seq(
      ("two beneficiaries", "0.3, 0.2"),
      ("single beneficiary", "0.5"),
      ("small percentage", "0.01"),
      ("percentage exactly 1.0", "1.0"),
      ("exact total split", "0.6, 0.4"),
      ("three-way even split", "0.33, 0.33, 0.33"),
      ("high precision", "0.123456789, 0.876543210"),
      ("empty beneficiaries", ""),
    ).foreach { case (desc, percentages) =>
      s"accept $desc ($percentages)" in {
        val overwrite =
          ConfigFactory.parseString(mkHoconConfig(beneficiariesFromPcts(percentages)))
        val validConfig = CantonConfig.mergeConfigs(config, Seq(overwrite))
        SpliceConfig.loadAndValidate(validConfig) shouldBe a[Right[?, ?]]
      }
    }

    Seq(
      ("percentage > 1.0", "1.5", "must be in (0.0, 1.0]"),
      ("percentage = 0", "0.0", "must be in (0.0, 1.0]"),
      ("negative percentage", "-0.1", "must be in (0.0, 1.0]"),
      ("sum > 1.0", "0.6, 0.5", "must sum to at most 1.0"),
    ).foreach { case (desc, percentage, expectedError) =>
      s"reject $desc" in {
        val beneficiaries =
          if (percentage.contains(",")) beneficiariesFromPcts(percentage)
          else mkBeneficiary("charlie", percentage)
        val overwrite = ConfigFactory.parseString(mkHoconConfig(beneficiaries))
        val buggyConfig = CantonConfig.mergeConfigs(config, Seq(overwrite))
        SpliceConfig.loadAndValidate(buggyConfig).left.value.toString should include(expectedError)
      }
    }

    "accept custom batchSize" in {
      val overwrite = ConfigFactory.parseString(
        """
          |canton.validator-apps.aliceValidator.reward-sharing-config-by-party = {
          |  "alice::1220abc" = {
          |    type = "built-in"
          |    beneficiaries = [{ beneficiary = "bob::1220", percentage = 0.4 }]
          |    min-ttl-after-sharing = 30h
          |    batch-size = 50
          |  }
          |}
          """.stripMargin
      )
      val validConfig = CantonConfig.mergeConfigs(config, Seq(overwrite))
      SpliceConfig.loadAndValidate(validConfig) shouldBe a[Right[?, ?]]
    }

    "reject batchSize = 0" in {
      val overwrite = ConfigFactory.parseString(
        """
          |canton.validator-apps.aliceValidator.reward-sharing-config-by-party = {
          |  "alice::1220abc" = {
          |    type = "built-in"
          |    beneficiaries = [{ beneficiary = "bob::1220", percentage = 0.4 }]
          |    min-ttl-after-sharing = 30h
          |    batch-size = 0
          |  }
          |}
          """.stripMargin
      )
      val buggyConfig = CantonConfig.mergeConfigs(config, Seq(overwrite))
      SpliceConfig
        .loadAndValidate(buggyConfig)
        .left
        .value
        .toString should include("batchSize")
    }

    def sharingConfigOf(cfg: SpliceConfig): RewardSharingConfig =
      cfg.validatorApps.values
        .flatMap(_.rewardSharingConfigByParty.get("alice::1220abc"))
        .loneElement

    "accept type = external with no beneficiaries and custom batch size" in {
      val overwrite = ConfigFactory.parseString(
        """
          |canton.validator-apps.aliceValidator.reward-sharing-config-by-party = {
          |  "alice::1220abc" = {
          |    type = "external"
          |    batch-size = 500
          |  }
          |}
          """.stripMargin
      )
      val validConfig = CantonConfig.mergeConfigs(config, Seq(overwrite))
      val loaded = SpliceConfig.loadAndValidate(validConfig).value
      sharingConfigOf(loaded) shouldBe RewardSharingConfig.External(batchSize = 500)
    }

    "accept explicit type = built-in with beneficiaries" in {
      val overwrite = ConfigFactory.parseString(
        """
          |canton.validator-apps.aliceValidator.reward-sharing-config-by-party = {
          |  "alice::1220abc" = {
          |    type = "built-in"
          |    beneficiaries = [{ beneficiary = "bob::1220", percentage = 0.4 }]
          |    min-ttl-after-sharing = 30h
          |  }
          |}
          """.stripMargin
      )
      val validConfig = CantonConfig.mergeConfigs(config, Seq(overwrite))
      val loaded = SpliceConfig.loadAndValidate(validConfig).value
      sharingConfigOf(loaded) shouldBe a[RewardSharingConfig.BuiltIn]
    }

    "reject type = external, with beneficiaries" in {
      val overwrite = ConfigFactory.parseString(
        """
          |canton.validator-apps.aliceValidator.reward-sharing-config-by-party = {
          |  "alice::1220abc" = {
          |    type = "external"
          |    beneficiaries = [{ beneficiary = "bob::1220", percentage = 0.4 }]
          |  }
          |}
          """.stripMargin
      )
      val validConfig = CantonConfig.mergeConfigs(config, Seq(overwrite))
      SpliceConfig.loadAndValidate(validConfig) shouldBe a[Left[?, ?]]
    }

    "reject an invalid type value" in {
      val overwrite = ConfigFactory.parseString(
        """
          |canton.validator-apps.aliceValidator.reward-sharing-config-by-party = {
          |  "alice::1220abc" = {
          |    type = "bogus"
          |  }
          |}
          """.stripMargin
      )
      val buggyConfig = CantonConfig.mergeConfigs(config, Seq(overwrite))
      SpliceConfig.loadAndValidate(buggyConfig) shouldBe a[Left[?, ?]]
    }

    "default to built-in when type is omitted (legacy config shape)" in {
      val overwrite = ConfigFactory.parseString(
        """
          |canton.validator-apps.aliceValidator.reward-sharing-config-by-party = {
          |  "alice::1220abc" = {
          |    beneficiaries = [{ beneficiary = "bob::1220", percentage = 0.4 }]
          |    min-ttl-after-sharing = 30h
          |  }
          |}
      """.stripMargin
      )
      val loaded =
        SpliceConfig.loadAndValidate(CantonConfig.mergeConfigs(config, Seq(overwrite))).value
      sharingConfigOf(loaded) shouldBe a[RewardSharingConfig.BuiltIn]
    }
  }
}
