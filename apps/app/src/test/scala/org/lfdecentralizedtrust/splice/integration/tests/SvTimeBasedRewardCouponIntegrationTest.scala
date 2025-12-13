package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.SvRewardCoupon
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.http.v0.definitions.TransactionHistoryRequest
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTestWithSharedEnvironment,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.store.Limit
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.ReceiveSvRewardCouponTrigger
import org.lfdecentralizedtrust.splice.sv.config.{BeneficiaryConfig, SvOnboardingConfig}
import org.lfdecentralizedtrust.splice.sv.util.SvUtil
import org.lfdecentralizedtrust.splice.util.SpliceUtil.defaultIssuanceCurve
import org.lfdecentralizedtrust.splice.util.{TriggerTestUtil, WalletTestUtil}
import org.lfdecentralizedtrust.splice.validator.automation.{
  ValidatorPackageVettingTrigger,
}
import org.lfdecentralizedtrust.splice.wallet.automation.{
  CollectRewardsAndMergeAmuletsTrigger,
  ReceiveFaucetCouponTrigger,
}
import org.lfdecentralizedtrust.splice.wallet.store.TransferTxLogEntry
import org.lfdecentralizedtrust.splice.wallet.store.TxLogEntry.TransferTransactionSubtype
import com.digitalasset.canton.config.RequireTypes.NonNegativeLong
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.topology.{ForceFlag, ForceFlags, PartyId}
import com.digitalasset.canton.topology.transaction.VettedPackage
import com.digitalasset.daml.lf.data.Ref.PackageId
import monocle.macros.syntax.lens.*
import org.lfdecentralizedtrust.splice.environment.DarResources
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyTransactionType.AuthorizedState
import org.lfdecentralizedtrust.splice.integration.plugins.TokenStandardCliSanityCheckPlugin
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingConfig.FoundDso
import org.slf4j.event.Level

