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
  override protected def runUpdateHistorySanityCheck: Boolean = false

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllValidatorConfigs {
          case (name, c) if name == "aliceValidator" =>
            val bftCustomConfig = BftScanClientConfig.BftCustom(
              seedUrls = NonEmptyList.of(
                Uri("http://127.0.0.1:5012"),
                Uri("http://127.0.0.1:5112"),
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
        withClue("Validator should connect to sv1:") {
          connectionEstablished(1, messages)
        }
        withClue("Validator should connect to sv2:") {
          connectionEstablished(2, messages)
        }
        withClue("Validator should connect to sv3:") {
          connectionEstablished(3, messages)
        }
        withClue("Validator should NOT connect to sv4:") {
          connectionNotEstablished(4, messages)
        }
      },
    )

    withClue("Alice's validator should be able to onboard a user after establishing connections.") {
      eventuallySucceeds() {
        aliceValidatorBackend.onboardUser(aliceWalletClient.config.ledgerApiUser)
      }
    }
  }

  "reconnects to a recovered SV after the refresh interval" in { implicit env =>
    startAllSync(sv1Backend, sv1ScanBackend, sv2Backend, sv2ScanBackend, sv4Backend, sv4ScanBackend)

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
        withClue("Validator should connect to sv1:") {
          connectionEstablished(1, messages)
        }
        withClue("Validator should connect to sv2:") {
          connectionEstablished(2, messages)
        }
        withClue("Validator should not connect to the offline sv3:") {
          connectionNotEstablished(3, messages)
        }
        withClue("Validator should not connect to sv4") {
          connectionNotEstablished(4, messages)
        }
      },
    )

    loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
      {
        sv3Backend.startSync()
        sv3ScanBackend.startSync()
      },
      logs => {
        val messages = logs.map(_.message)
        withClue("Validator should detect and connect to the newly started sv3 after a refresh:") {
          refreshConnectionEstablished(3, messages)
        }
      },
    )

    withClue(
      "The validator should successfully onboard a user after connecting to the recovered scan."
    ) {
      eventuallySucceeds() {
        aliceValidatorBackend.onboardUser(aliceWalletClient.config.ledgerApiUser)
      }
    }
  }

  "validator crashes when the number of connected scans drops below the threshold" in {
    implicit env =>
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
        val allHealthy =
          Seq(sv1ScanBackend, sv2ScanBackend, sv3ScanBackend, sv4ScanBackend).forall { scan =>
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
          withClue("Validator should connect to sv1:") {
            connectionEstablished(1, messages)
          }
          withClue("Validator should connect to sv2:") {
            connectionEstablished(2, messages)
          }
          withClue("Validator should connect to sv3:") {
            connectionEstablished(3, messages)
          }
          withClue("Validator should NOT connect to sv4:") {
            connectionNotEstablished(4, messages)
          }
        },
      )

      loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
        {
          sv1ScanBackend.stop()
          sv2ScanBackend.stop()
        },
        logs => {
          val messages = logs.map(_.message)
          withClue(
            "Validator should fail to reach consensus when trusted scans drop below the threshold:"
          ) {
            messages.exists(
              _.contains(
                "Consensus not reached"
              )
            ) should be(true)
          }
        },
      )

      aliceValidatorBackend.stop()

  }
}
