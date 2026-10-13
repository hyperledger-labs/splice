package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.metrics.MetricValue
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.topology.transaction.VettedPackage
import com.digitalasset.canton.topology.{ForceFlag, ForceFlags, ParticipantId, PartyId}
import com.digitalasset.daml.lf.data.Ref.{PackageId, PackageVersion}
import java.time.Duration
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.FeaturedAppRight
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.rewardassignmentv1.{
  RewardBeneficiary,
  RewardCoupon,
  RewardCoupon_AssignBeneficiaries,
}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.sv.config.InitialRewardConfig
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.environment.{DarResource, DarResources, RetryFor}
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics.MetricsPrefix
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyTransactionType.AuthorizedState
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTestWithIsolatedEnvironment,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.{
  ExpireRewardCouponV2Trigger,
  UnhideRewardCouponV2Trigger,
}
import org.lfdecentralizedtrust.splice.util.{
  ChoiceContextWithDisclosures,
  TimeTestUtil,
  UploadablePackage,
  WalletTestUtil,
}
import org.lfdecentralizedtrust.splice.wallet.automation.AcceptedTransferOfferTrigger

import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

// Tests the following
// - ProcessRewardsTrigger handles providers with wrong vetting state
// - UnhideRewardCouponV2Trigger does unhide when vetting state is correct
// - ExpireRewardCouponV2Trigger does expiry correctly in both vetting state scenarios
// Also include a test for an additional scenario where the reward assignment is
// done while the beneficiary is offline (but has already vetted).
@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_19
class UnhideAndExpireRewardCouponV2TimeBasedIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with TimeTestUtil {

  private val rewardCouponTtl = Duration.ofHours(2)

  // Version where V2 was introduced, or the current minimum initialization version if higher
  private val minV2AmuletVersion =
    Ordering[PackageVersion].max(
      DarResources.amulet.minimumInitialization.metadata.version,
      DarResources.amulet_0_1_19.metadata.version,
    )

  private val minV2AmuletPackageId =
    DarResources.amulet.getPackageIdWithVersion(minV2AmuletVersion.toString).value

  private val latestAmuletDar: DarResource = DarResources.amulet.latest

  private val v2CapableAmuletPackageIds: Seq[String] =
    DarResources.amulet.all
      .filter(_.metadata.version >= minV2AmuletVersion)
      .map(_.packageId)
      .distinct

  private val amuletVersionsAboveOldestV2: Seq[String] =
    DarResources.amulet.all
      .filter(_.metadata.version > minV2AmuletVersion)
      .map(_.packageId)
      .distinct

  // Only the latest is unvetted, as this would still cause
  // ProcessRewardsTrigger to create hidden coupons
  private val darsUnvettedOnAliceAtStart: Seq[DarResource] = {
    val latestAmuletIds = Set(latestAmuletDar.packageId)
    Seq(
      DarResources.amulet,
      DarResources.amuletNameService,
      DarResources.dsoGovernance,
      DarResources.wallet,
      DarResources.walletPayments,
    ).flatMap(_.all)
      .filter(d =>
        latestAmuletIds.contains(d.packageId) ||
          d.dependencyPackageIds.exists(latestAmuletIds.contains)
      )
      .distinctBy(d => (d.metadata.name, d.metadata.version))
  }

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .withNoVettedPackages(implicit env => Seq(aliceValidatorBackend.participantClient))
      .addConfigTransforms(
        (_, config) => {
          val aliceValidator = InstanceName.tryCreate("aliceValidator")
          config.copy(
            validatorApps = config.validatorApps +
              (aliceValidator -> config
                .validatorApps(aliceValidator)
                .copy(
                  additionalPackagesToUnvet = darsUnvettedOnAliceAtStart
                    .groupBy(_.metadata.name)
                    .map { case (name, resources) =>
                      name -> resources.map(_.metadata.version).toSet
                    }
                ))
          )
        },
        (_, c) => ConfigTransforms.withNoSvOperationsSwitchOverTimes(c),
      )
      .addConfigTransform((_, config) =>
        updateAutomationConfig(ConfigurableApp.Validator)(
          _.withPausedTrigger[AcceptedTransferOfferTrigger]
        )(config)
      )
      .addConfigTransform((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
          _.withPausedTrigger[UnhideRewardCouponV2Trigger]
        )(config)
      )
      .addConfigTransform((_, config) =>
        ConfigTransforms.updateAllSvAppConfigs_(svConfig =>
          svConfig.copy(
            packageVettingCache = svConfig.packageVettingCache.copy(
              ttl = NonNegativeFiniteDuration.ofMillis(1)
            )
          )
        )(config)
      )
      .addConfigTransform((_, config) =>
        ConfigTransforms.updateAllSvAppFoundDsoConfigs_(foundDso =>
          foundDso.copy(initialRewardConfig =
            Some(
              foundDso.initialRewardConfig
                .getOrElse(InitialRewardConfig())
                .copy(rewardCouponTimeToLiveMicros = rewardCouponTtl.toNanos / 1000)
            )
          )
        )(config)
      )
      .withoutAutomaticRewardsCollectionAndAmuletMerging

