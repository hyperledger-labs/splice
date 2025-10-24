// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
package org.lfdecentralizedtrust.splice.integration.tests

import cats.data.NonEmptyList
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.{SvTestUtil, WalletTestUtil}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.apache.pekko.http.scaladsl.model.Uri
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection.BftScanClientConfig
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.SuppressionRule
import org.slf4j.event.Level

class BftCustomScanConnectionIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with SvTestUtil
    with HasExecutionContext
    with HasActorSystem {

  override protected def runEventHistorySanityCheck: Boolean = false

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllValidatorConfigs {
          case (name, c) if name == "aliceValidator" =>
            val bftCustomConfig = BftScanClientConfig.BftCustom(
              seedUrls = NonEmptyList.one(Uri("http://127.0.0.1:5012")),
              trustedSvs =
                NonEmptyList.of(s"${getSvName(1)}", s"${getSvName(2)}", s"${getSvName(3)}"),
              threshold = Some(2),
              scansRefreshInterval = NonNegativeFiniteDuration.ofSeconds(5),
            )
            c.copy(scanClient = bftCustomConfig)
          case (_, c) => c
        }(config)
      )
      .withManualStart

  "simple threshold normal case validator onboarding succeeds" in { implicit env =>
    sv1Backend.startSync()
    sv1ScanBackend.startSync()
    sv2Backend.startSync()
    sv2ScanBackend.startSync()
    sv3Backend.startSync()
    sv3ScanBackend.startSync()
    sv4Backend.startSync()
    sv4ScanBackend.startSync()

    eventually() {
      val allHealthy = Seq(sv1ScanBackend, sv2ScanBackend, sv3ScanBackend, sv4ScanBackend).forall {
        scan =>
          scan.httpHealth.successOption.exists(_.active)
      }
      allHealthy shouldBe true
    }

    loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
      {
        aliceValidatorBackend.startSync()
      },
      logs => {
        val messages = logs.map(_.message)
        messages.exists(
          _.contains(
            s"Successfully established initial connection to trusted scan: ${getSvName(1)}"
          )
        ) should be(true)
        messages.exists(
          _.contains(
            s"Successfully established initial connection to trusted scan: ${getSvName(2)}"
          )
        ) should be(true)
        messages.exists(
          _.contains(
            s"Successfully established initial connection to trusted scan: ${getSvName(3)}"
          )
        ) should be(true)
        messages.exists(
          _.contains(
            s"Successfully established initial connection to trusted scan: ${getSvName(4)}"
          )
        ) should be(false)
      },
    )

    eventuallySucceeds() {
      aliceValidatorBackend.onboardUser(aliceWalletClient.config.ledgerApiUser)
    }
  }

  "reconnects to a recovered SV after the refresh interval" in { implicit env =>
    sv1Backend.startSync()
    sv1ScanBackend.startSync()
    sv2Backend.startSync()
    sv2ScanBackend.startSync()
    sv4Backend.startSync()
    sv4ScanBackend.startSync()

    eventually() {
      val allHealthy = Seq(sv1ScanBackend, sv2ScanBackend, sv4ScanBackend).forall { scan =>
        scan.httpHealth.successOption.exists(_.active)
      }
      allHealthy shouldBe true
    }

    loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
      {
        aliceValidatorBackend.startSync()
      },
      logs => {
        val messages = logs.map(_.message)
        messages.exists(
          _.contains(
            s"Successfully established initial connection to trusted scan: ${getSvName(1)}"
          )
        ) should be(true)
        messages.exists(
          _.contains(
            s"Successfully established initial connection to trusted scan: ${getSvName(2)}"
          )
        ) should be(true)
        messages.exists(
          _.contains(
            s"Successfully established initial connection to trusted scan: ${getSvName(3)}"
          )
        ) should be(false)
        messages.exists(
          _.contains(
            s"Successfully established initial connection to trusted scan: ${getSvName(4)}"
          )
        ) should be(false)
      },
    )

    loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
      {
        sv3Backend.startSync()
        sv3ScanBackend.startSync()
      },
      logs => {
        val messages = logs.map(_.message)
        messages.exists(_.contains(s"Successfully connected to scan of ${getSvName(3)}")) should be(
          true
        )
      },
    )

    eventuallySucceeds() {
      aliceValidatorBackend.onboardUser(aliceWalletClient.config.ledgerApiUser)
    }
  }
}
