package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeIntegrationTestWithSharedEnvironment
import com.daml.network.util.{DomainFeesTestUtil, WalletTestUtil}
import com.daml.network.wallet.store.UserWalletTxLogParser.TxLogEntry as walletLogEntry
import com.digitalasset.canton.HasExecutionContext

class WalletTxLogWithDomainFeesNoDevNetTimeBasedIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with DomainFeesTestUtil
    with WalletTxLogTestUtil {

  private val coinPrice = BigDecimal(1.25).setScale(10)

  override def environmentDefinition: CNNodeEnvironmentDefinition = {
    CNNodeEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      .addConfigTransforms(CNNodeConfigTransforms.onlySv1)
      .addConfigTransform((_, config) => CNNodeConfigTransforms.noDevNet(config))
      // Set a non-unit coin price to better test CC-USD conversion.
      .addConfigTransform((_, config) => CNNodeConfigTransforms.setCoinPrice(coinPrice)(config))
      // NOTE: automatic top-ups should be explicitly disabled for this test as currently written
      .withTrafficTopupsDisabled
  }

  "A wallet" should {

    "handle domain fees that have been paid (in a non DevNet cluster)" in { implicit env =>
      actAndCheck(
        "Advance rounds",
        advanceRoundsByOneTick,
      )(
        "Wait for SV rewards to be collected",
        _ => sv1WalletClient.balance().unlockedQty should be > BigDecimal(0),
      )

      val now = env.environment.clock.now
      val domainFeesConfig = sv1ScanBackend.getCoinConfigAsOf(now).globalDomain.fees
      val trafficAmount = Math.max(domainFeesConfig.minTopupAmount, 1_000_000L)
      val (_, totalCostCc) = computeDomainFees(trafficAmount, now)

      actAndCheck(
        "Purchase extra traffic", {
          buyExtraTraffic(sv1ValidatorBackend, sv1WalletClient.list().coins, trafficAmount, now)
        },
      )(
        "Check that an extra traffic purchase is registered in the transaction history",
        _ => {
          checkTxHistory(
            sv1WalletClient,
            Seq[CheckTxHistoryFn](
              { case logEntry: walletLogEntry.Transfer =>
                // Payment of domain fees by validator to SVC
                logEntry.transactionSubtype shouldBe walletLogEntry.Transfer.ExtraTrafficPurchase
                inside(logEntry.sender) { case (sender, amount) =>
                  sender shouldBe sv1ValidatorBackend.getValidatorPartyId().toProtoPrimitive
                  // amount actually paid will be more than totalCostCc due to fees
                  amount should beWithin(-totalCostCc - smallAmount, -totalCostCc)
                }
                inside(logEntry.receivers) { case Seq((receiver, amount)) =>
                  receiver shouldBe svcParty.toProtoPrimitive
                  // domain fees paid is immediately burnt by SVC
                  amount shouldBe 0
                }
              },
              { case logEntry: walletLogEntry.BalanceChange =>
                logEntry.transactionSubtype shouldBe walletLogEntry.BalanceChange.SvRewardCollected
                logEntry.receiver shouldBe sv1ValidatorBackend
                  .getValidatorPartyId()
                  .toProtoPrimitive
                logEntry.amount should be > totalCostCc
              },
            ),
          )
        },
      )
    }
  }

}
