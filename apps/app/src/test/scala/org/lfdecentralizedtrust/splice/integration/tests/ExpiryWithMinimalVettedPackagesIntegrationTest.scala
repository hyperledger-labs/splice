// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.{ForceFlag, ForceFlags, PartyId}
import com.digitalasset.daml.lf.data.Ref.{PackageId, PackageName, PackageVersion}
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{
  AppRewardCoupon,
  FeaturedAppActivityMarker,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.TransferPreapproval
import org.lfdecentralizedtrust.splice.codegen.java.splice.ans.{AnsEntry, AnsEntryContext}
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.payment.{PaymentAmount, Unit}
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.subscriptions.*
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.environment.{DarResource, DarResources}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTestWithIsolatedEnvironment,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.store.db.DbMultiDomainAcsStore
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.*
import org.lfdecentralizedtrust.splice.util.*
import org.lfdecentralizedtrust.splice.validator.automation.ValidatorPackageVettingTrigger
import org.lfdecentralizedtrust.splice.wallet.automation.SubscriptionReadyForPaymentTrigger
import org.slf4j.event.Level

import java.time.Duration
import scala.concurrent.duration.*

abstract class ExpiryWithMinimalVettedPackagesIntegrationTestBase
    extends IntegrationTestWithIsolatedEnvironment
    with WalletTestUtil
    with TimeTestUtil
    with TriggerTestUtil
    with PackageUnvettingUtil {

  override protected def runTokenStandardCliSanityCheck: Boolean = false
  override protected def runUpdateHistorySanityCheck: Boolean = false

  protected val ignoredAmuletVersions: Set[String] = Set.empty

  protected def packagesToUnvetOnAlice(
      packages: Seq[DarResource]
  ): Map[PackageName, Set[PackageVersion]] =
    packages
      .groupBy(_.metadata.name)
      .map { case (name, resources) => name -> resources.map(_.metadata.version).toSet }

  // have alice vet only the minimal required package versions
  private val unvetOnAlice = packagesToUnvetOnAlice(
    DarResourcesUtil.supportedPackageVersions
      .filterNot(DarResourcesUtil.minimalPackageVersions.contains(_))
  )

  protected val enablePersistedUnavailableParties: Boolean

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withNoVettedPackages(implicit env => env.validators.local.map(_.participantClient))
      .withTrafficTopupsDisabled
      .addConfigTransforms(
        (_, c) =>
          ConfigTransforms.updateInitialTickDuration(NonNegativeFiniteDuration.ofMillis(500))(c),
        (_, c) =>
          ConfigTransforms.updateInitialExternalPartyConfigStateTickDuration(
            NonNegativeFiniteDuration.ofMillis(500)
          )(c),
        // The validator on the old version otherwise will fail to onboard as we cannot downgrade DsoRules when trying to create their license.
        (_, c) => ConfigTransforms.withNoSvOperationsSwitchOverTimes(c),
      )
      .addConfigTransforms((_, config) => {
        val aliceVal = InstanceName.tryCreate("aliceValidator")
        config.copy(
          validatorApps = config.validatorApps +
            (aliceVal -> config
              .validatorApps(aliceVal)
              .copy(additionalPackagesToUnvet = unvetOnAlice))
        )
      })
      .addConfigTransforms((_, c) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
          _.withPausedTrigger[AdvanceOpenMiningRoundTrigger]
            .withPausedTrigger[UpdateExternalPartyConfigStateTrigger]
            .withPausedTrigger[ExpireRewardCouponsTrigger]
            .withPausedTrigger[FeaturedAppActivityMarkerTrigger]
            .withPausedTrigger[ExpireTransferPreapprovalsTrigger]
            .withPausedTrigger[ExpiredAnsEntryTrigger]
            .withPausedTrigger[ExpiredAnsSubscriptionTrigger]
            .withPausedTrigger[ExpiredAmuletTrigger]
            .withPausedTrigger[ExpiredLockedAmuletTrigger]
        )(c)
      )
      .addConfigTransforms((_, c) =>
        updateAutomationConfig(ConfigurableApp.Validator)(
          _.copy(enableAutomaticRewardsCollectionAndAmuletMerging = false)
            .withPausedTrigger[SubscriptionReadyForPaymentTrigger]
        )(c)
      )
      .addConfigTransforms((_, c) =>
        ConfigTransforms.updateAllSvAppConfigs_(
          _.copy(delegatelessAutomationExpiredAmuletBatchSize = 2)
        )(c)
      )
      .addConfigTransforms((_, c) =>
        ConfigTransforms.updateAllSvAppConfigs_(
          _.copy(ignoredAmuletVersions = ignoredAmuletVersions)
        )(c)
      )
      .addConfigTransforms((_, c) =>
        ConfigTransforms.updateAllSvAppConfigs_(conf =>
          conf.copy(parameters =
            conf.parameters.copy(enabledFeatures =
              conf.parameters.enabledFeatures
                .copy(enablePersistedUnavailableParties = enablePersistedUnavailableParties)
            )
          )
        )(c)
      )

  protected val danglingSubscriptionCid = new Subscription.ContractId("00" * 33 + "01")
  protected val danglingSubscriptionRequestCid =
    new SubscriptionRequest.ContractId("00" * 33 + "02")

  protected def createAsDso[T](signatories: PartyId*)(
      update: com.daml.ledger.javaapi.data.codegen.Update[T]
  )(implicit env: SpliceTestConsoleEnvironment) = {
    sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
      .submitWithResult(
        userId = sv1Backend.config.ledgerApiUser,
        actAs = dsoParty +: signatories,
        readAs = Seq.empty,
        update = update,
      )
      .discard
  }

  protected def dsoAcs(implicit env: SpliceTestConsoleEnvironment) =
    sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs

  protected def setupAliceWithDustAmulets()(implicit env: SpliceTestConsoleEnvironment): PartyId = {
    val synchronizerId = decentralizedSynchronizerId

    clue("aliceValidator has not vetted splice-amulet 0.1.17 and 0.1.18") {
      eventually() {
        val vetted = getVettedPackageIds(
          aliceValidatorBackend.appState.participantAdminConnection,
          synchronizerId,
        ).toSet
        vetted should not contain DarResources.amulet_0_1_17.packageId
        vetted should not contain DarResources.amulet_0_1_18.packageId
      }
    }

    val aliceUserId = aliceWalletClient.config.ledgerApiUser
    val aliceParty = onboardWalletUserHostedAlsoOn(
      aliceWalletClient,
      aliceValidatorBackend,
      sv1Backend.participantClientWithAdminToken,
      synchronizerId,
    )

    val numAmulets = 2
    val amuletAmount = BigDecimal(123.0)

    loggerFactory.suppress(
      SuppressionRule.forLogger[DbMultiDomainAcsStore[?]] && SuppressionRule.Level(Level.ERROR)
    ) {
      actAndCheck(
        "Create V1-pinned dust amulets owned by alice", {
          for (_ <- 1 to numAmulets) {
            createAmulet(
              sv1Backend.participantClientWithAdminToken,
              aliceUserId,
              aliceParty,
              amount = amuletAmount,
              holdingFee = amuletAmount,
            )
            createLockedAmulet(
              sv1Backend.participantClientWithAdminToken,
              aliceUserId,
              aliceParty,
              lockHolders = Seq(aliceParty),
              amount = amuletAmount,
              holdingFee = amuletAmount,
              expiredDuration = Duration.ofSeconds(1),
            )
          }
        },
      )(
        "Dust amulets show up in alice's wallet",
        _ => {
          aliceWalletClient.list().amulets should have length numAmulets.toLong
          aliceWalletClient.list().lockedAmulets should have length numAmulets.toLong
        },
      )
      aliceParty
    }
  }
}

