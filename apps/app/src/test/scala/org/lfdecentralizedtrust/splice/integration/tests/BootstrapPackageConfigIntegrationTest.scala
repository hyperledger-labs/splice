// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.daml.lf.data.Ref.PackageVersion
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.{
  AmuletConfig,
  PackageConfig,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules_SetConfig
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.amuletrules_actionrequiringconfirmation.CRARC_SetConfig
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.Amulet
import org.lfdecentralizedtrust.splice.codegen.java.splice.splitwell.balanceupdatetype
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.payment as walletCodegen
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.console.{
  ParticipantClientReference,
  SplitwellAppClientReference,
  WalletAppClientReference,
}
import org.lfdecentralizedtrust.splice.environment.{DarResources, PackageIdResolver}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.plugins.TokenStandardCliSanityCheckPlugin
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.splitwell.admin.api.client.commands.HttpSplitwellAppClient
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.SvPackageVettingTrigger
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingConfig.InitialPackageConfig
import org.lfdecentralizedtrust.splice.util.{ProcessTestUtil, SplitwellTestUtil, StandaloneCanton}
import org.lfdecentralizedtrust.splice.validator.automation.ValidatorPackageVettingTrigger
import org.scalatest.time.{Minute, Span}

import java.time.Instant
import java.time.temporal.ChronoUnit

@org.lfdecentralizedtrust.splice.util.scalatesttags.NoDamlCompatibilityCheck
class BootstrapPackageConfigIntegrationTest
    extends IntegrationTest
    with ProcessTestUtil
    with SplitwellTestUtil
    with StandaloneCanton {

  // this test starts up on older version (see initialPackageConfig), which don't define token-standard interfaces
  // and thus everything will show up as raw create/archives.
  override protected lazy val tokenStandardCliBehavior
      : TokenStandardCliSanityCheckPlugin.OutputCreateArchiveBehavior =
    TokenStandardCliSanityCheckPlugin.OutputCreateArchiveBehavior.IgnoreAll

  override def dbsSuffix = "bootstrapdso"

  // Runs against a temporary Canton instance.
  override lazy val resetRequiredTopologyState = false

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(1, Minute)))

  private val initialPackageConfig = InitialPackageConfig(
    amuletVersion = "0.1.8",
    amuletNameServiceVersion = "0.1.8",
    dsoGovernanceVersion = "0.1.11",
    validatorLifecycleVersion = "0.1.2",
    walletVersion = "0.1.8",
    walletPaymentsVersion = "0.1.8",
  )

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .withNoVettedPackages(implicit env => env.validators.local.map(_.participantClient))
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppFoundDsoConfigs_(
          _.copy(initialPackageConfig = initialPackageConfig)
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
      .addConfigTransform((_, config) =>
        ConfigTransforms.useDecentralizedSynchronizerSplitwell()(config)
      )
      .withSequencerConnectionsFromScanDisabled() // The direct ledger API submissions for splitwell interact poorly with domain disconnects

  private def splitwellPaymentRequest(
      senderSplitwell: SplitwellAppClientReference,
      senderWallet: WalletAppClientReference,
      key: HttpSplitwellAppClient.GroupKey,
      receiver: PartyId,
      amount: BigDecimal,
  )(implicit env: SpliceTestConsoleEnvironment) = {
    val sender = senderWallet.userStatus().party
    val (paymentRequestCid, _) =
      actAndCheck(
        s"$sender initiates transfer",
        senderSplitwell.initiateTransfer(
          key,
          Seq(
            new walletCodegen.ReceiverAmuletAmount(
              receiver.toProtoPrimitive,
              amount.setScale(10).bigDecimal,
            )
          ),
        ),
      )(
        s"$sender sees payment request",
        cid => {
          forExactly(1, senderWallet.listAppPaymentRequests()) { request =>
            request.contractId shouldBe cid
          }
        },
      )

    actAndCheck(
      s"$sender initiates payment accept request on global domain",
      senderWallet.acceptAppPaymentRequest(paymentRequestCid),
    )(
      s"$sender sees balance update",
      _ => {
        forExactly(1, aliceSplitwellClient.listBalanceUpdates(key)) { update =>
          update.payload.update shouldBe new balanceupdatetype.Transfer(
            sender,
            receiver.toProtoPrimitive,
            amount.setScale(10).bigDecimal,
          )
        }
      },
    )
  }

  "Bootstrap with specific versions and then upgrade to latest" in { implicit env =>
    val initialAmuletPackageId = DarResources.amulet
      .getPackageIdWithVersion(
        initialPackageConfig.amuletVersion
      )
      .value

    clue("alice taps amulet with initial package") {
      alicesTapsWithPackageId(initialAmuletPackageId)
    }

    clue("Upload all splitwell versions") {
      // This simulates an app vetting newer versions of their own DARs depending on newer splice-amulet versions
      // before the SVs do so. Topology aware package selection will then force the old splice-amulet and old splitwell versions
      // for composed transactions. Note that for this to work splitwell contracts must be downgradeable.
      Seq(aliceValidatorBackend, bobValidatorBackend, splitwellValidatorBackend).foreach { p =>
        p.participantClient.dars.upload_many(
          DarResources.splitwell.all
            .map(_.metadata.version)
            .distinct
            .map((v: PackageVersion) => s"daml/dars/splitwell-$v.dar")
        )
      }
    }

    val (_, bobUserParty, _, _, key, _) = initSplitwellTest()

    aliceWalletClient.tap(50)

    clue("Splitwell can complete payment request on old DAR versions") {
      splitwellPaymentRequest(aliceSplitwellClient, aliceWalletClient, key, bobUserParty, 42.0)
    }

    val sv2PackageVettingTrigger =
      sv2Backend.appState.dsoAutomation.trigger[SvPackageVettingTrigger]
    val sv2ValidatorPackageVettingTrigger =
      sv2ValidatorBackend.appState.automation.trigger[ValidatorPackageVettingTrigger]

    // 20s picked empirically to be far enough in the future that the voting can go through before that date.
    // it must also leave enough time for the dars to be uploaded and vetting to happen to prevent command failures
    val scheduledTime = Instant.now().plus(20, ChronoUnit.SECONDS)
    sv2PackageVettingTrigger.pause().futureValue
    sv2ValidatorPackageVettingTrigger.pause().futureValue

    val vettingScheduledTime = CantonTimestamp.assertFromInstant(
      scheduledTime
    )
    clue("Change AmuletConfig to latest packages") {
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
        new CRARC_SetConfig(
          new AmuletRules_SetConfig(
            newAmuletConfig,
            amuletConfig,
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
                new RelTime(19_000),
                Some(scheduledTime),
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

          newAmuletRules.payload.configSchedule.initialValue.packageConfig.amulet shouldBe DarResources.amulet.bootstrap.metadata.version
            .toString()
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

      clue("vetting topology is updated to the new config for nodes with enabled vetting trigger") {
        Seq(
          (sv1Backend.participantClient, Some(vettingScheduledTime)),
          (sv3Backend.participantClient, Some(vettingScheduledTime)),
          (sv4Backend.participantClient, Some(vettingScheduledTime)),
          (
            aliceValidatorBackend.participantClient,
            None,
          ), // due to the early splitwell dar upload this is vetted without a timestamp
        ).foreach { case (participantClient, scheduledTimeO) =>
          clue(s"Vetting state for ${participantClient.id}") {
            eventually() {
              vettingIsUpdatedForTheNewConfig(participantClient, scheduledTimeO)
            }
          }
        }
      }
    }

    clue("alice taps amulet with old package because sv2 hasn't vetted yet") {
      alicesTapsWithPackageId(initialAmuletPackageId)
    }

    def amuletRulesCreateTimestamp = {
      CantonTimestamp.tryFromInstant(sv2ScanBackend.getAmuletRules().contract.createdAt)
    }

    val amuletCreateTimeWhenVettingResumed =
      amuletRulesCreateTimestamp
    sv2PackageVettingTrigger.resume()
    sv2ValidatorPackageVettingTrigger.resume()

    clue(s"Vetting state for slow sv is updated after the trigger runs") {
      eventually() {
        vettingIsUpdatedForTheNewConfig(
          sv2Backend.participantClient,
          Some(
            vettingScheduledTime
          ),
          Some(amuletCreateTimeWhenVettingResumed),
          // it depends if the amulet rules contract was pruned or not by the time vetting ran
          // there's a chance that amulet rules changed after vetting resumed (eg: pruning)
          Some(amuletRulesCreateTimestamp),
        )
      }
    }

    clue("alice taps amulet with new package after all the svs vet the new packages") {
      alicesTapsWithPackageId(DarResources.amulet.bootstrap.packageId)
    }

    clue("ExternalPartyAmuletRules gets created") {
      eventuallySucceeds() {
        sv1ScanBackend.getExternalPartyAmuletRules()
      }
    }

    clue("Splitwell can complete payment request on new DAR versions") {
      splitwellPaymentRequest(aliceSplitwellClient, aliceWalletClient, key, bobUserParty, 23.0)
    }
  }

  private def vettingIsUpdatedForTheNewConfig(
      participantClient: ParticipantClientReference,
      scheduledTimeO: Option[CantonTimestamp],
      scheduledTime1: Option[CantonTimestamp] = None,
      scheduledTime2: Option[CantonTimestamp] = None,
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    val vettingTopologyState = participantClient.topology.vetted_packages.list(
      store = Some(
        TopologyStoreId.Synchronizer(
          decentralizedSynchronizerId
        )
      ),
      filterParticipant = participantClient.id.filterString,
    )
    val vettingState = vettingTopologyState.loneElement.item
    val amuletPackageName = DarResources.amulet.bootstrap.metadata.name
    val allAmuletVersion = DarResources.lookupAllPackageVersions(amuletPackageName)
    val expectedToBeVettedAmuletVersions = allAmuletVersion
      .filter(
        _.metadata.version > PackageIdResolver.readPackageVersion(
          initialPackageConfig.toPackageConfig,
          PackageIdResolver.Package.SpliceAmulet,
        )
      )
      .filter(_.metadata.version <= DarResources.amulet.bootstrap.metadata.version)
    expectedToBeVettedAmuletVersions.foreach { expectedVettedVersion =>
      val newAmuletVettedPackage = vettingState.packages
        .find(_.packageId == expectedVettedVersion.packageId)
        .value

      newAmuletVettedPackage.validFrom should (
        equal(scheduledTimeO) or equal(scheduledTime1) or equal(scheduledTime2)
      )
    }
  }

  private def alicesTapsWithPackageId(
      packageId: String
  )(implicit env: SpliceTestConsoleEnvironment) = {
    val tapContractId = aliceValidatorWalletClient.tap(10)
    aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
      .of_party(Amulet.COMPANION)(dsoParty)
      .filter(_.contractId == tapContractId.contractId)
      .loneElement
      .getTemplateId
      .packageId shouldBe packageId
  }
}
