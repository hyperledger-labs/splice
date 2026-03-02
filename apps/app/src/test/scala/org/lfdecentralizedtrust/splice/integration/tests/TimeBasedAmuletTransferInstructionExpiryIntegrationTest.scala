// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{ConfigurableApp, updateAutomationConfig}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.ExpiredAmuletTransferInstructionTrigger
import org.lfdecentralizedtrust.splice.util.*


class AmuletTransferInstructionExpiryIntegrationTest
  extends IntegrationTest
    with WalletTestUtil
    with TimeTestUtil
    with TriggerTestUtil {

  // We use the SimTime topology so we can easily bump the clock to trigger time-based expiry
  override def environmentDefinition: EnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      // Start the trigger in paused state so we can manipulate the contracts before it fires
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(sv => {
          sv.withPausedTrigger[ExpiredAmuletTransferInstructionTrigger]
        })(config)
      )

  "expire amulet transfer instructions handling both locked and already unlocked amulets" in { implicit env =>

    onboardAliceAndBob()
    aliceWalletClient.tap(100.0)
    aliceWalletClient.tap(100.0)

    val amulets = aliceWalletClient.list().amulets
    amulets should have length 2


  }
}