  "Unhide and expire of RewardCouponV2" in { implicit env =>
    val aliceParticipantId =
      aliceValidatorBackend.appState.participantAdminConnection.getParticipantId().futureValue
    assertAliceVettedBelowLatest(aliceParticipantId)

    val (aliceParty, bobParty) = onboardAliceAndBobWithFeaturedRights()

    def doTransfer(): Unit = {
      val offerCid = bobWalletClient.createTransferOffer(
        aliceParty,
        BigDecimal(10.0),
        "activity",
        getLedgerTime.plus(Duration.ofMinutes(1)),
        s"transfer-${scala.util.Random.nextInt()}",
      )
      aliceWalletClient.acceptTransferOffer(offerCid)
    }

    def aliceCoupons =
      sv1Backend.appState.dsoStore
        .listRewardCouponsV2()
        .futureValue
        .filter(_.payload.provider == aliceParty.toProtoPrimitive)

    def bobUnassignedCoupons =
      sv1Backend.appState.dsoStore
        .listRewardCouponsV2()
        .futureValue
        .filter(c =>
          c.payload.provider == bobParty.toProtoPrimitive && c.payload.beneficiary.isEmpty
        )

    for (round <- 1 to 4) {
      advanceRoundsToNextRoundOpening
      assertOldestOpenRound(round.toLong)
    }
    // FA right now effective from round 4
    doTransfer()
    advanceRoundsToNextRoundOpening

    clue(
      "RewardCouponV2 can be expired both when provider is an observer and not an observer"
    ) {
      clue(
        "ProcessRewardsTrigger creates Alice's RewardCouponV2 hidden because she is unvetted, Bob is an observer"
      ) {
        eventually() {
          aliceCoupons.filterNot(_.payload.providerIsObserver) should not be empty
          aliceCoupons.filter(_.payload.providerIsObserver) shouldBe empty
          bobUnassignedCoupons.filter(_.payload.providerIsObserver) should not be empty
          bobUnassignedCoupons.filterNot(_.payload.providerIsObserver) shouldBe empty
          hiddenCouponsMetricValue(aliceParty) shouldBe 1L
        }
      }

      actAndCheck(
        "Advance past the coupon TTL while Alice is unvetted, one round at a time",
        advanceRoundsUntil(getLedgerTime.toInstant.plus(rewardCouponTtl.plusMinutes(20))),
      )(
        "ExpireRewardCouponV2Trigger archives Alice's hidden and Bob's coupons while she is unvetted",
        _ => sv1Backend.appState.dsoStore.listRewardCouponsV2().futureValue shouldBe empty,
      )
    }

    clue(
      "coupons are unhid after vetting"
    ) {
      actAndCheck()(
        "Generate new Alice+Bob activity while Alice is still unvetted", {
          doTransfer()
          advanceRoundsToNextRoundOpening
        },
      )(
        "ProcessRewardsTrigger again creates Alice's RewardCouponV2 hidden, Bob's as observer",
        _ => {
          aliceCoupons.filterNot(_.payload.providerIsObserver) should not be empty
          bobUnassignedCoupons should not be empty
        },
      )

      uploadAndRevetV2DarsOnAlice(aliceParticipantId)

      clue("UnhideRewardCouponV2Trigger unhides Alice's coupons once she is re-vetted") {
        eventually() {
          sv1Backend.dsoDelegateBasedAutomation
            .trigger[UnhideRewardCouponV2Trigger]
            .runOnce()
            .futureValue
          val coupons = aliceCoupons
          coupons should not be empty
          coupons.filterNot(_.payload.providerIsObserver) shouldBe empty
        }
      }
    }

    // Scenario for #6372, both providers have V2 capable versions vetted, but they lack a common vetted version.
    clue(
      "ProcessRewardsTrigger handles a batch where providers have jointly-incompatible vetting states"
    ) {
      val bobParticipantId =
        bobValidatorBackend.appState.participantAdminConnection.getParticipantId().futureValue

      // Alice unvets minV2AmuletVersion; Bob keeps only minV2AmuletVersion, nothing after it.
      actAndCheck(
        s"Unvet $minV2AmuletPackageId on Alice and $amuletVersionsAboveOldestV2 on Bob", {
          aliceValidatorBackend.participantClient.topology.vetted_packages.propose_delta(
            aliceParticipantId,
            removes = Seq(PackageId.assertFromString(minV2AmuletPackageId)),
            force = ForceFlags(ForceFlag.AllowUnvettedDependencies),
            store = TopologyStoreId.Synchronizer(decentralizedSynchronizerId),
          )
          bobValidatorBackend.participantClient.topology.vetted_packages.propose_delta(
            bobParticipantId,
            removes = amuletVersionsAboveOldestV2.map(PackageId.assertFromString),
            force = ForceFlags(ForceFlag.AllowUnvettedDependencies),
            store = TopologyStoreId.Synchronizer(decentralizedSynchronizerId),
          )
        },
      )(
        "sv1's participant observes Alice no longer has minV2AmuletVersion vetted, and Bob's vetting is capped at minV2AmuletVersion",
        _ => {
          vettedPackagesOnSv1View(aliceParticipantId) should not contain
            minV2AmuletPackageId
          vettedPackagesOnSv1View(bobParticipantId)
            .intersect(amuletVersionsAboveOldestV2) shouldBe empty

          val aliceVettedAboveMin =
            vettedPackagesOnSv1View(aliceParticipantId).intersect(amuletVersionsAboveOldestV2)
          val bobVettedAboveMin =
            vettedPackagesOnSv1View(bobParticipantId).intersect(amuletVersionsAboveOldestV2)
          aliceVettedAboveMin.intersect(bobVettedAboveMin) shouldBe empty
        },
      )

      val (round, _) = actAndCheck(
        "Generate activity", {
          doTransfer()
          val round = oldestOpenRound
          advanceRoundsToNextRoundOpening
          round
        },
      )(
        "ProcessRewardsTrigger issues coupons for the round",
        round => {
          val newAliceCoupons = aliceCoupons.filter(_.payload.round.number == round)
          val newBobCoupons = bobUnassignedCoupons.filter(_.payload.round.number == round)
          newAliceCoupons should not be empty
          newAliceCoupons.foreach(_.payload.providerIsObserver shouldBe true)
          newBobCoupons should not be empty
          newBobCoupons.foreach(_.payload.providerIsObserver shouldBe false)
        },
      )

      clue("UnhideRewardCouponV2Trigger can unhide Bob's coupon before he is fully re-vetted") {
        eventually() {
          sv1Backend.dsoDelegateBasedAutomation
            .trigger[UnhideRewardCouponV2Trigger]
            .runOnce()
            .futureValue
          val coupons = bobUnassignedCoupons.filter(_.payload.round.number == round)
          coupons should not be empty
          coupons.filterNot(_.payload.providerIsObserver) shouldBe empty
        }
      }

      clue("Restore Alice's and Bob's full vetting") {
        actAndCheck(
          s"Re-vet the minV2AmuletVersion package on Alice and $amuletVersionsAboveOldestV2 on Bob", {
            aliceValidatorBackend.participantClient.topology.vetted_packages.propose_delta(
              aliceParticipantId,
              adds = Seq(
                VettedPackage(
                  PackageId.assertFromString(minV2AmuletPackageId),
                  None,
                  None,
                )
              ),
              store = TopologyStoreId.Synchronizer(decentralizedSynchronizerId),
            )
            bobValidatorBackend.participantClient.topology.vetted_packages.propose_delta(
              bobParticipantId,
              adds = amuletVersionsAboveOldestV2.map(id =>
                VettedPackage(PackageId.assertFromString(id), None, None)
              ),
              store = TopologyStoreId.Synchronizer(decentralizedSynchronizerId),
            )
          },
        )(
          "sv1's participant observes Alice and Bob are fully vetted again",
          _ => {
            v2CapableAmuletPackageIds.toSet.subsetOf(
              vettedPackagesOnSv1View(aliceParticipantId).toSet
            ) shouldBe true
            v2CapableAmuletPackageIds.toSet.subsetOf(
              vettedPackagesOnSv1View(bobParticipantId).toSet
            ) shouldBe true
          },
        )
      }
    }

    clue(
      "RewardCouponV2 can be assigned after vetting, even when beneficiary is offline"
    ) {
      clue("Take Alice's validator and participant offline") {
        aliceValidatorBackend.stop()
        aliceValidatorBackend.participantClient.synchronizers.disconnect_all()
        aliceValidatorBackend.participantClient.synchronizers.is_connected(
          decentralizedSynchronizerId
        ) shouldBe false
      }

      actAndCheck(
        "Bob assigns half of his reward coupons to Alice", {
          val cids = bobUnassignedCoupons.map(_.contractId.toInterface(RewardCoupon.INTERFACE))
          bobValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
            .submitJava(
              Seq(bobParty),
              commands = cids.head
                .exerciseRewardCoupon_AssignBeneficiaries(
                  new RewardCoupon_AssignBeneficiaries(
                    cids.tail.asJava,
                    Seq(
                      new RewardBeneficiary(
                        aliceParty.toProtoPrimitive,
                        new java.math.BigDecimal("0.5"),
                      ),
                      new RewardBeneficiary(
                        bobParty.toProtoPrimitive,
                        new java.math.BigDecimal("0.5"),
                      ),
                    ).asJava,
                    ChoiceContextWithDisclosures.emptyExtraArgs,
                  )
                )
                .commands()
                .asScala
                .toSeq,
            )
        },
      )(
        "Alice-beneficiary coupons with Bob as provider exist on the DSO store",
        _ => {
          val beneficiaryCoupons = sv1Backend.appState.dsoStore
            .listRewardCouponsV2()
            .futureValue
            .filter(_.payload.beneficiary.toScala.contains(aliceParty.toProtoPrimitive))
          beneficiaryCoupons should not be empty
          beneficiaryCoupons.foreach { c =>
            c.payload.provider shouldBe bobParty.toProtoPrimitive
            c.payload.providerIsObserver shouldBe true
          }
        },
      )

      clue("Reconnect Alice's participant") {
        aliceValidatorBackend.participantClient.synchronizers.reconnect_all()
      }
    }

    clue(
      "ExpireRewardCouponV2Trigger handles coupons with unvetted state and also expire assigned coupons"
    ) {
      val aliceObservedCoupons = sv1Backend.appState.dsoStore
        .listRewardCouponsV2()
        .futureValue
        .filter(c =>
          c.payload.provider == aliceParty.toProtoPrimitive ||
            c.payload.beneficiary.toScala.contains(aliceParty.toProtoPrimitive)
        )
        .map(_.contractId.contractId)
        .toSet
      aliceObservedCoupons should not be empty

      unvetV2AmuletOnAlice(aliceParticipantId)

      val couponsBeforeAdvance = sv1Backend.appState.dsoStore
        .listRewardCouponsV2()
        .futureValue
        .map(_.contractId.contractId)
        .toSet

      actAndCheck(
        "Advance past the coupon TTL, one round at a time",
        advanceRoundsUntil(getLedgerTime.toInstant.plus(rewardCouponTtl.plusMinutes(20))),
      )(
        "ExpireRewardCouponV2Trigger ignores Alice coupons",
        _ => {
          sv1Backend.dsoDelegateBasedAutomation
            .trigger[ExpireRewardCouponV2Trigger]
            .runOnce()
            .futureValue
          val remaining = sv1Backend.appState.dsoStore
            .listRewardCouponsV2()
            .futureValue
            .map(_.contractId.contractId)
            .toSet
          aliceObservedCoupons.subsetOf(remaining) shouldBe true
        },
      )

      actAndCheck(
        "Re-vet Alice",
        revetV2AmuletOnAlice(aliceParticipantId, aliceParty),
      )(
        "ExpireRewardCouponV2Trigger archives once Alice is re-vetted",
        _ => {
          sv1Backend.dsoDelegateBasedAutomation
            .trigger[ExpireRewardCouponV2Trigger]
            .runOnce()
            .futureValue
          val remaining = sv1Backend.appState.dsoStore
            .listRewardCouponsV2()
            .futureValue
            .map(_.contractId.contractId)
            .toSet
          remaining.intersect(couponsBeforeAdvance) shouldBe empty
        },
      )
    }

  }

