// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.daml.lf.data.Ref.{PackageName, PackageVersion}
import org.lfdecentralizedtrust.splice.environment.{DarResource, DarResources}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.{DarResourcesUtil, PackageUnvettingUtil}
import org.slf4j.event.Level

abstract class AdditionalPackagesToUnvetIntegrationTestBase
    extends IntegrationTest
    with PackageUnvettingUtil {

  protected val additionalPackagesToUnvetSv1: Seq[DarResource]
  protected val additionalPackagesToUnvetSv1Local: Seq[DarResource] = Seq.empty

  protected def supportedPackagesToUnvet(
      packages: Seq[DarResource]
  ): Map[PackageName, Set[PackageVersion]] =
    packages
      .groupBy(_.metadata.name)
      .map { case (name, resources) => name -> resources.map(_.metadata.version).toSet }

  override def environmentDefinition: EnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) => {
        config.copy(
          validatorApps = config.validatorApps +
            (InstanceName.tryCreate("sv1Validator") ->
              config
                .validatorApps(InstanceName.tryCreate(s"sv1Validator"))
                .copy(
                  additionalPackagesToUnvet = supportedPackagesToUnvet(additionalPackagesToUnvetSv1)
                )) +
            (InstanceName.tryCreate("sv1ValidatorLocal") ->
              config
                .validatorApps(InstanceName.tryCreate(s"sv1Validator"))
                .copy(
                  additionalPackagesToUnvet = supportedPackagesToUnvet(additionalPackagesToUnvetSv1Local)
                ))
        )
      })
      .addConfigTransforms((_, config) => {
        config.copy(
          svApps = config.svApps +
            (InstanceName.tryCreate("sv1") ->
              config
                .svApps(InstanceName.tryCreate(s"sv1"))
                .copy(
                  additionalPackagesToUnvet = supportedPackagesToUnvet(additionalPackagesToUnvetSv1)
                )) +
            (InstanceName.tryCreate("sv1Local") ->
              config
                .svApps(InstanceName.tryCreate(s"sv1"))
                .copy(
                  additionalPackagesToUnvet = supportedPackagesToUnvet(additionalPackagesToUnvetSv1Local)
                ))
        )
      })
      .withManualStart
}

/**
 * This test verifies that an SV cannot unvet packages that still have dependencies, but can unvet them if the dependencies are unvetted as well
 */
class DependentAdditionalPackagesToUnvetIntegrationTest
    extends AdditionalPackagesToUnvetIntegrationTestBase {

  private val missingDependency = DarResources.wallet_0_1_15
  private val problematicDar = DarResources.walletPayments_0_1_15
  private val darsWithMissingDependency = Seq(
    DarResources.wallet_0_1_16,
  ) :+ problematicDar

  override val additionalPackagesToUnvetSv1: Seq[DarResource] = darsWithMissingDependency
  override val additionalPackagesToUnvetSv1Local: Seq[DarResource] =
    darsWithMissingDependency :+ missingDependency

  "sv1 cannot unvet packages that still have dependencies" in { implicit env =>
    import env.executionContext

    startAllSync(
      sv1Backend,
      sv1ScanBackend,
      sv1ValidatorBackend,
    )
    val synchronizerId =
      sv1Backend.participantClient.synchronizers.list_connected().head.synchronizerId

    clue(s"sv1 cannot unvet a dar if a dependency to it remains, additionalPackagesToUnvet: ${additionalPackagesToUnvetSv1
        .map(pkg => pkg.metadata.name -> pkg.metadata.version)}") {
      loggerFactory.assertEventuallyLogsSeq(SuppressionRule.Level(Level.INFO))(
        within = {},
        lines =>
          forAtLeast(1, lines) { line =>
            line.message should include regex s"TOPOLOGY_DEPENDENCIES_NOT_VETTED.*${problematicDar.packageId}"
          },
      )
      eventually() {
        getVettedPackageIds(
          sv1Backend.appState.participantAdminConnection,
          synchronizerId,
        ) should contain(problematicDar.packageId)
      }
      stopAllAsync(
        sv1Backend,
        sv1ValidatorBackend,
      ).futureValue
    }

    clue(
      s"sv1 can unvet a dar if all dependency to it are unvetted as well, additionalPackagesToUnvet: ${additionalPackagesToUnvetSv1Local
          .map(pkg => pkg.metadata.name -> pkg.metadata.version)}"
    ) {
      startAllSync(
        sv1LocalBackend,
        sv1ValidatorLocalBackend,
      )
      eventually() {
        val vettedPackageIds = getVettedPackageIds(
          sv1LocalBackend.appState.participantAdminConnection,
          synchronizerId,
        )
        vettedPackageIds should contain noElementsOf darsWithMissingDependency :+ missingDependency
      }
      stopAllAsync(
        sv1LocalBackend,
        sv1ValidatorLocalBackend,
      ).futureValue
    }
  }
}

