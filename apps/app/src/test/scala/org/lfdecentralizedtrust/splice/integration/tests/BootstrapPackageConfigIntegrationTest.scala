// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.topology.transaction.VettedPackage
import com.digitalasset.daml.lf.data.Ref.{PackageName, PackageVersion}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.Amulet
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.{
  AmuletConfig,
  PackageConfig,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules_AddFutureAmuletConfigSchedule
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.amuletrules_actionrequiringconfirmation.CRARC_AddFutureAmuletConfigSchedule
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.environment.{
  DarResources,
  PackageResource,
  ParticipantAdminConnection,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.LocalSequencerConnectionsTrigger
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingConfig.InitialPackageConfig
import org.lfdecentralizedtrust.splice.util.{ProcessTestUtil, StandaloneCanton}
import org.scalatest.time.{Minute, Span}

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.jdk.CollectionConverters.*

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

  override def environmentDefinition: SpliceEnvironmentDefinition =
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
        ConfigTransforms.updateAllValidatorAppConfigs_(c =>
          // Reduce the cache TTL. Otherwise alice validator takes forever to see the new amulet rules version
          c.copy(scanClient =
            c.scanClient.setAmuletRulesCacheTimeToLive(NonNegativeFiniteDuration.ofSeconds(1))
          )
        )(conf)
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

  "Bootstrap with specific versions and then upgrade to latest" in { implicit env =>
    withCantonSvNodes(
      (
        Some(sv1Backend),
        Some(sv2Backend),
        Some(sv3Backend),
        Some(sv4Backend),
      ),
      "boostrap-dso-with-specific-package-config",
      extraParticipantsConfigFileNames =
        Seq("standalone-participant-extra.conf", "standalone-participant-extra-no-auth.conf"),
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
          decentralizedSynchronizerId,
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

      clue("alice taps amulet with initial package") {
        val tapContractId = aliceValidatorWalletClient.tap(10)
        aliceValidatorBackend.participantClient.ledger_api_extensions.acs
          .of_party(Amulet.COMPANION)(dsoParty)
          .filter(_.contractId == tapContractId.contractId)
          .loneElement
          .getTemplateId
          .packageId shouldBe DarResources.amulet
          .getPackageIdWithVersion(
            initialPackageConfig.amuletVersion
          )
          .value
      }

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

      assertThrowsAndLogsCommandFailures(
        sv1ScanBackend.getExternalPartyAmuletRules(),
        _.errorMessage should include("Not Found"),
      )

      clue("Change AmuletConfig to latest packages") {
        // 12s picked empirically to be far enough in the future that the voting can go through before that date.
        val scheduledTime = Instant.now().plus(12, ChronoUnit.SECONDS)
        val amuletRules = sv2ScanBackend.getAmuletRules()
        val amuletConfig = amuletRules.payload.configSchedule.initialValue
        val newAmuletConfig = new AmuletConfig(
          amuletConfig.transferConfig,
          amuletConfig.issuanceCurve,
          amuletConfig.decentralizedSynchronizer,
          amuletConfig.tickDuration,
          new PackageConfig(
            DarResources.amulet.bootstrap.metadata.version.toString(),
            DarResources.amuletNameService.bootstrap.metadata.version.toString(),
            DarResources.dsoGovernance.bootstrap.metadata.version.toString(),
            DarResources.validatorLifecycle.bootstrap.metadata.version.toString(),
            DarResources.wallet.bootstrap.metadata.version.toString(),
            DarResources.walletPayments.bootstrap.metadata.version.toString(),
          ),
          java.util.Optional.empty(),
          java.util.Optional.empty(),
        )

        val upgradeAction = new ARC_AmuletRules(
          new CRARC_AddFutureAmuletConfigSchedule(
            new AmuletRules_AddFutureAmuletConfigSchedule(
              new org.lfdecentralizedtrust.splice.codegen.java.da.types.Tuple2(
                scheduledTime,
                newAmuletConfig,
              )
            )
          )
        )

        actAndCheck(
          "Voting on a AmuletRules config change for upgraded packages", {
            val (_, voteRequest) = actAndCheck(
              "Creating vote request",
              eventuallySucceeds() {
                sv1Backend.createVoteRequest(
                  sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
                  upgradeAction,
                  "url",
                  "description",
                  sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
                )
              },
            )("vote request has been created", _ => sv1Backend.listVoteRequests().loneElement)

            clue(s"sv1-3 accept") {
              Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend).map(sv =>
                eventuallySucceeds() {
                  sv.castVote(
                    voteRequest.contractId,
                    isAccepted = true,
                    "url",
                    "description",
                  )
                }
              )
            }
          },
        )(
          "observing AmuletRules with upgraded config",
          _ => {
            val newAmuletRules = sv1Backend.getDsoInfo().amuletRules
            val configs =
              newAmuletRules.payload.configSchedule.futureValues.asScala.toList.map(_._2)
            forExactly(1, configs) { config =>
              config.packageConfig.amulet shouldBe DarResources.amulet.bootstrap.metadata.version
                .toString()
            }
          },
        )

        // Ensure that the code below really uses the new version. Locally things can be sufficiently
        // fast that you otherwise still end up using the old version.
        env.environment.clock
          .scheduleAt(
            _ => (),
            CantonTimestamp.assertFromInstant(scheduledTime.plus(500, ChronoUnit.MILLIS)),
          )
          .unwrap
          .futureValue

        clue("vetting topology is updated to the new config") {
          eventuallySucceeds() {
            Seq(
              sv1Backend.participantClient,
              sv2Backend.participantClient,
              sv3Backend.participantClient,
              sv4Backend.participantClient,
              aliceValidatorBackend.participantClient,
            ).foreach { participantClient =>
              clue(s"Vetting state for ${participantClient.id}") {
                val vettingTopologyState = participantClient.topology.vetted_packages.list(
                  store = Some(
                    TopologyStoreId.Synchronizer(
                      decentralizedSynchronizerId
                    )
                  ),
                  filterParticipant = participantClient.id.filterString,
                )
                val newAmuletVettedPackage = vettingTopologyState.loneElement.item.packages
                  .find(_.packageId == DarResources.amulet.bootstrap.packageId)
                  .value
                newAmuletVettedPackage.validFrom.value shouldBe CantonTimestamp.assertFromInstant(
                  scheduledTime
                )
              }
            }
          }
        }
      }

      clue("alice taps amulet with new package") {
        val tapContractId = aliceValidatorWalletClient.tap(10)
        aliceValidatorBackend.participantClient.ledger_api_extensions.acs
          .of_party(Amulet.COMPANION)(dsoParty)
          .filter(_.contractId == tapContractId.contractId)
          .loneElement
          .getTemplateId
          .packageId shouldBe DarResources.amulet.bootstrap.packageId
      }

      clue("ExternalPartyAmuletRules gets created") {
        eventuallySucceeds() {
          sv1ScanBackend.getExternalPartyAmuletRules()
        }
      }
    }
  }

  private def checkDarVersions(
      domainId: SynchronizerId,
      darsToCheck: Seq[(PackageResource, String)],
      participantAdminConnection: ParticipantAdminConnection,
  ): Unit = {
    eventually() {
      val vettedPackages: Seq[VettedPackage] =
        participantAdminConnection.getVettingState(domainId).futureValue.mapping.packages
      val uploadedDarNameAndVersions: Seq[(PackageName, PackageVersion)] = {
        vettedPackages
          .flatMap { darDesc =>
            DarResources.lookupPackageId(darDesc.packageId)
          }
          .map(dar => dar.metadata.name -> dar.metadata.version)
      }
      darsToCheck.foreach { case (packageResource, upToVersion) =>
        withClue(
          s"${participantAdminConnection.getParticipantId().futureValue} should have all required dars"
        ) {
          checkDarLatestVersion(uploadedDarNameAndVersions, packageResource, upToVersion)
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
