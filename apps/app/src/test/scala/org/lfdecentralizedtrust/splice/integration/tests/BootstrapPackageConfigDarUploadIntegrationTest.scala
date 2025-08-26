// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.topology.transaction.VettedPackage
import com.digitalasset.daml.lf.data.Ref.{PackageName, PackageVersion}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyTransactionType.AuthorizedState
import org.lfdecentralizedtrust.splice.environment.{
  DarResources,
  PackageResource,
  ParticipantAdminConnection,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingConfig.InitialPackageConfig
import org.lfdecentralizedtrust.splice.util.{ProcessTestUtil, StandaloneCanton}
import org.scalatest.time.{Minute, Span}

class BootstrapPackageConfigDarUploadIntegrationTest
    extends IntegrationTest
    with ProcessTestUtil
    with StandaloneCanton {

  override def dbsSuffix = "bootstrappackageconfigdarupload"

  // Runs against a temporary Canton instance.
  override lazy val resetRequiredTopologyState = false

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(1, Minute)))

  private val initialPackageConfig = InitialPackageConfig.minimumInitialPackageConfig

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      // Technically a single SV test but withCanton doesn't handle that atm.
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .withPreSetup(_ => ())
      .addConfigTransformsToFront((_, conf) => ConfigTransforms.bumpCantonPortsBy(22_000)(conf))
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppFoundDsoConfigs_(
          _.copy(initialPackageConfig = initialPackageConfig)
        )(config)
      )
      .addConfigTransform((_, conf) =>
        conf.copy(validatorApps =
          conf.validatorApps.updatedWith(InstanceName.tryCreate("aliceValidator")) {
            _.map { aliceValidatorConfig =>
              val withoutExtraSynchronizers = aliceValidatorConfig.domains.copy(extra = Seq.empty)
              aliceValidatorConfig.copy(
                domains = withoutExtraSynchronizers
              )
            }
          }
        )
      )
      .withCantonNodeNameSuffix("BootstrapDsoPackageConfig")
      .withManualStart

  "Bootstrap with specific versions and check that no newer DARs have been uploaded" in {
    implicit env =>
      withCantonSvNodes(
        (
          Some(sv1Backend),
          Some(sv2Backend),
          Some(sv3Backend),
          None,
        ),
        "boostrap-package-config-dar-upload",
        sv4 = false,
        extraParticipantsConfigFileNames =
          Seq("standalone-participant-extra.conf", "standalone-participant-extra-no-auth.conf"),
        extraParticipantsEnvMap = Map(
          "EXTRA_PARTICIPANT_ADMIN_USER" -> aliceValidatorBackend.config.ledgerApiUser,
          "EXTRA_PARTICIPANT_DB" -> ("participant_extra_" + dbsSuffix),
        ),
      )() {
        clue("Initialize sv1 and alice validator") {
          startAllSync(
            sv1ScanBackend,
            sv1Backend,
            sv1ValidatorBackend,
            aliceValidatorBackend,
          )
        }
        checkDarVersions(
          decentralizedSynchronizerId,
          Seq(
            DarResources.amulet -> initialPackageConfig.amuletVersion,
            DarResources.amuletNameService -> initialPackageConfig.amuletNameServiceVersion,
            DarResources.dsoGovernance -> initialPackageConfig.dsoGovernanceVersion,
            DarResources.validatorLifecycle -> initialPackageConfig.validatorLifecycleVersion,
            DarResources.wallet -> initialPackageConfig.walletVersion,
            DarResources.walletPayments -> initialPackageConfig.walletPaymentsVersion,
          ),
          sv1Backend.appState.participantAdminConnection,
        )
        checkDarVersions(
          decentralizedSynchronizerId,
          Seq(
            DarResources.amulet -> initialPackageConfig.amuletVersion,
            DarResources.amuletNameService -> initialPackageConfig.amuletNameServiceVersion,
            DarResources.validatorLifecycle -> initialPackageConfig.validatorLifecycleVersion,
            DarResources.wallet -> initialPackageConfig.walletVersion,
            DarResources.walletPayments -> initialPackageConfig.walletPaymentsVersion,
          ),
          aliceValidatorBackend.appState.participantAdminConnection,
        )
      }
  }

  private def checkDarVersions(
      domainId: SynchronizerId,
      darsToCheck: Seq[(PackageResource, String)],
      participantAdminConnection: ParticipantAdminConnection,
  ): Unit = {
    eventually() {
      val vettedPackages: Seq[VettedPackage] =
        participantAdminConnection
          .getVettingState(domainId, AuthorizedState)
          .futureValue
          .mapping
          .packages
      val uploadedPackages =
        participantAdminConnection
          .listDars()
          .futureValue
          .map(dar => dar.name -> PackageVersion.assertFromString(dar.version))
          .filter { case (name, _) =>
            DarResources.packageResources.map(_.bootstrap.metadata.name).contains(name)
          }
      val vettedDarNameAndVersions: Seq[(PackageName, PackageVersion)] = {
        vettedPackages
          .flatMap { darDesc =>
            DarResources.lookupPackageId(darDesc.packageId)
          }
          .map(dar => dar.metadata.name -> dar.metadata.version)
      }
      uploadedPackages.diff(vettedDarNameAndVersions) should have size 0
      vettedDarNameAndVersions.diff(uploadedPackages) should have size 0
      darsToCheck.foreach { case (packageResource, upToVersion) =>
        withClue(
          s"${participantAdminConnection.getParticipantId().futureValue} should have all required dars"
        ) {
          checkDarLatestVersion(vettedDarNameAndVersions, packageResource, upToVersion)
        }
      }
    }
  }

  private def checkDarLatestVersion(
      uploadedDars: Seq[(PackageName, PackageVersion)],
      packageResource: PackageResource,
      requiredVersion: String,
  ): Unit = {
    withClue(
      s"dars for package ${packageResource.bootstrap.metadata.name} should be up to $requiredVersion"
    ) {
      val dars =
        uploadedDars.filter { case (name, _) =>
          name == packageResource.bootstrap.metadata.name
        }
      dars should not be empty
      dars.map(_._2).max shouldBe PackageVersion.assertFromString(requiredVersion)
    }
  }
}
