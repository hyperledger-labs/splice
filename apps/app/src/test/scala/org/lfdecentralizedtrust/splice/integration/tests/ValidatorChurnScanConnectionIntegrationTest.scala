// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import cats.data.{NonEmptyList}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import org.apache.pekko.http.scaladsl.model.Uri
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithIsolatedEnvironment
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection.BftScanClientConfig
import org.lfdecentralizedtrust.splice.util.{SvTestUtil, WalletTestUtil}

class ValidatorChurnScanConnectionIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with WalletTestUtil
    with SvTestUtil
    with HasExecutionContext
    with HasActorSystem {

  override protected def runEventHistorySanityCheck: Boolean = false
  override protected def runUpdateHistorySanityCheck: Boolean = false

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllValidatorConfigs {
          case (name, c) if name == "aliceValidator" =>
            val dbEnabledConfig = BftScanClientConfig.Bft(
              seedUrls = NonEmptyList.of(
                Uri("http://127.0.0.1:5012")
              ),
              scansRefreshInterval = NonNegativeFiniteDuration.ofSeconds(60),
              useLastKnownConnectionsForInitialization = true,
            )
            c.copy(scanClient = dbEnabledConfig)
          case (_, c) => c
        }(config)
      )
      .withManualStart


  "reboots with failed bootstrap scans" in { implicit env =>
    clue("Initialize the DSO network") {
      initDso()
    }

    clue("Start Alice's validator and trigger initial topology discovery") {
      aliceValidatorBackend.startSync()
    }

    clue("Stop SV1 scan to simulate a dead bootstrap seed node") {
      sv1ScanBackend.stop()
    }

    clue("Stop Alice's validator to prepare for reboot") {
      aliceValidatorBackend.stop()
    }

    clue("Reboot Alice's validator and verify it can recover using the other 3 SVs from the DB") {
      eventuallySucceeds() {
        aliceValidatorBackend.startSync()
        aliceValidatorBackend.onboardUser("Test2") // Use a different username just to be safe
      }
    }
  }
}
