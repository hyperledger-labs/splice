// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
package org.lfdecentralizedtrust.splice.integration.tests

import cats.data.NonEmptyList
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import org.apache.pekko.http.scaladsl.model.Uri
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection.BftScanClientConfig
import org.lfdecentralizedtrust.splice.util.{SvTestUtil, WalletTestUtil}
import org.slf4j.event.Level

class InternalConfigEnabledBftScanConnectionIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with SvTestUtil
    with HasExecutionContext
    with HasActorSystem {

  override protected def runEventHistorySanityCheck: Boolean = false
  override protected def runUpdateHistorySanityCheck: Boolean = false

  private def existsUrl(messages: Seq[String], uri: String) = {
    messages.exists(_.contains(s"Validator bootstrapping with scan URI: $uri"))
  }

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
              scansRefreshInterval = NonNegativeFiniteDuration.ofSeconds(1),
              enableInternalStore = Some(true),
            )
            c.copy(scanClient = dbEnabledConfig)
          case (_, c) => c
        }(config)
      )
      .withManualStart

  "validator onboarding and recovery succeed with internal config turned on" in { implicit env =>
    startAllSync(
      sv1Backend,
      sv1ScanBackend,
    )

    eventually() {
      val allHealthy = Seq(sv1ScanBackend).forall { scan =>
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
        withClue("Validator should bootstrap with only sv1 scan") {
          existsUrl(messages, "http://localhost:5012") &&
          !existsUrl(messages, "http://localhost:5112") &&
          !existsUrl(messages, "http://localhost:5212") &&
          !existsUrl(messages, "http://localhost:5312")
        } should be(true).withClue(s"Actual Logs: $logs")
      },
    )

    loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
      {
        startAllSync(
          sv2Backend,
          sv2ScanBackend,
          sv3Backend,
          sv3ScanBackend,
          sv4Backend,
          sv4ScanBackend,
        )
      },
      logs => {
        val messages = logs.map(_.message)
        withClue("validator should eventually refresh the scan list") {
          eventuallySucceeds() {
            messages.exists(_.contains(s"Updated scan list with 4 scans:"))
          }
        } should be(true).withClue(s"Actual Logs: $logs")
      },
    )

    loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
      {
        aliceValidatorBackend.stop()
        aliceValidatorBackend.startSync()
      },
      logs => {
        val messages = logs.map(_.message)
        withClue("Validator should bootstrap with all scans") {
          existsUrl(messages, "http://localhost:5012") &&
          existsUrl(messages, "http://localhost:5112") &&
          existsUrl(messages, "http://localhost:5212") &&
          existsUrl(messages, "http://localhost:5312")
        } should be(true).withClue(s"Actual Logs: $logs")
      },
    )

    withClue("Alice's validator should be able to onboard a user after establishing connections.") {
      eventuallySucceeds() {
        aliceValidatorBackend.onboardUser(aliceWalletClient.config.ledgerApiUser)
      }
    }

  }
}
