package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.util.{SvTestUtil, SynchronizerFeesTestUtil, WalletTestUtil}
import org.lfdecentralizedtrust.splice.wallet.automation.ReceiveFaucetCouponTrigger
import org.lfdecentralizedtrust.splice.wallet.store.{
  TransferTxLogEntry,
  TxLogEntry as walletLogEntry,
}
import com.digitalasset.canton.HasExecutionContext

class WalletTxLogWithSynchronizerFeesNoDevNetTimeBasedIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with SynchronizerFeesTestUtil
    with WalletTxLogTestUtil
    with SvTestUtil {

  private val amuletPrice = BigDecimal(1.25).setScale(10)

  override def environmentDefinition: EnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .addConfigTransform((_, config) => ConfigTransforms.noDevNet(config))
      // Set a non-unit amulet price to better test CC-USD conversion.
      .addConfigTransform((_, config) => ConfigTransforms.setAmuletPrice(amuletPrice)(config))
      // NOTE: automatic top-ups should be explicitly disabled for this test as currently written
      .withTrafficTopupsDisabled
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Validator)(
          _.withPausedTrigger[ReceiveFaucetCouponTrigger]
        )(config)
      )
  }

  "A wallet" should {

    "handle domain fees that have been paid (in a non DevNet cluster)" in { implicit env =>
      actAndCheck(
        "Advance enough rounds for SV1 to claim rewards", {
          (0 to 3).foreach { _ =>
            advanceTimeForRewardAutomationToRunForCurrentRound
            eventually() {
              ensureSvRewardCouponReceivedForCurrentRound(sv1ScanBackend, sv1WalletClient)
            }
            advanceRoundsToNextRoundOpening
          }
        },
      )(
        "Wait for SV rewards to be collected",
        _ => sv1WalletClient.balance().unlockedQty should be > BigDecimal(0),
      )

      val transferAmount = BigDecimal(100)
      actAndCheck(
        "Provide some initial amulet balance to aliceValidator",
        p2pTransfer(
          sv1WalletClient,
          aliceValidatorWalletClient,
          aliceValidatorBackend.getValidatorPartyId(),
          transferAmount,
        ),
      )(
        "Wait for aliceValidator's wallet balance to be updated",
        _ => {
          aliceValidatorWalletClient.balance().unlockedQty should be > BigDecimal(0)
        },
      )

      val now = env.environment.clock.now
      val domainFeesConfig = sv1ScanBackend.getAmuletConfigAsOf(now).decentralizedSynchronizer.fees
      val trafficAmount = Math.max(domainFeesConfig.minTopupAmount, 1_000_000L)
      val (_, totalCostCc) = computeSynchronizerFees(trafficAmount)

      actAndCheck(
        "Purchase extra traffic for SV1", {
          buyMemberTraffic(sv1ValidatorBackend, trafficAmount, now, sv1WalletClient.list().amulets)
        },
      )(
        "Check that an extra traffic purchase is registered in SV1's tx history",
        _ => {
          checkTxHistory(
            sv1WalletClient,
            Seq[CheckTxHistoryFn](
              { case logEntry: TransferTxLogEntry =>
                // Payment of domain fees by validator to DSO
                logEntry.subtype.value shouldBe walletLogEntry.TransferTransactionSubtype.ExtraTrafficPurchase.toProto
                logEntry.sender.value.party shouldBe sv1ValidatorBackend
                  .getValidatorPartyId()
                  .toProtoPrimitive
                // amount actually paid will be more than totalCostCc due to fees
                logEntry.sender.value.amount should beWithin(
                  -totalCostCc - smallAmount,
                  -totalCostCc,
                )
                logEntry.receivers shouldBe empty
              },
              { case logEntry: TransferTxLogEntry =>
                logEntry.subtype.value shouldBe walletLogEntry.TransferTransactionSubtype.P2PPaymentCompleted.toProto
                logEntry.sender.value.party shouldBe sv1ValidatorBackend
                  .getValidatorPartyId()
                  .toProtoPrimitive
                logEntry.sender.value.amount should beWithin(
                  -transferAmount - smallAmount,
                  -transferAmount,
                )
                inside(logEntry.receivers) { case Seq(receiver) =>
                  receiver.party shouldBe aliceValidatorBackend
                    .getValidatorPartyId()
                    .toProtoPrimitive
                  receiver.amount shouldBe transferAmount
                }
              },
            ) ++ (1 to 3).flatMap { _ =>
              Seq[CheckTxHistoryFn](
                { case logEntry: TransferTxLogEntry =>
                  logEntry.subtype.value shouldBe walletLogEntry.TransferTransactionSubtype.WalletAutomation.toProto
                  logEntry.receivers shouldBe empty
                  logEntry.sender.value.party shouldBe sv1ValidatorBackend
                    .getValidatorPartyId()
                    .toProtoPrimitive
                  logEntry.sender.value.amount should be > totalCostCc
                }
              )
            },
          )
        },
      )

      actAndCheck(
        "Purchase extra traffic for aliceValidator", {
          buyMemberTraffic(
            aliceValidatorBackend,
            trafficAmount,
            now,
            aliceValidatorWalletClient.list().amulets,
          )
        },
      )(
        "Check that an extra traffic purchase is registered in aliceValidator's tx history",
        _ => {
          checkTxHistory(
            aliceValidatorWalletClient,
            Seq[CheckTxHistoryFn](
              { case logEntry: TransferTxLogEntry =>
                // Payment of domain fees by validator to DSO
                logEntry.subtype.value shouldBe walletLogEntry.TransferTransactionSubtype.ExtraTrafficPurchase.toProto
                logEntry.sender.value.party shouldBe aliceValidatorBackend
                  .getValidatorPartyId()
                  .toProtoPrimitive
                // amount actually paid will be more than totalCostCc due to fees
                logEntry.sender.value.amount should beWithin(
                  -totalCostCc - smallAmount,
                  -totalCostCc,
                )
                logEntry.receivers shouldBe empty
              },
              { case logEntry: TransferTxLogEntry =>
                logEntry.subtype.value shouldBe walletLogEntry.TransferTransactionSubtype.P2PPaymentCompleted.toProto
                logEntry.sender.value.party shouldBe sv1ValidatorBackend
                  .getValidatorPartyId()
                  .toProtoPrimitive
                logEntry.sender.value.amount should beWithin(
                  -transferAmount - smallAmount,
                  -transferAmount,
                )
                inside(logEntry.receivers) { case Seq(receiver) =>
                  receiver.party shouldBe aliceValidatorBackend
                    .getValidatorPartyId()
                    .toProtoPrimitive
                  receiver.amount shouldBe transferAmount
                }
              },
            ),
          )
        },
      )

    }
  }

}
