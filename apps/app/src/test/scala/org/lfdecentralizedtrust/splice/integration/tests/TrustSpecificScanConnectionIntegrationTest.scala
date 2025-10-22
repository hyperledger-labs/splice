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

        messages.exists(_.contains(s"Connection to trusted sv ${getSvName(1)} made.")) should be(
          true
        )
        messages.exists(_.contains(s"Connection to trusted sv ${getSvName(2)} made.")) should be(
          true
        )
        messages.exists(_.contains(s"Connection to trusted sv ${getSvName(3)} made.")) should be(
          true
        )

        messages.exists(_.contains(s"Connection to trusted sv ${getSvName(4)} made.")) should be(
          false
        )

        messages.exists(_.contains("created threshold number of scan connections.")) should be(true)
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
      sv2ScanBackend,
      sv2Backend,
      sv3ScanBackend,
      sv3Backend,
      sv4ScanBackend,
      sv4Backend,
    )

    sv2ScanBackend.stop()

    loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
      {
        aliceValidatorBackend.startSync()
      },
      logs => {
        val messages = logs.map(_.message)

        messages.exists(_.contains(s"Connection to trusted sv ${getSvName(1)} made")) should be(
          true
        )
        messages.exists(_.contains(s"Connection to trusted sv ${getSvName(3)} made")) should be(
          true
        )
        messages.exists(_.contains(s"Could not make connection to sv ${getSvName(2)}")) should be(
          true
        )

        messages.exists(_.contains(s"created threshold number of scan connections.")) should be(
          true
        )

        messages.exists(_.contains(s"Connection to trusted sv ${getSvName(4)} made.")) should be(
          false
        )
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
      sv2Backend, // sv2's main backend is running
      sv2ScanBackend,
      sv3ScanBackend,
      sv3Backend,
      sv4ScanBackend,
      sv4Backend,
    )

    sv2ScanBackend.stop()

    loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
      {
        aliceValidatorBackend.startSync()
      },
      logs => {
        val messages = logs.map(_.message)
        messages.exists(_.contains(s"Connection to trusted sv ${getSvName(1)} made.")) should be(
          true
        )
        messages.exists(_.contains(s"Connection to trusted sv ${getSvName(3)} made.")) should be(
          true
        )
        messages.exists(_.contains(s"Could not make connection to sv ${getSvName(2)}")) should be(
          true
        )
        messages.exists(_.contains("created threshold number of scan connections.")) should be(true)
      },
    )

    loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
      { sv2ScanBackend.startSync() },
      logs => {
        val messages = logs.map(_.message)
        messages.exists(
          _.contains("Refresh complete. Active connections: 3 / 3")
        ) should be(
          true
        )
      },
    )

    eventuallySucceeds() {
      aliceValidatorBackend.onboardUser(aliceWalletClient.config.ledgerApiUser)
    }
  }

//  "fails to initialize when below threshold" in { implicit env =>
//    startAllSync(
//      sv1ScanBackend,
//      sv1Backend,
//      sv2ScanBackend,
//      sv2Backend,
//      sv3ScanBackend,
//      sv3Backend,
//      sv4ScanBackend,
//      sv4Backend,
//    )
//
//    sv2ScanBackend.stop()
//    sv3ScanBackend.stop()
//
//
//    val exception = intercept[java.lang.RuntimeException] {
//      aliceValidatorBackend.startSync()
//    }
//
//    def findStatusRuntimeException(t: Throwable): Option[io.grpc.StatusRuntimeException] =
//      t match {
//        case sre: io.grpc.StatusRuntimeException => Some(sre)
//        case null => None
//        case _ => findStatusRuntimeException(t.getCause)
//      }
//
//    val statusRuntimeException =
//      findStatusRuntimeException(exception).valueOrFail("Could not find StatusRuntimeException in cause chain")
//
//    val description = statusRuntimeException.getStatus.getDescription
//    description should include("Failed to connect to required number of trusted scans.")
//  }

}
