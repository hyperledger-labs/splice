// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.integration.tests

import com.digitalasset.daml.lf.data.Ref.{PackageName, PackageVersion}
import com.daml.network.config.ConfigTransforms
import com.daml.network.config.ConfigTransforms.{ConfigurableApp, updateAutomationConfig}
import com.daml.network.environment.{
  DarResources,
  EnvironmentImpl,
  PackageResource,
  ParticipantAdminConnection,
}
import com.daml.network.integration.EnvironmentDefinition
import com.daml.network.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import com.daml.network.sv.automation.singlesv.LocalSequencerConnectionsTrigger
import com.daml.network.sv.config.SvOnboardingConfig.InitialPackageConfig
import com.daml.network.util.{DarUtil, ProcessTestUtil, StandaloneCanton}
import com.digitalasset.canton.admin.participant.v30.DarDescription
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.crypto.Hash
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.google.protobuf.ByteString
import org.scalatest.time.{Minute, Span}

class BootstrapPackageConfigIntegrationTest
    extends IntegrationTest
    with ProcessTestUtil
    with StandaloneCanton {

  override def dbsSuffix = "bootstrapdso"

  // Runs against a temporary Canton instance.
  override lazy val resetRequiredTopologyState = false

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(1, Minute)))

  // These versions are from the release 0.1.17
  private val initialPackageConfig = InitialPackageConfig(
    amuletVersion = "0.1.4",
    amuletNameServiceVersion = "0.1.4",
    dsoGovernanceVersion = "0.1.6",
    validatorLifecycleVersion = "0.1.1",
    walletVersion = "0.1.4",
    walletPaymentsVersion = "0.1.4",
  )

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .withPreSetup(_ => ())
      .addConfigTransformsToFront(
        (_, conf) => ConfigTransforms.bumpCantonPortsBy(22_000)(conf),
        (_, conf) => ConfigTransforms.bumpCantonDomainPortsBy(22_000)(conf),
      )
      .addConfigTransformsToFront((_, conf) =>
        ConfigTransforms.bumpRemoteSplitwellPortsBy(22_000)(conf)
      )
      .withSequencerConnectionsFromScanDisabled(22_000)
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppFoundDsoConfigs_(
          _.copy(initialPackageConfig = initialPackageConfig)
        )(config)
      )
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
          _.withPausedTrigger[LocalSequencerConnectionsTrigger]
        )(config)
      )
      .addConfigTransform((_, conf) =>
        conf.copy(validatorApps =
          conf.validatorApps.updatedWith(InstanceName.tryCreate("aliceValidator")) {
            _.map { aliceValidatorConfig =>
              val withoutExtraDomains = aliceValidatorConfig.domains.copy(extra = Seq.empty)
              aliceValidatorConfig.copy(
                domains = withoutExtraDomains
              )
            }
          }
        )
      )
      .withCantonNodeNameSuffix("BootstrapDsoPackageConfig")
      .withManualStart

  "Bootstrap with specific versions" in { implicit env =>
    withCantonSvNodes(
      (
        Some(sv1Backend),
        Some(sv2Backend),
        Some(sv3Backend),
        Some(sv4Backend),
      ),
      "boostrap-dso-with-specific-package-config",
      extraParticipantsConfigFileName = Some("standalone-participant-extra.conf"),
      extraParticipantsEnvMap = Map(
        "EXTRA_PARTICIPANT_ADMIN_USER" -> aliceValidatorBackend.config.ledgerApiUser,
        "EXTRA_PARTICIPANT_DB" -> ("participant_extra_" + dbsSuffix),
      ),
    )() {
      clue("Initialize DSO with 4 SVs") {
        startAllSync(
          sv1ScanBackend,
          sv2ScanBackend,
          sv1Backend,
          sv2Backend,
          sv3Backend,
          sv4Backend,
          sv1ValidatorBackend,
          sv2ValidatorBackend,
          sv3ValidatorBackend,
          sv4ValidatorBackend,
          aliceValidatorBackend,
        )
      }

      Seq(
        sv1Backend.appState.participantAdminConnection,
        sv2Backend.appState.participantAdminConnection,
        sv3Backend.appState.participantAdminConnection,
        sv4Backend.appState.participantAdminConnection,
      ).foreach(
        checkDarVersions(
          Seq(
            DarResources.amulet -> initialPackageConfig.amuletVersion,
            DarResources.amuletNameService -> initialPackageConfig.amuletNameServiceVersion,
            DarResources.dsoGovernance -> initialPackageConfig.dsoGovernanceVersion,
            DarResources.validatorLifecycle -> initialPackageConfig.validatorLifecycleVersion,
            DarResources.wallet -> initialPackageConfig.walletVersion,
            DarResources.walletPayments -> initialPackageConfig.walletPaymentsVersion,
          ),
          _,
        )
      )

      checkDarVersions(
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
      darsToCheck: Seq[(PackageResource, String)],
      participantAdminConnection: ParticipantAdminConnection,
  ): Unit = {
    eventually() {
      val uploadedDarDescriptions: Seq[DarDescription] =
        participantAdminConnection.listDars().futureValue
      val uploadedDarNameAndVersions: Seq[(PackageName, PackageVersion)] =
        uploadedDarDescriptions.map { darDesc =>
          val darBytes: ByteString =
            participantAdminConnection
              .lookupDar(Hash.tryFromHexString(darDesc.hash))
              .futureValue
              .value
          val darMetadata = DarUtil.readDarMetadata(darDesc.name, darBytes.newInput())
          darMetadata.name -> darMetadata.version
        }
      darsToCheck.foreach { case (packageResource, upToVersion) =>
        withClue(
          s"${participantAdminConnection.getParticipantId().futureValue} should have all required dars"
        ) {
          checkDarVersionsUpTo(uploadedDarNameAndVersions, packageResource, upToVersion)
        }
      }
    }
  }

  private def checkDarVersionsUpTo(
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
      dars.foreach { case (_, version) =>
        version should be <= PackageVersion.assertFromString(
          requiredVersion
        )
      }
    }
  }
}
