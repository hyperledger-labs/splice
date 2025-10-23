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
import com.digitalasset.canton.config.NonNegativeFiniteDuration

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
                NonEmptyList.of(s"${getSvName(1)}", s"${getSvName(2)}", s"${getSvName(3)}"),
              threshold = Some(2),
              scansRefreshInterval = NonNegativeFiniteDuration.ofSeconds(5),
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

  private def assertSuccessfulConnection(
      messages: Seq[String],
      svIndex: Int,
      connected: Boolean = true,
  ): org.scalatest.Assertion = {
    val logMessage = s"Successfully connected to scan of ${getSvName(svIndex)}"

    if (connected) {
      messages.exists(_.contains(logMessage)) should be(true)
    } else {
      messages.exists(_.contains(logMessage)) should be(false)
    }
  }

  private def assertThresholdMet(messages: Seq[String]): org.scalatest.Assertion = {

    messages.exists(_.contains("created threshold number of scan connections")) should be(true)

  }

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

        assertSuccessfulConnection(messages, 1)
        assertSuccessfulConnection(messages, 2)
        assertSuccessfulConnection(messages, 3)
        assertSuccessfulConnection(messages, 4, false)
        assertThresholdMet(messages)
      },
    )

    eventuallySucceeds() {
      aliceValidatorBackend.onboardUser(aliceWalletClient.config.ledgerApiUser)
    }
  }

  "starts successfully even with one trusted SV down" in { implicit env =>
    startAllSync(
      sv1ScanBackend,
      sv1Backend,
      sv2Backend,
      sv3ScanBackend,
      sv3Backend,
      sv4ScanBackend,
      sv4Backend,
    )

    loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
      {
        aliceValidatorBackend.startSync()
      },
      logs => {
        val messages = logs.map(_.message)

        assertSuccessfulConnection(messages, 1)
        assertSuccessfulConnection(messages, 3)
        assertSuccessfulConnection(messages, 2, connected = false)
        assertSuccessfulConnection(messages, 4, connected = false)
        assertThresholdMet(messages)
      },
    )

    eventuallySucceeds() {
      aliceValidatorBackend.onboardUser(aliceWalletClient.config.ledgerApiUser)
    }
  }

  "reconnects to a recovered SV after the refresh interval" in { implicit env =>
    startAllSync(
      sv1ScanBackend,
      sv1Backend,
      sv2Backend,
      sv3ScanBackend,
      sv3Backend,
      sv4ScanBackend,
      sv4Backend,
    )

    loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
      {
        aliceValidatorBackend.startSync()
      },
      logs => {
        val messages = logs.map(_.message)

        assertSuccessfulConnection(messages, 1)
        assertSuccessfulConnection(messages, 3)
        assertSuccessfulConnection(messages, 2, connected = false)
        assertSuccessfulConnection(messages, 4, connected = false)
        assertThresholdMet(messages)
      },
    )

    loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
      {
        sv2ScanBackend.startSync()
      },
      logs => {
        val messages = logs.map(_.message)
        assertSuccessfulConnection(messages, 2)
      },
    )

    eventuallySucceeds() {
      aliceValidatorBackend.onboardUser(aliceWalletClient.config.ledgerApiUser)
    }
  }
}