/** Tests that expiry triggers fall back to V1 choices when alice's validator
  * has only vetted minimal package versions (not splice-amulet 0.1.17+).
  */
class AmuletExpiryV1FallbackIntegrationTest
    extends ExpiryWithMinimalVettedPackagesIntegrationTestBase {

  override protected val enablePersistedUnavailableParties: Boolean = true

  "Amulet expiry falls back to V1 choices when alice's validator has not vetted splice-amulet 0.1.17" in {
    implicit env =>
      setupAliceWithDustAmulets()
      actAndCheck(timeUntilSuccess = 60.seconds)(
        "Advance 4 rounds and resume expiry triggers", {
          (1 to 4).foreach(_ => advanceRoundsByOneTickViaAutomation())
          updateExternalPartyConfigStatesViaAutomation()
          updateExternalPartyConfigStatesViaAutomation()
          env.svs.local.foreach { sv =>
            sv.dsoDelegateBasedAutomation.trigger[ExpiredAmuletTrigger].resume()
            sv.dsoDelegateBasedAutomation.trigger[ExpiredLockedAmuletTrigger].resume()
          }
        },
      )(
        "Dust amulets are expired via V1 choices",
        _ => {
          aliceWalletClient.list().amulets shouldBe empty withClue "dust amulets"
          aliceWalletClient.list().lockedAmulets shouldBe empty withClue "dust lockedAmulets"
        },
      )
  }
}

