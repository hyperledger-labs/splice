// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
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

class ConfigurationProvidedBftScanConnectionIntegrationTest
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
              seedUrls = NonEmptyList.of(
                Uri("http://127.0.0.1:5012")
              ),
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

  def connectionEstablished(svName: Int, message: Seq[String]) = {
    message.exists(
      _.contains(
        s"Successfully established initial connection to trusted scan: ${getSvName(svName)}"
      )
    ) should be(true)
  }

  def connectionNotEstablished(svName: Int, message: Seq[String]) = {
    message.exists(
      _.contains(
        s"Successfully established initial connection to trusted scan: ${getSvName(svName)}"
      )
    ) should be(false)
  }

  def refreshConnectionEstablished(svName: Int, message: Seq[String]) = {
    message.exists(
      _.contains(
        s"Successfully connected to scan of ${getSvName(svName)}"
      )
    ) should be(true)
  }

  "simple threshold normal case validator onboarding succeeds" in { implicit env =>
    // checks if aliceValidatorBackend can connect to sv1, sv2, sv3 and not sv4

    startAllSync(
      sv1Backend,
      sv1ScanBackend,
      sv2Backend,
      sv2ScanBackend,
      sv3Backend,
      sv3ScanBackend,
      sv4Backend,
      sv4ScanBackend,
    )

    eventually() {
      val allHealthy = Seq(sv1ScanBackend, sv2ScanBackend, sv3ScanBackend, sv4ScanBackend).forall {
        scan =>
          scan.httpHealth.successOption.exists(_.active)
      }
      allHealthy shouldBe true
    }

    loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
      {
        // start alice validator
        aliceValidatorBackend.startSync()
      },
      logs => {
        val messages = logs.map(_.message)
        connectionEstablished(1, messages) // sv1 connected
        connectionEstablished(2, messages) // sv2 connected
        connectionEstablished(3, messages) // sv3 connected
        connectionNotEstablished(4, messages) // sv4 not connected
      },
    )

    eventuallySucceeds() {
      // to make sure aliceValidatorBackend is working properly
      aliceValidatorBackend.onboardUser(aliceWalletClient.config.ledgerApiUser)
    }
  }

  "reconnects to a recovered SV after the refresh interval" in { implicit env =>
    startAllSync(sv1Backend, sv1ScanBackend, sv2Backend, sv2ScanBackend, sv4Backend, sv4ScanBackend)
    // check if aliceValidatorBackend can connect to sv1, sv2, and work, although sv3 is down
    eventually() {
      val allHealthy = Seq(sv1ScanBackend, sv2ScanBackend, sv4ScanBackend).forall { scan =>
        scan.httpHealth.successOption.exists(_.active)
      }
      allHealthy shouldBe true
    }

    loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
      {
        // start alice validator
        aliceValidatorBackend.startSync()
      },
      logs => {
        val messages = logs.map(_.message)
        connectionEstablished(1, messages) // sv1 connected
        connectionEstablished(2, messages) // sv2 connected
        connectionNotEstablished(3, messages) // sv3 not connected
        connectionNotEstablished(4, messages) // sv4 not connected
      },
    )

    loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
      {
        // now start sv3
        sv3Backend.startSync()
        sv3ScanBackend.startSync()
      },
      logs => {
        val messages = logs.map(_.message)
        refreshConnectionEstablished(3, messages) // sv3 reconnected
      },
    )

    eventuallySucceeds() {
      // to make sure aliceValidatorBackend is working properly
      aliceValidatorBackend.onboardUser(aliceWalletClient.config.ledgerApiUser)
    }
  }
}
