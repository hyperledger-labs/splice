// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.admin.api.client.data.TemplateId
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.{ParticipantId, PartyId}
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.daml.lf.data.Ref.PackageVersion
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.Amulet
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.{
  AmuletConfig,
  PackageConfig,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules_SetConfig
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.amuletrules_actionrequiringconfirmation.CRARC_SetConfig
import org.lfdecentralizedtrust.splice.codegen.java.splice.splitwell.balanceupdatetype
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.payment as walletCodegen
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.console.{
  ParticipantClientReference,
  SplitwellAppClientReference,
  WalletAppClientReference,
}
import org.lfdecentralizedtrust.splice.environment.{DarResource, DarResources, PackageIdResolver}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.plugins.TokenStandardCliSanityCheckPlugin
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.splitwell.admin.api.client.commands.HttpSplitwellAppClient
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.SvPackageVettingTrigger
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingConfig.InitialPackageConfig
import org.lfdecentralizedtrust.splice.util.{SpliceUtil, SplitwellTestUtil}
import org.lfdecentralizedtrust.splice.validator.automation.ValidatorPackageVettingTrigger
import org.lfdecentralizedtrust.splice.wallet.automation.CollectRewardsAndMergeAmuletsTrigger
import org.scalatest.time.{Minute, Span}

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.duration.DurationInt

@org.lfdecentralizedtrust.splice.util.scalatesttags.NoDamlCompatibilityCheck
class BootstrapPackageConfigIntegrationTest extends IntegrationTest with SplitwellTestUtil {

  // this test starts up on older version (see initialPackageConfig), which don't define token-standard interfaces
  // and thus everything will show up as raw create/archives.
  override protected lazy val tokenStandardCliBehavior
      : TokenStandardCliSanityCheckPlugin.OutputCreateArchiveBehavior =
    TokenStandardCliSanityCheckPlugin.OutputCreateArchiveBehavior.IgnoreAll

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(1, Minute)))

  // Factored out so we can reuse it in the test
  val initialAmulet: DarResource = DarResources.amulet_0_1_14

  private val initialPackageConfig = InitialPackageConfig.minimumInitialPackageConfig

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
      .addConfigTransforms(
        // we are casting two votes in quick succession; and we don't want to wait for the cooldown
        (_, config) => ConfigTransforms.withNoVoteCooldown(config)
      )
      // Disable automerging to make the tx history deterministic
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Validator)(
          _.withPausedTrigger[CollectRewardsAndMergeAmuletsTrigger]
        )(config)
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

      // Split into batches to avoid gRPC message size limit (10 MB)
      val batchSize = 12
      val versionBatches =
        DarResources.splitwell.all.map(_.metadata.version).distinct.grouped(batchSize).toSeq

      Seq(aliceValidatorBackend, bobValidatorBackend, splitwellValidatorBackend).foreach { p =>
        versionBatches.foreach { batch =>
          p.participantClient.dars.upload_many(
            batch.map((v: PackageVersion) => s"daml/dars/splitwell-$v.dar")
          )
        }
      }
    }

    val (_, bobUserParty, _, _, key, _) = initSplitwellTest()

    clue("Bob's validator right has the right package id") {
      // ValidatorRight is special as it does not have the DSO as a signatory but is involved in workflows that include the DSO
      // so we need to make sure it uses a package id also vetted by the DSO and not the newer one that is pulled in by the splitwell DAR above.
      val bobValidatorRight =
        bobValidatorBackend.participantClientWithAdminToken.ledger_api.state.acs
          .active_contracts_of_party(
            bobUserParty,
            filterTemplates = Seq(TemplateId("#splice-amulet", "Splice.Amulet", "ValidatorRight")),
          )
          .loneElement
          .getCreatedEvent
      bobValidatorRight.getTemplateId.packageId shouldBe initialAmulet.packageId
    }

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
    val expiration = new RelTime(19_000_000)
    val scheduledTime =
      Instant.now().plus(expiration.microseconds, ChronoUnit.MICROS).plus(1, ChronoUnit.SECONDS)
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
          DarResources.amulet.latest.metadata.version.toString(),
          DarResources.amuletNameService.latest.metadata.version.toString(),
          DarResources.dsoGovernance.latest.metadata.version.toString(),
          DarResources.validatorLifecycle.latest.metadata.version.toString(),
          DarResources.wallet.latest.metadata.version.toString(),
          DarResources.walletPayments.latest.metadata.version.toString(),
        ),
        amuletConfig.transferPreapprovalFee,
        amuletConfig.featuredAppActivityMarkerAmount,
        amuletConfig.optDevelopmentFundManager,
        amuletConfig.externalPartyConfigStateTickDuration,
      )

      val upgradeAction = new ARC_AmuletRules(
        new CRARC_SetConfig(
          new AmuletRules_SetConfig(
            newAmuletConfig,
            amuletConfig,
          )
        )
      )

      val (_, voteRequest) = actAndCheck(
        "Creating vote request for upgraded packages",
        eventuallySucceeds() {
          sv1Backend.createVoteRequest(
            sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
            upgradeAction,
            "url",
            "description",
            expiration,
            Some(scheduledTime),
          )
        },
      )("vote request has been created", _ => sv1Backend.listVoteRequests().loneElement)

      actAndCheck(timeUntilSuccess = 30.seconds)(
        s"sv1-3 accept vote request for upraded packages",
        Seq(sv1Backend, sv2Backend, sv3Backend).map(sv =>
          eventuallySucceeds() {
            sv.castVote(
              voteRequest.contractId,
              isAccepted = true,
              "url",
              "description",
            )
          }
        ),
      )(
        "observe AmuletRules with upgraded config",
        _ => {
          val newAmuletRules = sv1Backend.getDsoInfo().amuletRules

          newAmuletRules.payload.configSchedule.initialValue.packageConfig.amulet shouldBe DarResources.amulet.latest.metadata.version
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
          clue(s"Alice sees updated vetting state for ${participantClient.id}") {
            eventually() {
              vettingIsUpdatedForTheNewConfig(
                aliceValidatorBackend.participantClient,
                participantClient.id,
                scheduledTimeO,
                Some(vettingScheduledTime),
                Some(vettingScheduledTime),
              )
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

    clue(s"Vetting state for slow sv is updated after the trigger runs, and alice sees it") {
      eventually() {
        vettingIsUpdatedForTheNewConfig(
          aliceValidatorBackend.participantClient,
          sv2Backend.participantClient.id,
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
      alicesTapsWithPackageId(DarResources.amulet.latest.packageId)
    }

    clue("ExternalPartyAmuletRules gets created") {
      eventuallySucceeds() {
        sv1ScanBackend.getExternalPartyAmuletRules()
      }
    }

    // We check this as splice-amulet < 0.1.14 did not support setting the fees to zero;
    // and we want to ensure that the upgrade works as expected.
    clue("Change AmuletConfig to zero fees") {
      // Here we pick an expiration far enough in the future to surely get the test through,
      // as we can observe the completion of the vote request via the config change.
      val expiration = new RelTime(3_600_000_000L)
      val amuletRules = sv2ScanBackend.getAmuletRules()
      val amuletConfig = amuletRules.payload.configSchedule.initialValue
      val newAmuletConfig = new AmuletConfig(
        SpliceUtil.defaultTransferConfig(
          amuletConfig.transferConfig.maxNumInputs,
          amuletConfig.transferConfig.holdingFee.rate,
        ),
        amuletConfig.issuanceCurve,
        amuletConfig.decentralizedSynchronizer,
        amuletConfig.tickDuration,
        amuletConfig.packageConfig,
        amuletConfig.transferPreapprovalFee,
        amuletConfig.featuredAppActivityMarkerAmount,
        amuletConfig.optDevelopmentFundManager,
        amuletConfig.externalPartyConfigStateTickDuration,
      )

      val upgradeAction = new ARC_AmuletRules(
        new CRARC_SetConfig(
          new AmuletRules_SetConfig(
            newAmuletConfig,
            amuletConfig,
          )
        )
      )

      clue("Double-check that no vote request exists") {
        sv1Backend.listVoteRequests() shouldBe empty
      }

      val (_, voteRequest) = actAndCheck(
        "Create vote request to vote for zero fees",
        eventuallySucceeds() {
          sv1Backend.createVoteRequest(
            sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
            upgradeAction,
            "url",
            "description",
            expiration,
            None,
          )
        },
      )("vote request has been created", _ => sv1Backend.listVoteRequests().loneElement)

      actAndCheck(
        s"sv1-3 accept vote request for zero fees",
        Seq(sv1Backend, sv2Backend, sv3Backend).map(sv =>
          eventuallySucceeds() {
            sv.castVote(
              voteRequest.contractId,
              isAccepted = true,
              "url",
              "description",
            )
          }
        ),
      )(
        "observe AmuletRules with upgraded config",
        _ => {
          val newAmuletRules = sv1ScanBackend.getAmuletRules().contract.payload
          val newCreateFee: BigDecimal =
            newAmuletRules.configSchedule.initialValue.transferConfig.createFee.fee
          newCreateFee shouldBe BigDecimal(0)
        },
      )
    }

    clue("Splitwell can complete payment request on new DAR versions") {
      splitwellPaymentRequest(aliceSplitwellClient, aliceWalletClient, key, bobUserParty, 23.0)
    }
  }

  private def vettingIsUpdatedForTheNewConfig(
      checkViaParticipant: ParticipantClientReference,
      vettedByParticipant: ParticipantId,
      scheduledTimeO: Option[CantonTimestamp],
      scheduledTime1: Option[CantonTimestamp],
      scheduledTime2: Option[CantonTimestamp],
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    val vettingTopologyState = checkViaParticipant.topology.vetted_packages.list(
      store = Some(
        TopologyStoreId.Synchronizer(
          decentralizedSynchronizerId
        )
      ),
      filterParticipant = vettedByParticipant.filterString,
    )
    val vettingState = vettingTopologyState.loneElement.item
    def packagesAreVetted(
        bootstrapPackage: DarResource,
        packageName: PackageIdResolver.Package,
    ): Unit = {
      val allPackagesVersions = DarResources.lookupAllPackageVersions(packageName.packageName)
      val expectedToBeVettedVersions = allPackagesVersions
        .filter(
          _.metadata.version > PackageIdResolver.readPackageVersion(
            initialPackageConfig.toPackageConfig,
            packageName,
          )
        )
        .filter(_.metadata.version <= bootstrapPackage.metadata.version)
      expectedToBeVettedVersions.foreach { expectedVettedVersion =>
        val newVettedPackage = vettingState.packages
          .find(_.packageId == expectedVettedVersion.packageId)
          .value
        newVettedPackage.validFromInclusive should (
          equal(scheduledTimeO) or equal(scheduledTime1) or equal(scheduledTime2)
        )
      }
    }
    packagesAreVetted(DarResources.amulet.latest, PackageIdResolver.Package.SpliceAmulet)
    // also check wallet because for the sv we have 2 vetting triggers, and the wallet is used in the tap call but it's vetted by the validator trigger (amulet rules can be vetted by any of the triggers)
    packagesAreVetted(DarResources.wallet.latest, PackageIdResolver.Package.SpliceWallet)
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
