package org.lfdecentralizedtrust.splice.config

import com.digitalasset.canton.config.CantonConfig
import com.typesafe.config.ConfigFactory
import org.scalatest.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class SpliceConfigTest extends AnyFunSuite with EitherValues with Matchers {
  private implicit val elc: com.digitalasset.canton.logging.ErrorLoggingContext = SpliceConfig.elc
  test("Validator config is rejected when topup interval < pollingInterval") {
    val config = ConfigFactory.parseFile(
      new java.io.File("apps/app/src/test/resources/simple-topology-1sv.conf")
    )
    SpliceConfig.loadAndValidate(config) shouldBe a[Right[_, _]]
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
}
