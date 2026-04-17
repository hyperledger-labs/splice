// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.topology.SynchronizerId
import org.lfdecentralizedtrust.splice.automation.PackageVettingTrigger
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.{
  AmuletConfig,
  PackageConfig,
}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.environment.{
  DarResource,
  DarResources,
  ParticipantAdminConnection,
  RetryFor,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.SvPackageVettingTrigger
import org.lfdecentralizedtrust.splice.util.{
  AmuletConfigSchedule,
  AmuletConfigUtil,
  PackageUnvettingUtil,
  UploadablePackage,
  WalletTestUtil,
}
import org.lfdecentralizedtrust.splice.validator.automation.ValidatorPackageVettingTrigger
import org.scalatest.concurrent.PatienceConfiguration

import scala.concurrent.duration.FiniteDuration

class UnsupportedPackageVettingIntegrationTest
    extends IntegrationTest
    with PackageUnvettingUtil
    with AmuletConfigUtil
    with WalletTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
          _.withPausedTrigger[SvPackageVettingTrigger]
        )(config)
      )
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Validator)(
          _.withPausedTrigger[ValidatorPackageVettingTrigger]
        )(config)
      )

  "Unsupported vetted packages are automatically removed by the package vetting trigger for SV and validator" in {
    implicit env =>
      val unsupportedDarsToVetSv = Seq(
        DarResources.dsoGovernance_0_1_0,
        DarResources.walletPayments_0_1_0,
        DarResources.amuletNameService_0_1_0,
        DarResources.amulet_0_1_0,
      )
      // TODO(DACH-NY/cn-test-failures#8034) Consider vetting DSO governance again once the Canton issue is fixed
      val unsupportedDarsToVetValidator = Seq(
        DarResources.walletPayments_0_1_0,
        DarResources.amuletNameService_0_1_0,
        DarResources.amulet_0_1_0,
      )
      val synchronizerId =
        sv1Backend.participantClient.synchronizers.list_connected().head.synchronizerId
      test(
        sv1Backend.appState.participantAdminConnection,
        synchronizerId,
        unsupportedDarsToVetSv,
        unsupportedDarsToVetSv,
        sv1Backend.dsoAutomation.trigger[SvPackageVettingTrigger],
      )
      test(
        sv1ValidatorBackend.appState.participantAdminConnection,
        synchronizerId,
        unsupportedDarsToVetSv,
        unsupportedDarsToVetSv,
        sv1ValidatorBackend.validatorAutomation.trigger[ValidatorPackageVettingTrigger],
      )
      // See https://github.com/DACH-NY/canton/issues/29834: set darsUnvettedByAutomation when unvetting works on non-sv validators
      test(
        aliceValidatorBackend.appState.participantAdminConnection,
        synchronizerId,
        unsupportedDarsToVetValidator,
        Seq.empty,
        aliceValidatorBackend.validatorAutomation.trigger[ValidatorPackageVettingTrigger],
      )
  }

  private def test(
      participantAdminConnection: ParticipantAdminConnection,
      synchronizerId: SynchronizerId,
      unsupportedDarsToVet: Seq[DarResource],
      darsUnvettedByAutomation: Seq[DarResource],
      vettingTrigger: PackageVettingTrigger,
  ) = {
    val participantId = participantAdminConnection.getParticipantId().futureValue
    val name = participantId.uid.identifier
    actAndCheck(
      s"$name uploads and vets unsupported packages", {
        participantAdminConnection
          .uploadDarFiles(
            unsupportedDarsToVet.map(UploadablePackage.fromResource),
            RetryFor.Automation,
          )
          .futureValue
        participantAdminConnection
          .vetDars(synchronizerId, unsupportedDarsToVet, None, None)
          .futureValue(timeout = PatienceConfiguration.Timeout(FiniteDuration(40, "seconds")))
      },
    )(
      s"the unsupported packages are vetted on $name",
      _ =>
        getVettedPackageIds(
          participantAdminConnection,
          synchronizerId,
        ) should contain allElementsOf unsupportedDarsToVet.map(_.packageId),
    )
    clue(s"the unsupported packages are then removed by the package vetting trigger from $name") {
      vettingTrigger.resume()
      eventually() {
        getVettedPackageIds(
          participantAdminConnection,
          synchronizerId,
        ) should contain noElementsOf darsUnvettedByAutomation.map(_.packageId)
      }
    }
  }

  "SVs unvet package versions above the configured PackageConfig, validators do not" in {
    implicit env =>
      val synchronizerId =
        sv1Backend.participantClient.synchronizers.list_connected().head.synchronizerId

      val validatorDarsAbovePackageConfigVersion = Seq(
        DarResources.wallet_0_1_18,
        DarResources.walletPayments_0_1_17,
        DarResources.amuletNameService_0_1_18,
        DarResources.amulet_0_1_17,
      )
      val svDarsAbovePackageConfigVersion = Seq(
        DarResources.dsoGovernance_0_1_23
      ) ++ validatorDarsAbovePackageConfigVersion

      clue("sv1 votes to downgrade to the previous package versions") {
        val amuletRules = sv1ScanBackend.getAmuletRules()
        val currentConfig =
          AmuletConfigSchedule(amuletRules).getConfigAsOf(env.environment.clock.now)

        val downgradedPackageConfig = new PackageConfig(
          DarResources.amulet_0_1_16.metadata.version.toString(),
          DarResources.amuletNameService_0_1_17.metadata.version.toString(),
          DarResources.dsoGovernance_0_1_22.metadata.version.toString(),
          currentConfig.packageConfig.validatorLifecycle,
          DarResources.wallet_0_1_17.metadata.version.toString(),
          DarResources.walletPayments_0_1_16.metadata.version.toString(),
        )
        val newAmuletConfig = new AmuletConfig(
          currentConfig.transferConfig,
          currentConfig.issuanceCurve,
          currentConfig.decentralizedSynchronizer,
          currentConfig.tickDuration,
          downgradedPackageConfig,
          currentConfig.transferPreapprovalFee,
          currentConfig.featuredAppActivityMarkerAmount,
          currentConfig.optDevelopmentFundManager,
          currentConfig.externalPartyConfigStateTickDuration,
        )
        setAmuletConfig(Seq((None, newAmuletConfig, currentConfig)))
      }

      clue("sv1 unvets package versions above the downgraded PackageConfig") {
        eventually() {
          getVettedPackageIds(
            sv1Backend.appState.participantAdminConnection,
            synchronizerId,
          ) should contain noElementsOf svDarsAbovePackageConfigVersion.map(_.packageId)
        }
      }

      clue("sv1 validator unvets package versions above the downgraded PackageConfig") {
        eventually() {
          getVettedPackageIds(
            sv1ValidatorBackend.appState.participantAdminConnection,
            synchronizerId,
          ) should contain noElementsOf validatorDarsAbovePackageConfigVersion.map(_.packageId)
        }
      }

      clue("alice validator keeps package versions above the downgraded PackageConfig vetted") {
        eventually() {
          getVettedPackageIds(
            aliceValidatorBackend.appState.participantAdminConnection,
            synchronizerId,
          ) should contain allElementsOf validatorDarsAbovePackageConfigVersion.map(_.packageId)
        }
        alicesTapsWithPackageId(DarResources.amulet_0_1_16.packageId)
      }
  }
}
