// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
package org.lfdecentralizedtrust.splice.integration.tests

import cats.data.NonEmptyList
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.{SvTestUtil, WalletTestUtil}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.apache.pekko.http.scaladsl.model.Uri
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection.BftScanClientConfig
import org.slf4j.event.Level

class TrustSpecificScanConnectionIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with SvTestUtil
    with HasExecutionContext
    with HasActorSystem {

  override protected def runEventHistorySanityCheck: Boolean = false

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .fromResources(Seq("simple-topology.conf"), this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllValidatorConfigs {
          case (name, c) if name == "aliceValidator" =>
            val trustSpecificConfig = BftScanClientConfig.TrustSpecific(
              seedUrls = NonEmptyList.one(Uri("http://127.0.0.1:5012")),
              trustedSvs =
                NonEmptyList.of("Digital-Asset-2", "Digital-Asset-Eng-2", "Digital-Asset-Eng-3"),
              threshold = Some(2),
            )
            c.copy(scanClient = trustSpecificConfig)
          case (_, c) => c
        }(config)
      )
      .withAllocatedUsers()
      .withInitializedNodes()
      .withTrafficTopupsEnabled
      .withInitialPackageVersions
      .withManualStart

  "simple threshold" in { implicit env =>
    startAllSync(
      sv1ScanBackend,
      sv1Backend,
      sv2ScanBackend,
      sv2Backend,
      sv3ScanBackend,
      sv3Backend,
      sv4ScanBackend,
      sv4Backend,
    )

    loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
      {
        aliceValidatorBackend.startSync()
      },
      logs => {
        val messages = logs.map(_.message)

        // Assert that connections were successfully made to the trusted SVs
        messages.exists(_.contains("Connection to trusted sv Digital-Asset-2 made.")) should be(
          true
        )
        messages.exists(_.contains("Connection to trusted sv Digital-Asset-Eng-2 made.")) should be(
          true
        )
        messages.exists(_.contains("Connection to trusted sv Digital-Asset-Eng-3 made.")) should be(
          true
        )

        // Assert that no connection attempt was made to the untrusted SV
        messages.exists(_.contains(s"Connection to trusted sv ${getSvName(4)} made.")) should be(
          false
        )

        // Assert that the connection threshold was met
        messages.exists(_.contains("created threshold number of scan connections.")) should be(true)
      },
    )

    eventuallySucceeds() {
      aliceValidatorBackend.onboardUser(aliceWalletClient.config.ledgerApiUser)
    }

  }
}
