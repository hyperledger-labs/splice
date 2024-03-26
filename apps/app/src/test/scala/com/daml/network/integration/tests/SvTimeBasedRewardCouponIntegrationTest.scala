package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.config.CNNodeConfigTransforms.{ConfigurableApp, updateAutomationConfig}
import com.daml.network.environment.BaseLedgerConnection
import com.daml.network.http.v0.definitions.TransactionHistoryRequest
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeIntegrationTestWithSharedEnvironment
import com.daml.network.store.Limit
import com.daml.network.sv.automation.singlesv.ReceiveSvRewardCouponTrigger
import com.daml.network.sv.util.SvUtil
import com.daml.network.util.CNNodeUtil.defaultIssuanceCurve
import com.daml.network.util.WalletTestUtil
import com.daml.network.validator.automation.ReceiveFaucetCouponTrigger
import com.daml.network.wallet.store.BalanceChangeTxLogEntry
import com.daml.network.wallet.store.TxLogEntry.BalanceChangeTransactionSubtype
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.topology.PartyId
import monocle.macros.syntax.lens.*

import scala.math.Ordering.Implicits.*

class SvTimeBasedRewardCouponIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with SvTimeBasedIntegrationTestUtil
    with WalletTestUtil
    with WalletTxLogTestUtil {

  override def environmentDefinition: CNNodeEnvironmentDefinition =
    CNNodeEnvironmentDefinition
      .simpleTopology4SvsWithSimTime(this.getClass.getSimpleName)
      .addConfigTransform((_, config) => {
        config
          .focus(_.svApps)
          .modify(_.map { case (name, svConfig) =>
            // sv4 gives part of its reward to alice
            val newConfig = if (name.unwrap == "sv4") {
              val aliceParticipant =
                CNNodeConfigTransforms
                  .getParticipantIds(config.parameters.clock)("alice_validator_user")
              val aliceLedgerApiUser =
                config.validatorApps(InstanceName.tryCreate("aliceValidator")).ledgerApiUser
              val alicePartyId = PartyId
                .tryFromProtoPrimitive(
                  s"${BaseLedgerConnection.sanitizeUserIdToPartyString(aliceLedgerApiUser)}::${aliceParticipant.split("::").last}"
                )
              svConfig
                .copy(extraBeneficiaries = Map(alicePartyId -> BigDecimal("33.33")))
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
          Seq.fill(expectedSize.toInt)(BigDecimal(SvUtil.DefaultFoundingNodeWeight))
        )
        aliceCoupons.map(_.payload.weight) should be(
          Seq.fill(expectedSize.toInt)(
            BigDecimal(SvUtil.DefaultFoundingNodeWeight) * BigDecimal("0.3333")
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
          Seq[CheckTxHistoryFn] { case b: BalanceChangeTxLogEntry =>
            b.subtype.value shouldBe BalanceChangeTransactionSubtype.SvRewardCollected.toProto
            b.receiver should be(sv1Party.toProtoPrimitive)
            b.amount should beWithin(BigDecimal(eachSvGetInRound0) - smallAmount, eachSvGetInRound0)
          },
        )
      }

      clue("The claimed reward appears in alice's wallet history") {
        checkTxHistory(
          aliceValidatorWalletClient,
          Seq[CheckTxHistoryFn] { case b: BalanceChangeTxLogEntry =>
            b.subtype.value shouldBe BalanceChangeTransactionSubtype.SvRewardCollected.toProto
            b.receiver should be(aliceValidatorParty.toProtoPrimitive)
            b.amount should beWithin(
              BigDecimal(expectedAliceAmount) - smallAmount,
              expectedAliceAmount,
            )
          },
        )
      }

      clue("The claims appear in the scan history") {
        eventually() {
          val txs = (for {
            tx <- sv1ScanBackend
              .listTransactions(
                None,
                TransactionHistoryRequest.SortOrder.Desc,
                Limit.MaxPageSize,
              )
              .filter(
                _.svRewardCollected
                  .map(_.amuletOwner)
                  .exists(
                    Seq(sv1Party.toProtoPrimitive, aliceValidatorParty.toProtoPrimitive).contains
                  )
              )
            reward <- tx.svRewardCollected
          } yield (reward.amuletOwner, reward.amuletAmount)).toMap
          BigDecimal(txs(sv1Party.toProtoPrimitive)) should beWithin(
            BigDecimal(eachSvGetInRound0) - smallAmount,
            eachSvGetInRound0,
          )
          BigDecimal(txs(aliceValidatorParty.toProtoPrimitive)) should beWithin(
            BigDecimal(expectedAliceAmount) - smallAmount,
            expectedAliceAmount,
          )
        }
      }
    }
  }

}