  private def vettedPackagesOnSv1View(
      participantId: ParticipantId
  )(implicit env: SpliceTestConsoleEnvironment): Seq[String] =
    sv1ValidatorBackend.appState.participantAdminConnection
      .listVettedPackages(participantId, decentralizedSynchronizerId, AuthorizedState)
      .futureValue
      .flatMap(_.mapping.packages.map(_.packageId))

  private def assertAliceVettedBelowLatest(
      aliceParticipantId: ParticipantId
  )(implicit env: SpliceTestConsoleEnvironment): Unit =
    clue("Alice's validator vets the second-latest amulet version but not the latest") {
      eventually() {
        val vetted = vettedPackagesOnSv1View(aliceParticipantId)
        vetted should contain(
          DarResources.amulet.others
            .filter(_.metadata.version < latestAmuletDar.metadata.version)
            .maxBy(_.metadata.version)
            .packageId
        )
        vetted should not contain latestAmuletDar.packageId
      }
    }

  private def onboardAliceAndBobWithFeaturedRights()(implicit
      env: SpliceTestConsoleEnvironment
  ): (PartyId, PartyId) = {
    val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
    val bobParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)

    bobWalletClient.tap(100)
    grantFeaturedAppRight(bobWalletClient)

    // Alice can't self-grant while unvetted, so use dso directly
    actAndCheck(
      "DSO directly creates Alice's FeaturedAppRight",
      sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
        .submitWithResult(
          userId = sv1Backend.config.ledgerApiUser,
          actAs = Seq(dsoParty),
          readAs = Seq.empty,
          update = new FeaturedAppRight(
            dsoParty.toProtoPrimitive,
            aliceParty.toProtoPrimitive,
            java.util.Optional.empty(),
          ).create,
        ),
    )(
      "Alice's featured app right is visible in scan",
      _ => sv1ScanBackend.lookupFeaturedAppRight(aliceParty) should not be empty,
    )

