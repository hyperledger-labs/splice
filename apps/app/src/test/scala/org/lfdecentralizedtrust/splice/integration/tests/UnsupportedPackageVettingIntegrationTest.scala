// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.daml.lf.data.Ref.{PackageName, PackageVersion}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyTransactionType.AuthorizedState
import org.lfdecentralizedtrust.splice.environment.{
  DarResource,
  DarResources,
  ParticipantAdminConnection,
  RetryFor,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.UploadablePackage

class UnsupportedPackageVettingIntegrationTest extends IntegrationTest {

  private val extraPackagesToUnvet: Seq[DarResource] = Seq(
    DarResources.wallet_0_1_15
  )

  private val supportedPackagesToUnvet: Map[PackageName, Set[PackageVersion]] =
    extraPackagesToUnvet
      .groupBy(_.metadata.name)
      .map { case (name, resources) => name -> resources.map(_.metadata.version).toSet }

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppConfigs_(
          _.copy(
            additionalPackagesToUnvet = supportedPackagesToUnvet
          )
        )(config)
      )
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllValidatorConfigs_(
          _.copy(
            additionalPackagesToUnvet = supportedPackagesToUnvet
          )
        )(config)
      )

  "Unsupported vetted packages are automatically removed by the package vetting trigger for SV and validator" in {
    implicit env =>
      val unsupportedDarsToVet = Seq(
        DarResources.dsoGovernance_0_1_0,
        DarResources.walletPayments_0_1_0,
        DarResources.amuletNameService_0_1_0,
        DarResources.amulet_0_1_0,
      )
      val synchronizerId =
        sv1Backend.participantClient.synchronizers.list_connected().head.synchronizerId
      test(
        sv1Backend.appState.participantAdminConnection,
        synchronizerId,
        unsupportedDarsToVet,
        unsupportedDarsToVet ++ extraPackagesToUnvet,
      )
      test(
        sv1ValidatorBackend.appState.participantAdminConnection,
        synchronizerId,
        unsupportedDarsToVet,
        unsupportedDarsToVet ++ extraPackagesToUnvet,
      )
      // See https://github.com/DACH-NY/canton/issues/29834: set darsUnvettedByAutomation when unvetting works on non-sv validators
      test(
        aliceValidatorBackend.appState.participantAdminConnection,
        synchronizerId,
        unsupportedDarsToVet,
        Seq.empty,
      )
  }

  private def test(
      participantAdminConnection: ParticipantAdminConnection,
      synchronizerId: SynchronizerId,
      unsupportedDarsToVet: Seq[DarResource],
      darsUnvettedByAutomation: Seq[DarResource],
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
          .futureValue
      },
    )(
      s"the unsupported packages are vetted on $name",
      _ =>
        participantAdminConnection
          .listVettedPackages(participantId, synchronizerId, AuthorizedState)
          .futureValue
          .flatMap(_.mapping.packages)
          .map(_.packageId) should contain allElementsOf unsupportedDarsToVet.map(_.packageId),
    )
    clue(s"the unsupported packages are then removed by the package vetting trigger from $name") {
      eventually() {
        participantAdminConnection
          .listVettedPackages(participantId, synchronizerId, AuthorizedState)
          .futureValue
          .flatMap(_.mapping.packages)
          .map(_.packageId) should contain noElementsOf darsUnvettedByAutomation.map(_.packageId)
      }
    }
  }
}
