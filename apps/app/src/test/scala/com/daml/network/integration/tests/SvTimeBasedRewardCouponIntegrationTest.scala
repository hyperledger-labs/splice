package com.daml.network.integration.tests

import com.daml.network.config.ConfigTransforms
import com.daml.network.config.ConfigTransforms.{ConfigurableApp, updateAutomationConfig}
import com.daml.network.http.v0.definitions.TransactionHistoryRequest
import com.daml.network.integration.EnvironmentDefinition
import com.daml.network.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import com.daml.network.store.Limit
import com.daml.network.sv.automation.singlesv.ReceiveSvRewardCouponTrigger
import com.daml.network.sv.config.BeneficiaryConfig
import com.daml.network.sv.util.SvUtil
import com.daml.network.util.SpliceUtil.defaultIssuanceCurve
import com.daml.network.util.WalletTestUtil
import com.daml.network.validator.automation.ReceiveFaucetCouponTrigger
import com.daml.network.wallet.store.TransferTxLogEntry
import com.daml.network.wallet.store.TxLogEntry.TransferTransactionSubtype
import com.digitalasset.canton.config.RequireTypes.NonNegativeLong
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.topology.PartyId
import monocle.macros.syntax.lens.*

import scala.math.Ordering.Implicits.*

class SvTimeBasedRewardCouponIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with SvTimeBasedIntegrationTestUtil
    with WalletTestUtil
    with WalletTxLogTestUtil {

  override def environmentDefinition: EnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4SvsWithSimTime(this.getClass.getSimpleName)
      .addConfigTransform((_, config) => {
        config
          .focus(_.svApps)
          .modify(_.map { case (name, svConfig) =>
            // sv4 gives part of its reward to alice
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
      .withTrafficTopupsDisabled

  private val feesUpperBoundCC = walletUsdToAmulet(smallAmount)

  "SVs" should {

    "receive and claim SvRewardCoupons" in { implicit env =>
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
      advanceRoundsByOneTick
      advanceRoundsByOneTick
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
          val txs = sv1WalletClient
            .listTransactions(
              None,
              Limit.MaxPageSize,
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
    }
  }

}