/**
 * This test verifies that an SV can unvet packages that were previously vetted as part of an upgrade
 */
class UpgradeAdditionalPackagesToUnvetIntegrationTest
    extends AdditionalPackagesToUnvetIntegrationTestBase {

  private val darsFromAnUpgrade = Seq(
    DarResources.dsoGovernance_0_1_23,
    DarResources.walletPayments_0_1_17,
    DarResources.wallet_0_1_18,
    DarResources.amuletNameService_0_1_18,
    DarResources.amulet_0_1_17,
  )

  override val additionalPackagesToUnvetSv1: Seq[DarResource] = darsFromAnUpgrade
  override val additionalPackagesToUnvetSv1Local: Seq[DarResource] = Seq(DarResources.wallet_0_1_15)

  "sv1 can unvet an upgrade and vet it again along new ignore packages" in { implicit env =>
    import env.executionContext

    startAllSync(
      sv1Backend,
      sv1ScanBackend,
      sv1ValidatorBackend,
    )
    val synchronizerId =
      sv1Backend.participantClient.synchronizers.list_connected().head.synchronizerId

    clue(s"sv1 does not vet packages from additionalPackagesToUnvet: ${additionalPackagesToUnvetSv1
        .map(pkg => pkg.metadata.name -> pkg.metadata.version)}") {
      eventually() {
        getVettedPackageIds(
          sv1Backend.appState.participantAdminConnection,
          synchronizerId,
        ) should contain noElementsOf darsFromAnUpgrade.map(_.packageId)
      }
      stopAllAsync(
        sv1Backend,
        sv1ValidatorBackend,
      ).futureValue
    }

    clue(
      s"sv1 restarts, vets the packages previously ignored and does not vet the packages from additionalPackagesToUnvet: ${additionalPackagesToUnvetSv1Local
          .map(pkg => pkg.metadata.name -> pkg.metadata.version)}"
    ) {
      startAllSync(
        sv1LocalBackend,
        sv1ValidatorLocalBackend,
      )
      eventually() {
        val vettedPackageIds = getVettedPackageIds(
          sv1LocalBackend.appState.participantAdminConnection,
          synchronizerId,
        )
        vettedPackageIds should contain allElementsOf darsFromAnUpgrade.map(_.packageId)
        vettedPackageIds should contain noElementsOf additionalPackagesToUnvetSv1Local.map(_.packageId)
      }
      stopAllAsync(
        sv1LocalBackend,
        sv1ValidatorLocalBackend,
      ).futureValue
    }
  }
}

/**
 * This test verifies that an SV can unvet packages that were previously vetted as part of an upgrade, even if they have missing dependencies
 */