/** Tests that expiry triggers skip batches when the task's amulet preferred package version
  * is listed in `ignoredAmuletVersions`, adding the party to the ignored-parties store.
  */
class ExpiryWithIgnoredAmuletVersionIntegrationTest
    extends ExpiryWithMinimalVettedPackagesIntegrationTestBase {

  override protected val enablePersistedUnavailableParties: Boolean = false

  // Amulet version 0.1.19 is just below the minimumInitialization version.
  override val ignoredAmuletVersions: Set[String] = Set(
    DarResources.amulet_0_1_19.metadata.version.toString
  )

  private val entryName = "alice.unverified.ans"
  private val entryDescription = "expired ans entry"

  "Expiry triggers skip parties whose preferred amulet package version is ignored" in {
    implicit env =>
      val alice = setupAliceWithDustAmulets()
      val aliceId = alice.toProtoPrimitive
      val dsoId = dsoParty.toProtoPrimitive

      advanceRoundsByOneTickViaAutomation()
      advanceRoundsByOneTickViaAutomation()

      val (openRounds, _) = sv1ScanBackend.getOpenAndIssuingMiningRounds()
      val currentRound = openRounds.toList.headOption.value.payload.round
      val now = env.environment.clock.now.toInstant
      val expired = now.minus(Duration.ofSeconds(1))

      clue("Create dust contracts owned or referenced by alice") {
        createAsDso()(
          new AppRewardCoupon(
            dsoId,
            aliceId,
            false,
            BigDecimal(10.0).bigDecimal,
            currentRound,
            java.util.Optional.empty(),
          ).create
        )
        createAsDso()(
          new FeaturedAppActivityMarker(dsoId, aliceId, aliceId, BigDecimal(1.0).bigDecimal).create
        )
        createAsDso(alice)(
          new TransferPreapproval(
            dsoId,
            aliceId, // receiver
            aliceId, // provider
            now.minus(Duration.ofHours(1)), // validFrom
            now.minus(Duration.ofHours(1)), // lastRenewedAt
            expired,
          ).create
        )
        createAsDso(alice)(
          new AnsEntry(aliceId, dsoId, entryName, "", entryDescription, expired).create
        )
        createAsDso(alice)(
          new AnsEntryContext(
            dsoId,
            aliceId,
            entryName,
            "",
            entryDescription,
            danglingSubscriptionRequestCid,
          ).create
        )
        createAsDso(alice)(
          new SubscriptionIdleState(
            danglingSubscriptionCid,
            new SubscriptionData(aliceId, dsoId, dsoId, dsoId, entryDescription),
            new SubscriptionPayData(
              new PaymentAmount(BigDecimal(1.0).bigDecimal, Unit.AMULETUNIT),
              new RelTime(1_000_000_000L),
              new RelTime(1_000_000L),
            ),
            expired, // nextPaymentDueAt -> overdue
            danglingSubscriptionRequestCid,
          ).create
        )
      }

      actAndCheck(timeUntilSuccess = 60.seconds)(
        "Advance 4 rounds and resume expiry triggers", {
          (1 to 4).foreach(_ => advanceRoundsByOneTickViaAutomation())
          updateExternalPartyConfigStatesViaAutomation()
          updateExternalPartyConfigStatesViaAutomation()
          env.svs.local.foreach { sv =>
            sv.dsoDelegateBasedAutomation.trigger[ExpiredAmuletTrigger].resume()
            sv.dsoDelegateBasedAutomation.trigger[ExpiredLockedAmuletTrigger].resume()
            sv.dsoDelegateBasedAutomation.trigger[ExpireRewardCouponsTrigger].resume()
            sv.dsoDelegateBasedAutomation.trigger[FeaturedAppActivityMarkerTrigger].resume()
            sv.dsoDelegateBasedAutomation.trigger[ExpireTransferPreapprovalsTrigger].resume()
            sv.dsoDelegateBasedAutomation.trigger[ExpiredAnsEntryTrigger].resume()
            sv.dsoDelegateBasedAutomation.trigger[ExpiredAnsSubscriptionTrigger].resume()
          }
        },
      )(
        s"All dust contracts remain because alice's preferred version is in ignoredAmuletVersions",
        _ => {
          sv1Backend.dsoDelegateBasedAutomation.unavailablePartiesStore
            .listParties()(TraceContext.empty)
            .futureValue should
            contain(alice)

          aliceWalletClient.list().amulets should have length 2L withClue "amulets"
          aliceWalletClient.list().lockedAmulets should have length 2L withClue "locked amulets"

          dsoAcs.filterJava(AppRewardCoupon.COMPANION)(
            dsoParty,
            _.data.provider == aliceId,
          ) should have size 1L withClue "app reward coupon"

          dsoAcs.filterJava(FeaturedAppActivityMarker.COMPANION)(
            dsoParty,
            _.data.provider == aliceId,
          ) should have size 1L withClue "featured app activity marker"

          dsoAcs.filterJava(TransferPreapproval.COMPANION)(
            dsoParty,
            _.data.receiver == aliceId,
          ) should have size 1L withClue "transfer preapproval"

          dsoAcs.filterJava(AnsEntry.COMPANION)(
            dsoParty,
            _.data.user == aliceId,
          ) should have size 1L withClue "ans entry"

          dsoAcs.filterJava(SubscriptionIdleState.COMPANION)(
            dsoParty,
            _.data.subscriptionData.sender == aliceId,
          ) should have size 1L withClue "ans subscription"
        },
      )
  }
}

