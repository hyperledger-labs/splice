// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{ConfigurableApp, updateAutomationConfig}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.ExpiredAmuletTransferInstructionTrigger
import org.lfdecentralizedtrust.splice.util.*
import org.lfdecentralizedtrust.splice.http.v0.definitions.TransferInstructionResultOutput.members
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.transferinstructionv1.TransferInstruction

import java.time.Duration
import scala.concurrent.duration.DurationInt

class TimeBasedAmuletTransferInstructionExpiryIntegrationTest
  extends IntegrationTest
    with WalletTestUtil
    with TimeTestUtil
    with TriggerTestUtil {

  // We use the SimTime topology so we can easily bump the clock to trigger time-based expiry
  override def environmentDefinition: EnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(sv => {
          sv.withPausedTrigger[ExpiredAmuletTransferInstructionTrigger]
        })(config)
      )

  "expire amulet transfer instructions handling both locked and already unlocked amulets" in { implicit env =>

    onboardAliceAndBob()
    aliceWalletClient.tap(100.0)

    aliceWalletClient.list().amulets should have length 1

    val transfer_1 = aliceWalletClient.createTokenStandardTransfer(
      receiver = bobValidatorBackend.getValidatorPartyId(),
      amount = BigDecimal(10.0),
      description = "Transfer 1",
      expiresAt = getLedgerTime.plus(Duration.ofMinutes(5)),
      trackingId = "tracking-id-1"
    )

    val _ = aliceWalletClient.createTokenStandardTransfer(
      receiver = bobValidatorBackend.getValidatorPartyId(),
      amount = BigDecimal(10.0),
      description = "Transfer 2",
      expiresAt = getLedgerTime.plus(Duration.ofMinutes(5)),
      trackingId = "tracking-id-2"
    )

    aliceWalletClient.listTokenStandardTransfers()  should have length 2

    advanceTime(Duration.ofMinutes(10))

    aliceWalletClient.list().lockedAmulets should have length 2

    aliceWalletClient.withdrawTokenStandardTransfer(transfer_1.output match {
      case members.TransferInstructionPending(value) =>
        new TransferInstruction.ContractId(value.transferInstructionCid)
      case other => fail(s"Expected TransferInstructionPending, but got $other")
    })

    aliceWalletClient.list().lockedAmulets should have length 1

    sv1Backend.dsoDelegateBasedAutomation.trigger[ExpiredAmuletTransferInstructionTrigger].resume()

    advanceTime(Duration.ofDays(1))

    eventually(60.seconds) {
      aliceWalletClient.listTokenStandardTransfers() shouldBe empty
      aliceWalletClient.list().lockedAmulets shouldBe empty
    }
  }
}
