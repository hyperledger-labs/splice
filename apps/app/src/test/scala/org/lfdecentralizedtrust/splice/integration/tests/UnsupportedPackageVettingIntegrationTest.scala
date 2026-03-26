// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.LfPackageId
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.daml.lf.data.Ref.{PackageName, PackageVersion}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.environment.DarResources.pkgIdToDarResource
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyTransactionType.AuthorizedState
import org.lfdecentralizedtrust.splice.environment.{
  DarResource,
  DarResources,
  ParticipantAdminConnection,
  RetryFor,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.{DarResourcesUtil, UploadablePackage}

abstract class UnsupportedPackageVettingIntegrationTestBase extends IntegrationTest {

  protected val extraPackagesToUnvet1: Seq[DarResource]

  protected val extraPackagesToUnvet2: Seq[DarResource] = Seq.empty

  private def supportedPackagesToUnvet(
      packages: Seq[DarResource]
  ): Map[PackageName, Set[PackageVersion]] =
    packages
      .groupBy(_.metadata.name)
      .map { case (name, resources) => name -> resources.map(_.metadata.version).toSet }

  protected def getVettedPackageIds(
      participantAdminConnection: ParticipantAdminConnection,
      synchronizerId: SynchronizerId,
  ): Seq[LfPackageId] = {
    val participantId = participantAdminConnection.getParticipantId().futureValue
    participantAdminConnection
      .listVettedPackages(participantId, synchronizerId, AuthorizedState)
      .futureValue
      .flatMap(_.mapping.packages)
      .map(_.packageId)
  }

  override def environmentDefinition: EnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllValidatorConfigs_(
          _.copy(
            additionalPackagesToUnvet = supportedPackagesToUnvet(extraPackagesToUnvet1)
          )
        )(config)
      )
      .addConfigTransforms((_, config) => {
        config.copy(
          svApps = config.svApps +
            (InstanceName.tryCreate("sv1") ->
              config
                .svApps(InstanceName.tryCreate(s"sv1"))
                .copy(
                  additionalPackagesToUnvet = supportedPackagesToUnvet(extraPackagesToUnvet1)
                )) +
            (InstanceName.tryCreate("sv1Local") ->
              config
                .svApps(InstanceName.tryCreate(s"sv1"))
                .copy(
                  additionalPackagesToUnvet = supportedPackagesToUnvet(extraPackagesToUnvet2)
                ))
        )
      })
}

class AllAdditionalPackagesToUnvetIntegrationTest
    extends UnsupportedPackageVettingIntegrationTestBase {

  private val svSupportedDars =
    DarResourcesUtil.supportedPackageVersions.filterNot(DarResources.splitwell.all.toSet)
  private val minimalSvSupporedDars =
    DarResourcesUtil.getMinimalSupportedPackageVersions.filterNot(DarResources.splitwell.all.toSet)

  override val extraPackagesToUnvet1: Seq[DarResource] =
    svSupportedDars.filterNot(minimalSvSupporedDars.toSet)

  override val extraPackagesToUnvet2: Seq[DarResource] = minimalSvSupporedDars

  override def environmentDefinition: EnvironmentDefinition =
    super.environmentDefinition.withManualStart

  "Unvetting all supported packages is prevented" in { implicit env =>
    sv1Backend.startSync()

    val synchronizerId =
      sv1Backend.participantClient.synchronizers.list_connected().head.synchronizerId

    clue("sv1 unvet all supported packages, but the minimal supported versions") {

      println(getVettedPackageIds(sv1Backend.appState.participantAdminConnection, synchronizerId))
      eventually() {
        println(
          minimalSvSupporedDars
            .map(_.packageId)
            .flatMap(pkgIdToDarResource.get)
            .map(e => s"${e.metadata.name}:${e.metadata.version}")
        )
        // println(getVettedPackageIds(sv1Backend.appState.participantAdminConnection, synchronizerId).flatMap(pkgIdToDarResource.get).map(e => s"${e.metadata.name}:${e.metadata.version}"))
        println(
          getVettedPackageIds(sv1Backend.appState.participantAdminConnection, synchronizerId)
            .filterNot(extraPackagesToUnvet1.map(_.packageId).toSet)
            .flatMap(pkgIdToDarResource.get)
            .map(e => s"${e.metadata.name}:${e.metadata.version}")
        )
        println(
          extraPackagesToUnvet1
            .map(_.packageId)
            .filterNot(
              getVettedPackageIds(
                sv1Backend.appState.participantAdminConnection,
                synchronizerId,
              ).toSet
            )
            .flatMap(pkgIdToDarResource.get)
            .map(e => s"${e.metadata.name}:${e.metadata.version}")
        )
        getVettedPackageIds(
          sv1Backend.appState.participantAdminConnection,
          synchronizerId,
        ) shouldBe minimalSvSupporedDars.map(_.packageId)
      }
      sv1Backend.stop()
    }
    clue("sv1 cannot vet supported package versions") {
      sv1LocalBackend.startSync()
      eventually() {
        getVettedPackageIds(
          sv1Backend.appState.participantAdminConnection,
          synchronizerId,
        ) should contain theSameElementsAs extraPackagesToUnvet1
      }
      sv1LocalBackend.stop()
    }
  }
}

class UnsupportedPackageVettingIntegrationTest
    extends UnsupportedPackageVettingIntegrationTestBase {

  override val extraPackagesToUnvet1: Seq[DarResource] = Seq(
    DarResources.wallet_0_1_15
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
        unsupportedDarsToVet ++ extraPackagesToUnvet1,
      )
      test(
        sv1ValidatorBackend.appState.participantAdminConnection,
        synchronizerId,
        unsupportedDarsToVet,
        unsupportedDarsToVet ++ extraPackagesToUnvet1,
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
        getVettedPackageIds(
          participantAdminConnection,
          synchronizerId,
        ) should contain allElementsOf unsupportedDarsToVet.map(_.packageId),
    )
    clue(s"the unsupported packages are then removed by the package vetting trigger from $name") {
      eventually() {
        getVettedPackageIds(
          participantAdminConnection,
          synchronizerId,
        ) should contain noElementsOf darsUnvettedByAutomation.map(_.packageId)
      }
    }
  }
}
