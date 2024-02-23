package com.daml.network.integration.tests

import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeIntegrationTestWithSharedEnvironment
import monocle.macros.syntax.lens.*
import math.Ordering.Implicits.*

class SvTimeBasedRewardCouponIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with SvTimeBasedIntegrationTestUtil {

  override def environmentDefinition: CNNodeEnvironmentDefinition =
    CNNodeEnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      // TODO (#9149): Remove this transform, as they will be enabled by default
      .addConfigTransform((_, config) =>
        config
          .focus(_.svApps)
          .modify(_.map { case (name, svConfig) =>
            name -> svConfig.focus(_.automation.useNewSvRewardIssuance).replace(true)
          })
      )

  "SVs" should {

    "receive and claim SvRewardCoupons" in { implicit env =>
      val openRounds = eventually() {
        val openRounds = sv1ScanBackend
          .getOpenAndIssuingMiningRounds()
          ._1
          .filter(_.payload.opensAt <= env.environment.clock.now.toInstant)
        openRounds should not be empty
        openRounds
      }

      eventually() {
        sv1WalletClient.listSvRewardCoupons() should have size openRounds.size.toLong
      }

    // TODO (#10247): Check that the wallet auto-collects the coupons
    }

  }

}
