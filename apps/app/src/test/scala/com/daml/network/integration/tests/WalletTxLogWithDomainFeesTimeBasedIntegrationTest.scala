package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.config.CNNodeConfigTransforms.updateAllValidatorConfigs_
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeIntegrationTestWithSharedEnvironment
import com.daml.network.util.WalletTestUtil
import com.daml.network.wallet.store.UserWalletTxLogParser.TxLogEntry as walletLogEntry
import com.digitalasset.canton.HasExecutionContext
import monocle.Monocle.toAppliedFocusOps

class WalletTxLogWithDomainFeesTimeBasedIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with WalletTxLogTestUtil {

  private val coinPrice = BigDecimal(1.25).setScale(10)

  override def environmentDefinition: CNNodeEnvironmentDefinition = {
    CNNodeEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      // Set a non-unit coin price to better test CC-USD conversion.
      .addConfigTransform((_, config) => CNNodeConfigTransforms.setCoinPrice(coinPrice)(config))
      .addConfigTransform((_, cnNodeConfig) =>
        updateAllValidatorConfigs_(validatorConfig =>
          validatorConfig
            .focus(_.automation.enableAutomaticValidatorTrafficBalanceTopup)
            .replace(true)
        )(cnNodeConfig)
      )
  }

  "A wallet" should {

    "handle domain fees that has been paid" in { implicit env =>
      clue("Create validator wallet with sufficient balance") {
        onboardWalletUser(bobWallet, bobValidator)
        bobValidatorWallet.tap(100)
      }
      clue("Purchase extra traffic and verify the transaction history") {
        eventually() {
          // Advance time till the automation kicks in and purchases extra traffic.
          advanceTimeByMinTopupInterval(bobValidator)
          bobValidatorWallet.balance().unlockedQty should be < BigDecimal(100)
          checkTxHistory(
            bobValidatorWallet,
            Seq[CheckTxHistoryFn](
              { case logEntry: walletLogEntry.Transfer =>
                // Payment of domain fees by validator to SVC
                logEntry.transactionSubtype shouldBe walletLogEntry.Transfer.ExtraTrafficPurchase
                inside(logEntry.sender) { case (sender, amount) =>
                  sender shouldBe bobValidator.getValidatorPartyId().toProtoPrimitive
                  amount should be < BigDecimal(0)
                }
                inside(logEntry.receivers) { case Seq((receiver, amount)) =>
                  receiver shouldBe svcParty.toProtoPrimitive
                  // domain fees paid is immediately burnt by SVC
                  amount shouldBe 0
                }
              },
              { case logEntry: walletLogEntry.BalanceChange =>
                logEntry.transactionSubtype shouldBe walletLogEntry.BalanceChange.Tap
              },
            ),
          )
        }
      }
    }

  }

}