import scala.math.Ordering.Implicits.*

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_9
class SvTimeBasedRewardCouponIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with SvTimeBasedIntegrationTestUtil
    with WalletTestUtil
    with WalletTxLogTestUtil
    with TriggerTestUtil {

  // unvetting causes fallback to older version which doesn't implement token-standard.
  override protected lazy val tokenStandardCliBehavior
      : TokenStandardCliSanityCheckPlugin.OutputCreateArchiveBehavior =
    TokenStandardCliSanityCheckPlugin.OutputCreateArchiveBehavior.IgnoreAll

  override def environmentDefinition: EnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4SvsWithSimTime(this.getClass.getSimpleName)
      .addConfigTransform((_, config) => {
        config
          .focus(_.svApps)
          .modify(_.map { case (name, svConfig) =>
            // sv4 gives part of its reward to aliceValidator
            val newConfig = if (name.unwrap == "sv4") {
              val aliceParticipant =
                ConfigTransforms
                  .getParticipantIds(config.parameters.clock)("alice_validator_user")
              val aliceValidatorPartyHint =
                config
                  .validatorApps(InstanceName.tryCreate("aliceValidator"))
                  .validatorPartyHint
                  .value
              val alicePartyId = PartyId
                .tryFromProtoPrimitive(
                  s"$aliceValidatorPartyHint::${aliceParticipant.split("::").last}"
                )
              svConfig
                .copy(extraBeneficiaries =
                  Seq(BeneficiaryConfig(alicePartyId, NonNegativeLong.tryCreate(3333L)))
                )
            } else svConfig

            name -> newConfig
          })
      })
      .addConfigTransforms((_, config) =>
        // makes balance changes easier to compare
        updateAutomationConfig(ConfigurableApp.Validator)(
          _.withPausedTrigger[ReceiveFaucetCouponTrigger]
        )(config)
      )
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
          // needs to be disabled until alice has vetted the latest packages (to be checked at the beginning of the test)
          _.withPausedTrigger[ReceiveSvRewardCouponTrigger]
        )(config)
      )
      .withTrafficTopupsDisabled

  private val feesUpperBoundCC = walletUsdToAmulet(smallAmount)

  "SVs" should {

    "receive and claim SvRewardCoupons" in { implicit env =>
      // ensure alice has vetted the latest packages
      val expectedVettedPackages = ReceiveSvRewardCouponTrigger.svLatestVettedPackages(
        initialPackageConfig.toPackageConfig
      )
      eventually() {
        val vettedByAlice =
          aliceValidatorBackend.participantClientWithAdminToken.topology.vetted_packages
            .list(Some(TopologyStoreId.Synchronizer(decentralizedSynchronizerId)))
            .flatMap(
              _.item.packages.map(_.packageId)
            )
        forAll(expectedVettedPackages) { expectedPackage =>
          vettedByAlice should contain(expectedPackage)
        }
      }
      // now that we know that alice has vetted the latest packages, we can resume the trigger for the rest of the test
      Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend).foreach(
        _.dsoAutomation
          .trigger[ReceiveSvRewardCouponTrigger]
          .resume()
      )

      advanceTimeForRewardAutomationToRunForCurrentRound

      val openRounds = eventually() {
        val openRounds = sv1ScanBackend
          .getOpenAndIssuingMiningRounds()
          ._1
          .filter(_.payload.opensAt <= env.environment.clock.now.toInstant)
        openRounds should not be empty
        openRounds
      }
      eventually() {
        val expectedSize = openRounds.size.toLong
        val sv1Coupons = sv1WalletClient.listSvRewardCoupons()
        val aliceCoupons = aliceValidatorWalletClient.listSvRewardCoupons()
        sv1Coupons should have size expectedSize
        aliceCoupons should have size expectedSize
        sv1Coupons.map(_.payload.weight) should be(
          Seq.fill(expectedSize.toInt)(BigDecimal(SvUtil.DefaultSV1Weight))
        )
        aliceCoupons.map(_.payload.weight) should be(
          Seq.fill(expectedSize.toInt)(
            BigDecimal(SvUtil.DefaultSV1Weight) * BigDecimal("0.3333")
          )
        )
        // SV4 has no wallet
      }

      // prevent other coupons from being received so that we can verify when the previous ones have been claimed.
      Seq(sv1Backend, sv4Backend).foreach { sv =>
        sv.dsoAutomation
          .trigger[ReceiveSvRewardCouponTrigger]
          .pause()
          .futureValue
      }

      // advance enough rounds to claim one SvRewardCoupon
      advanceRoundsToNextRoundOpening
      advanceRoundsToNextRoundOpening
      eventually() {
        val expectedSize = (openRounds.size - 1).toLong
        sv1WalletClient.listSvRewardCoupons() should have size expectedSize
        aliceValidatorWalletClient.listSvRewardCoupons() should have size expectedSize
      }

      val eachSvGetInRound0 =
        computeSvRewardInRound0(defaultIssuanceCurve.initialValue, defaultTickDuration, svs.size)
      val sv1Party = sv1Backend.getDsoInfo().svParty
      val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()
      val expectedAliceAmount = eachSvGetInRound0.multiply(new java.math.BigDecimal("0.3333"))

      eventually() {
        checkWallet(
          sv1Party,
          sv1WalletClient,
          Seq(BigDecimal(eachSvGetInRound0) - feesUpperBoundCC -> eachSvGetInRound0),
        )

        checkWallet(
          aliceValidatorParty,
          aliceValidatorWalletClient,
          Seq(BigDecimal(expectedAliceAmount) - feesUpperBoundCC -> expectedAliceAmount),
        )
      }

      clue("The claimed reward appear in SV1's wallet history") {
        checkTxHistory(
          sv1WalletClient,
          Seq[CheckTxHistoryFn] { case b: TransferTxLogEntry =>
            b.subtype.value shouldBe TransferTransactionSubtype.WalletAutomation.toProto
            b.receivers shouldBe empty
            b.sender.value.party should be(sv1Party.toProtoPrimitive)
            b.sender.value.amount should beWithin(
              BigDecimal(eachSvGetInRound0) - feesUpperBoundCC,
              BigDecimal(eachSvGetInRound0),
            )
          },
        )
      }

      clue("The claimed reward appears in alice's wallet history") {
        checkTxHistory(
          aliceValidatorWalletClient,
          Seq[CheckTxHistoryFn] { case b: TransferTxLogEntry =>
            b.subtype.value shouldBe TransferTransactionSubtype.WalletAutomation.toProto
            b.receivers shouldBe empty
            b.sender.value.party should be(aliceValidatorParty.toProtoPrimitive)
            b.sender.value.amount should beWithin(
              BigDecimal(expectedAliceAmount) - feesUpperBoundCC,
              BigDecimal(expectedAliceAmount),
            )
          },
        )
      }

      clue("The claims appear in the scan history") {
        eventually() {
          val txs = sv1ScanBackend
            .listTransactions(
              None,
              TransactionHistoryRequest.SortOrder.Desc,
              Limit.MaxPageSize,
            )
            .flatMap(_.transfer)
            .filter(tf =>
              tf.sender.inputSvRewardAmount.nonEmpty &&
                Seq(sv1Party.toProtoPrimitive, aliceValidatorParty.toProtoPrimitive)
                  .contains(tf.sender.party)
            )
            .map(tf => tf.sender.party -> tf.sender.inputSvRewardAmount.value)
            .toMap
          BigDecimal(txs(sv1Party.toProtoPrimitive)) should beWithin(
            // The expected SV reward calculated here does not match exactly the reward calculated in daml,
            // presumably because of rounding differences in the reward calculation.
            BigDecimal(eachSvGetInRound0) - 0.001,
            BigDecimal(eachSvGetInRound0) + 0.001,
          )
          BigDecimal(txs(aliceValidatorParty.toProtoPrimitive)) should beWithin(
            // The expected SV reward calculated here does not match exactly the reward calculated in daml,
            // presumably because of rounding differences in the reward calculation.
            BigDecimal(expectedAliceAmount) - 0.001,
            BigDecimal(expectedAliceAmount) + 0.001,
          )
        }
      }

      clue("The claims appear in the wallet history") {
        eventually() {
          val txs = withoutDevNetTopups(
            sv1WalletClient
              .listTransactions(
                None,
                Limit.MaxPageSize,
              )
          )
            .collect {
              case b: TransferTxLogEntry
                  if b.subtype.value == TransferTransactionSubtype.WalletAutomation.toProto =>
                b
            }
            .filter(tf =>
              tf.svRewardsUsed.value > 0 &&
                Seq(sv1Party.toProtoPrimitive, aliceValidatorParty.toProtoPrimitive)
                  .contains(tf.sender.value.party)
            )
            .map(tf => tf.sender.value.party -> tf.svRewardsUsed.value)
            .toMap
          txs(sv1Party.toProtoPrimitive) should beWithin(
            // The expected SV reward calculated here does not match exactly the reward calculated in daml,
            // presumably because of rounding differences in the reward calculation.
            BigDecimal(eachSvGetInRound0) - 0.001,
            BigDecimal(eachSvGetInRound0) + 0.001,
          )
        }
      }

      Seq(sv1Backend, sv4Backend).foreach { sv =>
        sv.dsoAutomation
          .trigger[ReceiveSvRewardCouponTrigger]
          .resume()
      }

    }
  }

  "filter out beneficiaries that did not vet the latest packages" in { implicit env =>
    val dso = sv1Backend.getDsoInfo().dsoParty

    def getSvRewardCoupon(party: String) =
      sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
        .filterJava(SvRewardCoupon.COMPANION)(
          dso,
          _ => true,
        )
        .filter(_.data.beneficiary.contains(party))

    val latestAmuletPackageId =
      DarResources.amulet.getPackageIdWithVersion(initialPackageConfig.amuletVersion).value
    val aliceParticipantId =
      aliceValidatorBackend.appState.participantAdminConnection.getParticipantId().futureValue

    val sv4RewardCouponTrigger = sv4Backend.dsoAutomation.trigger[ReceiveSvRewardCouponTrigger]

    clue("Pause SV4's SvRewardCouponTrigger") {
      sv4RewardCouponTrigger.pause().futureValue
      // TODO(#2380) Remove the pause once the scan txlog script is fixed.
    }
    clue("Pause alice reward collection trigger") {
      aliceValidatorBackend
        .userWalletAutomation(aliceValidatorWalletClient.config.ledgerApiUser)
        .futureValue
        .trigger[CollectRewardsAndMergeAmuletsTrigger]
        .pause()
        .futureValue
    }

    clue("Pause alice vetting trigger") {
      aliceValidatorBackend.validatorAutomation
        .trigger[ValidatorPackageVettingTrigger]
        .pause()
        .futureValue
    }
    actAndCheck(
      s"Unvet the latest amulet package on Alice's participant with package id: $latestAmuletPackageId",
      aliceValidatorBackend.participantClient.topology.vetted_packages.propose_delta(
        aliceParticipantId,
        removes = Seq(PackageId.assertFromString(latestAmuletPackageId)),
        force = ForceFlags(
          ForceFlag.AllowUnvettedDependencies
        ),
        store = TopologyStoreId.Synchronizer(decentralizedSynchronizerId),
      ),
    )(
      "Alice's participant has unvetted the latest amulet package, and SV4 is aware of that",
      _ => {
        sv4ValidatorBackend.appState.participantAdminConnection
          .listVettedPackages(aliceParticipantId, decentralizedSynchronizerId, AuthorizedState)
          .futureValue
          .flatMap(_.mapping.packages.map(_.packageId)) should not contain latestAmuletPackageId
      },
    )

    loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
      within = {

        eventually() {
          clue("No SvRewardCoupon should be issued to Alice's participant") {
            advanceRoundsToNextRoundOpening
            sv4RewardCouponTrigger.runOnce().futureValue
            val openRounds = eventually() {
              val openRounds = sv1ScanBackend
                .getOpenAndIssuingMiningRounds()
                ._1
                .filter(_.payload.opensAt <= env.environment.clock.now.toInstant)
              openRounds should not be empty
              openRounds
            }
            val aliceRewards = getSvRewardCoupon("alice")
            val sv1Rewards = getSvRewardCoupon("digital-asset-2")
            sv1Rewards.map(_.data.round.number) should contain atLeastOneElementOf openRounds.map(
              _.payload.round.number
            )
            aliceRewards.map(_.data.round.number) should contain noElementsOf openRounds.map(
              _.payload.round.number
            )
          }
        }
      },
      lines =>
        forAtLeast(1, lines) {
          _.message should include("Beneficiaries did not vet the latest packages")
        },
    )

    actAndCheck(
      s"Vet back the latest amulet package on Alice's participant with package id: $latestAmuletPackageId", {
        aliceValidatorBackend.participantClient.topology.vetted_packages.propose_delta(
          aliceParticipantId,
          adds = Seq(VettedPackage(PackageId.assertFromString(latestAmuletPackageId), None, None)),
          store = TopologyStoreId.Synchronizer(decentralizedSynchronizerId),
        )
      },
    )(
      "Alice's participant has vetted the latest amulet package",
      _ => {
        aliceValidatorBackend.appState.participantAdminConnection
          .listVettedPackages(aliceParticipantId, decentralizedSynchronizerId, AuthorizedState)
          .futureValue
          .flatMap(_.mapping.packages.map(_.packageId)) should contain(latestAmuletPackageId)
      },
    )

  }

  private def initialPackageConfig(implicit
      env: SpliceTestConsoleEnvironment
  ): SvOnboardingConfig.InitialPackageConfig = {
    inside(sv1Backend.config.onboarding.value) { case founder: FoundDso =>
      founder.initialPackageConfig
    }
  }

}
