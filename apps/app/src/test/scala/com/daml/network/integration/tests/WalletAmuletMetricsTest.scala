// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.integration.tests

import com.daml.network.integration.EnvironmentDefinition
import com.daml.network.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import com.daml.network.util.{WalletTestUtil}
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.metrics.MetricValue

class WalletAmuletMetricsTest
    extends IntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with WalletTxLogTestUtil {

  override def environmentDefinition: EnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
  }

  "Unlocked coin metrics" should {
    "update when tapping coin" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val before = aliceValidatorBackend.metrics
        .get(
          "cn.wallet.unlocked-amulet-balance",
          Map("owner" -> aliceUserParty.toString),
        )
        .select[MetricValue.DoublePoint]
        .value
        .value
      before shouldBe 0
      actAndCheck(
        "alice taps 100 coin",
        aliceWalletClient.tap(100.0),
      )(
        "metrics update to reflect new coins",
        _ => {
          val after = aliceValidatorBackend.metrics
            .get("cn.wallet.unlocked-amulet-balance", Map("owner" -> aliceUserParty.toString))
            .select[MetricValue.DoublePoint]
            .value
            .value
          val tapCC = walletUsdToAmulet(100.0)
          BigDecimal(after) should beWithin(tapCC - smallAmount, tapCC)
        },
      )
    }
  }
}
