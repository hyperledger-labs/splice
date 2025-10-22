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
              trusted_svs = NonEmptyList.of("sv1Scan", "sv2Scan", "sv3Scan"),
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

  "starting the network" in { implicit env =>
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

    aliceValidatorBackend.startSync()
//    aliceValidatorBackend.onboardUser(aliceWalletClient.config.ledgerApiUser)
  }

}