/** Tests that expiry triggers ignore parties whose participant has no vetted amulet.
  * Only Amulet contracts are covered in this test, as the ignore logic is shared across expiry triggers.
  */
class ExpiryWithNoVettedAmuletVersionIntegrationTest
    extends ExpiryWithMinimalVettedPackagesIntegrationTestBase {

  override protected val enablePersistedUnavailableParties: Boolean = true

  "Amulet expiry ignores parties with no vetted amulet version" in { implicit env =>
    val alice = setupAliceWithDustAmulets()

    clue("Alice unvets every amulet version") {
      aliceValidatorBackend.validatorAutomation
        .trigger[ValidatorPackageVettingTrigger]
        .pause()
        .futureValue
      aliceValidatorBackend.participantClient.topology.vetted_packages.propose_delta(
        aliceValidatorBackend.participantClient.id,
        store = decentralizedSynchronizerId,
        removes = DarResources.amulet.all.map(p => PackageId.assertFromString(p.packageId)),
        force = ForceFlags(ForceFlag.AllowUnvettedDependencies),
      )
      eventually() {
        val vetted = getVettedPackageIds(
          aliceValidatorBackend.appState.participantAdminConnection,
          decentralizedSynchronizerId,
        ).toSet
        DarResources.amulet.all.foreach(p => vetted should not contain p.packageId)
      }
    }

    actAndCheck(timeUntilSuccess = 60.seconds)(
      "Advance 4 rounds and resume the amulet expiry trigger", {
        (1 to 4).foreach(_ => advanceRoundsByOneTickViaAutomation())
        updateExternalPartyConfigStatesViaAutomation()
        updateExternalPartyConfigStatesViaAutomation()
        env.svs.local.foreach(
          _.dsoDelegateBasedAutomation.trigger[ExpiredAmuletTrigger].resume()
        )
      },
    )(
      "Alice is ignored and her dust amulets are not expired",
      _ => {
        val ignored = sv1Backend.dsoDelegateBasedAutomation.unavailablePartiesStore
          .listParties()(TraceContext.empty)
          .futureValue
        ignored should contain(alice)
        ignored should not contain dsoParty
        aliceWalletClient.list().amulets should have length 2L withClue "dust amulets"
      },
    )
  }
}
