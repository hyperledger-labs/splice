package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.wallet.install.coinoperationoutcome.COO_Error
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.util.{DomainFeesTestUtil, WalletTestUtil}
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeIntegrationTestWithSharedEnvironment
import com.daml.network.wallet.store.{
  BalanceChangeTxLogEntry,
  TransferTxLogEntry,
  TxLogEntry as walletLogEntry,
}
import com.digitalasset.canton.HasExecutionContext

class WalletTxLogWithDomainFeesIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with DomainFeesTestUtil
    with WalletTxLogTestUtil {

  private val coinPrice = BigDecimal(1.25).setScale(10)

  override def environmentDefinition: CNNodeEnvironmentDefinition = {
    CNNodeEnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      // Set a non-unit coin price to better test CC-USD conversion.
      .addConfigTransform((_, config) => CNNodeConfigTransforms.setCoinPrice(coinPrice)(config))
      // NOTE: automatic top-ups should be explicitly disabled for this test as currently written
      .withTrafficTopupsDisabled
  }

  "A wallet" should {

    "handle domain fees that have been paid (in a DevNet cluster)" in { implicit env =>
      val now = env.environment.clock.now
      val domainFeesConfig = sv1ScanBackend.getCoinConfigAsOf(now).globalDomain.fees
      val trafficAmount = Math.max(domainFeesConfig.minTopupAmount, 1_000_000L)
      val (_, totalCostCc) = computeDomainFees(trafficAmount, now)

      actAndCheck(
        "Fail to purchase extra traffic for SV1", {
          inside(
            buyMemberTraffic(sv1ValidatorBackend, domainFeesConfig.minTopupAmount - 1, now)
          ) { case coo: COO_Error =>
            coo.invalidTransferReasonValue.toString should startWith("ITR_InsufficientTopupAmount")
          }
        },
      )(
        "The failure was ignored by the tx log parser",
        _ => {
          // In case the error is unexpected, the wallet tx log parser will add a log entry of
          // of type Unknown (and also log an error).
          // See https://github.com/DACH-NY/canton-network-node/issues/7197
          checkTxHistory(
            sv1WalletClient,
            Seq[CheckTxHistoryFn](),
          )
        },
      )

      actAndCheck(
        "Purchase extra traffic for SV1", {
          buyMemberTraffic(sv1ValidatorBackend, trafficAmount, now)
        },
      )(
        "Check that an extra traffic purchase is registered in the SV1's tx history",
        _ => {
          checkTxHistory(
            sv1WalletClient,
            Seq[CheckTxHistoryFn](
              { case logEntry: TransferTxLogEntry =>
                // Payment of domain fees by validator to SVC
                logEntry.subtype.value shouldBe walletLogEntry.TransferTransactionSubtype.ExtraTrafficPurchase.toProto
                logEntry.sender.value.party shouldBe sv1ValidatorBackend
                  .getValidatorPartyId()
                  .toProtoPrimitive
                // amount actually paid will be more than totalCostCc due to fees
                logEntry.sender.value.amount should beWithin(
                  -totalCostCc - smallAmount,
                  -totalCostCc,
                )
                inside(logEntry.receivers) { case Seq(receiver) =>
                  receiver.party shouldBe svcParty.toProtoPrimitive
                  // domain fees paid is immediately burnt by SVC
                  receiver.amount shouldBe 0
                }
              },
              { case logEntry: BalanceChangeTxLogEntry =>
                logEntry.subtype.value shouldBe walletLogEntry.BalanceChangeTransactionSubtype.Tap.toProto
                logEntry.receiver shouldBe sv1ValidatorBackend
                  .getValidatorPartyId()
                  .toProtoPrimitive
                logEntry.amount should beWithin(totalCostCc, totalCostCc + smallAmount)
              },
            ),
          )
        },
      )

      actAndCheck(
        "Purchase extra traffic for aliceValidator", {
          buyMemberTraffic(aliceValidatorBackend, trafficAmount, now)
        },
      )(
        "Check that an extra traffic purchase is registered in aliceValidator's tx history",
        _ => {
          checkTxHistory(
            aliceValidatorWalletClient,
            Seq[CheckTxHistoryFn](
              { case logEntry: TransferTxLogEntry =>
                // Payment of domain fees by validator to SVC
                logEntry.subtype.value shouldBe walletLogEntry.TransferTransactionSubtype.ExtraTrafficPurchase.toProto
                logEntry.sender.value.party shouldBe aliceValidatorBackend
                  .getValidatorPartyId()
                  .toProtoPrimitive
                // amount actually paid will be more than totalCostCc due to fees
                logEntry.sender.value.amount should beWithin(
                  -totalCostCc - smallAmount,
                  -totalCostCc,
                )
                inside(logEntry.receivers) { case Seq(receiver) =>
                  receiver.party shouldBe svcParty.toProtoPrimitive
                  // domain fees paid is immediately burnt by SVC
                  receiver.amount shouldBe 0
                }
              },
              { case logEntry: BalanceChangeTxLogEntry =>
                logEntry.subtype.value shouldBe walletLogEntry.BalanceChangeTransactionSubtype.Tap.toProto
                logEntry.receiver shouldBe aliceValidatorBackend
                  .getValidatorPartyId()
                  .toProtoPrimitive
                logEntry.amount should beWithin(totalCostCc, totalCostCc + smallAmount)
              },
            ),
          )
        },
      )

    }
  }

}