    (aliceParty, bobParty)
  }

  private def uploadAndRevetV2DarsOnAlice(
      aliceParticipantId: ParticipantId
  )(implicit env: SpliceTestConsoleEnvironment): Unit =
    actAndCheck(
      "Upload and re-vet the latest packages on Alice's participant", {
        val aliceAdminConnection = aliceValidatorBackend.appState.participantAdminConnection
        aliceAdminConnection
          .uploadDarFiles(
            darsUnvettedOnAliceAtStart.map(UploadablePackage.fromResource),
            RetryFor.Automation,
          )
          .futureValue
        aliceAdminConnection
          .vetDars(decentralizedSynchronizerId, darsUnvettedOnAliceAtStart, None, None)
          .futureValue
      },
    )(
      "sv1's participant observes Alice has the correct vetting state for RewardAccountingV2",
      _ =>
        vettedPackagesOnSv1View(aliceParticipantId) should contain(
          DarResources.amulet.latest.packageId
        ),
    )

  private def unvetV2AmuletOnAlice(
      aliceParticipantId: ParticipantId
  )(implicit env: SpliceTestConsoleEnvironment): Unit =
    actAndCheck(
      "Unvet the V2-capable amulet versions on Alice",
      aliceValidatorBackend.participantClient.topology.vetted_packages.propose_delta(
        aliceParticipantId,
        removes = v2CapableAmuletPackageIds.map(PackageId.assertFromString),
        force = ForceFlags(ForceFlag.AllowUnvettedDependencies),
        store = TopologyStoreId.Synchronizer(decentralizedSynchronizerId),
      ),
    )(
      "sv1's participant observes Alice is in the wrong vetting state",
      _ =>
        vettedPackagesOnSv1View(aliceParticipantId)
          .intersect(v2CapableAmuletPackageIds) shouldBe empty,
    )

  private def revetV2AmuletOnAlice(
      aliceParticipantId: ParticipantId,
      aliceParty: PartyId,
  )(implicit env: SpliceTestConsoleEnvironment): Unit =
    // Allocate sufficient time for the change to be visible on SV1, and avoid CI flake
    actAndCheck(timeUntilSuccess = 60.seconds)(
      "Re-vet the V2-capable amulet versions on Alice",
      aliceValidatorBackend.participantClient.topology.vetted_packages.propose_delta(
        aliceParticipantId,
        adds = v2CapableAmuletPackageIds.map(id =>
          VettedPackage(PackageId.assertFromString(id), None, None)
        ),
        store = TopologyStoreId.Synchronizer(decentralizedSynchronizerId),
      ),
    )(
      "sv1's participant observes Alice has the correct vetting state again",
      _ => {
        v2CapableAmuletPackageIds.toSet
          .subsetOf(vettedPackagesOnSv1View(aliceParticipantId).toSet) shouldBe true
        aliceLedgerApiAmuletVersionOnSv1View(aliceParty).exists(
          _ >= minV2AmuletVersion
        ) shouldBe true
      },
    )

  /** Confirm Alice's preferred amulet package version as resolved through sv1's
    * Ledger API, i.e. the exact path the ExpireRewardCouponV2Trigger uses to
    * determine the vetted version.
    */
  private def aliceLedgerApiAmuletVersionOnSv1View(
      aliceParty: PartyId
  )(implicit env: SpliceTestConsoleEnvironment): Option[PackageVersion] =
    sv1Backend.participantClient.ledger_api.interactive_submission
      .preferred_packages(
        Map(DarResources.amulet.latest.metadata.name -> Set(aliceParty)),
        Some(decentralizedSynchronizerId),
      )
      .packageReferences
      .headOption
      .map(ref => PackageVersion.assertFromString(ref.packageVersion))

  private def hiddenCouponsMetricValue(
      party: PartyId
  )(implicit env: SpliceTestConsoleEnvironment): Long = {
    val name = s"$MetricsPrefix.reward_coupons_v2.hidden_coupons"
    val attributes = Map("party" -> party.toProtoPrimitive)
    sv1Backend.metrics.list(name, attributes).get(name) match {
      case None => 0L
      case Some(_) =>
        sv1Backend.metrics.get(name, attributes).select[MetricValue.LongPoint].value.value
    }
  }

  private def oldestOpenRound(implicit env: SpliceTestConsoleEnvironment): Long = {
    val (openRounds, _) = sv1ScanBackend.getOpenAndIssuingMiningRounds()
    openRounds.map(_.contract.payload.round.number.toLong).min
  }

  private def assertOldestOpenRound(
      expected: Long
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    clue(s"Asserting oldest open round=$expected") {
      eventually() {
        val (openRounds, _) = sv1ScanBackend.getOpenAndIssuingMiningRounds()
        val roundNumbers = openRounds.map(_.contract.payload.round.number.toLong).sorted
        roundNumbers should have size 3
        roundNumbers.head shouldBe expected
      }
    }
  }

}