class DowngradeWalletAdditionalPackagesToUnvetIntegrationTest
  extends AdditionalPackagesToUnvetIntegrationTestBase {

  private val darsWithMissingDependency = Seq(
    DarResources.wallet_0_1_16,
  )

  override val additionalPackagesToUnvetSv1: Seq[DarResource] = darsWithMissingDependency

  "sv1 can unvet wallet_0_1_16" in { implicit env =>
    import env.executionContext

    startAllSync(
      sv1Backend,
      sv1ScanBackend,
      sv1ValidatorBackend,
    )
    val synchronizerId =
      sv1Backend.participantClient.synchronizers.list_connected().head.synchronizerId

    clue(
      s"sv1 can unvet a dar if all dependency to it are unvetted as well, additionalPackagesToUnvet: ${additionalPackagesToUnvetSv1Local
        .map(pkg => pkg.metadata.name -> pkg.metadata.version)}"
    ) {
      eventually() {
        val vettedPackageIds = getVettedPackageIds(
          sv1Backend.appState.participantAdminConnection,
          synchronizerId,
        )
        vettedPackageIds should contain noElementsOf darsWithMissingDependency
      }
      stopAllAsync(
        sv1Backend,
        sv1ValidatorBackend,
      ).futureValue
    }
  }
}

/**
 * This test verifies that an SV can unvet all supported packages that are above the minimum initialization version,
 * but not the ones equal to the minimum initialization version,.
 */
class MinimumRequiredAdditionalPackagesToUnvetIntegrationTest
    extends AdditionalPackagesToUnvetIntegrationTestBase {

  private val minimalPackageVersions = DarResourcesUtil.minimalPackageVersions
  private val nonMinimalSupportedPackageVersions =
    DarResourcesUtil.supportedPackageVersions.filterNot(minimalPackageVersions.contains(_))

  override val additionalPackagesToUnvetSv1: Seq[DarResource] = nonMinimalSupportedPackageVersions
  override val additionalPackagesToUnvetSv1Local: Seq[DarResource] =
    nonMinimalSupportedPackageVersions ++ minimalPackageVersions

  "sv1 cannot unvet all vetted and supported packages" in { implicit env =>
    import env.executionContext

    startAllSync(
      sv1Backend,
      sv1ScanBackend,
      sv1ValidatorBackend,
    )
    val synchronizerId =
      sv1Backend.participantClient.synchronizers.list_connected().head.synchronizerId

    clue(s"sv1 succeeds to unvets all packages but the minimal supported ones: ${additionalPackagesToUnvetSv1
        .map(pkg => pkg.metadata.name -> pkg.metadata.version)}") {
      eventually() {
        val vettedPackageIds = getVettedPackageIds(
          sv1Backend.appState.participantAdminConnection,
          synchronizerId,
        )
        vettedPackageIds should contain noElementsOf nonMinimalSupportedPackageVersions.map(
          _.packageId
        )
      }
      stopAllAsync(
        sv1Backend,
        sv1ValidatorBackend,
      ).futureValue
    }

    clue(
      s"sv1 fails to unvet all supported packages: ${additionalPackagesToUnvetSv1Local
          .map(pkg => pkg.metadata.name -> pkg.metadata.version)}"
    ) {
      startAllSync(
        sv1LocalBackend,
        sv1ValidatorLocalBackend,
      )
      loggerFactory.assertEventuallyLogsSeq(SuppressionRule.Level(Level.INFO))(
        within = {},
        lines =>
          forAtLeast(1, lines) { line =>
            line.message should include regex s"Version .* of .* is smaller or equal to the minimum initialization version .* or larger than .*"
          },
      )
      eventually() {
        val vettedPackageIds = getVettedPackageIds(
          sv1LocalBackend.appState.participantAdminConnection,
          synchronizerId,
        )
        vettedPackageIds should contain noElementsOf nonMinimalSupportedPackageVersions.map(
          _.packageId
        )
      }
      stopAllAsync(
        sv1LocalBackend,
        sv1ValidatorLocalBackend,
      ).futureValue
    }
  }
}
