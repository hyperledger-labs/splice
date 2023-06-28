package com.daml.network.integration.tests

import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.codegen.java.cc.globaldomain.ValidatorTrafficCreationIntent
import com.daml.network.codegen.java.cn.wallet.install.{CoinOperation, WalletAppInstall}
import com.daml.network.codegen.java.cn.wallet.install.coinoperation.CO_BuyExtraTraffic
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.codegen.java.da.types as daTypes
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.util.DisclosedContracts
import com.digitalasset.canton.data.CantonTimestamp
import io.grpc.Status

import scala.math.BigDecimal.javaBigDecimal2bigDecimal
import com.daml.network.console.ValidatorAppBackendReference
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTestWithSharedEnvironment,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.WalletTestUtil
import com.daml.network.wallet.store.UserWalletTxLogParser.TxLogEntry as walletLogEntry
import com.digitalasset.canton.HasExecutionContext

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

class WalletTxLogWithDomainFeesIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with WalletTxLogTestUtil {

  private val coinPrice = BigDecimal(1.25).setScale(10)

  override def environmentDefinition: CNNodeEnvironmentDefinition = {
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .addConfigTransforms(CNNodeConfigTransforms.onlySv1)
      // Set a non-unit coin price to better test CC-USD conversion.
      .addConfigTransform((_, config) => CNNodeConfigTransforms.setCoinPrice(coinPrice)(config))
    // NOTE: automatic top-ups should be explicitly disabled for this test as currently written
    // .withTrafficTopupsEnabled
  }

  "A wallet" should {

    "handle domain fees that have been paid" in { implicit env =>
      onboardWalletUser(aliceWallet, aliceValidator)
      val now = env.environment.clock.now
      val domainFeesConfig = sv1Scan.getCoinConfigAsOf(now).globalDomain.fees
      val trafficAmount = Math.max(domainFeesConfig.minTopupAmount, 1_000_000L)
      val trafficPriceUsd = domainFeesConfig.extraTrafficPrice
      val coinPrice = sv1Scan.getLatestOpenMiningRound(now).contract.payload.coinPrice
      val trafficPriceCc = trafficPriceUsd / coinPrice
      val totalCostCc = trafficAmount / 1e6 * trafficPriceCc
      actAndCheck(
        "Purchase extra traffic", {
          buyInitialExtraTraffic(aliceValidator, trafficAmount, now)
        },
      )(
        "Check that an extra traffic purchase is registered in the transaction history (eventually)",
        _ => {
          checkTxHistory(
            aliceValidatorWallet,
            Seq[CheckTxHistoryFn](
              { case logEntry: walletLogEntry.Transfer =>
                // Payment of domain fees by validator to SVC
                logEntry.transactionSubtype shouldBe walletLogEntry.Transfer.ExtraTrafficPurchase
                inside(logEntry.sender) { case (sender, amount) =>
                  sender shouldBe aliceValidator.getValidatorPartyId().toProtoPrimitive
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
                logEntry.transactionSubtype shouldBe walletLogEntry.BalanceChange.Tap
                logEntry.amount should beWithin(totalCostCc, totalCostCc + smallAmount)
              },
            ),
          )
        },
      )
    }
  }

  private def buyInitialExtraTraffic(
      validatorApp: ValidatorAppBackendReference,
      trafficAmount: Long,
      ts: CantonTimestamp,
  )(implicit env: CNNodeTestConsoleEnvironment) = {
    val validatorParty = validatorApp.getValidatorPartyId()
    val transferContext = sv1Scan.getTransferContextWithInstances(ts)
    val intentCreationCmd = ValidatorTrafficCreationIntent.create(
      validatorApp.getValidatorPartyId().toProtoPrimitive,
      sv1Scan.getCoinConfigAsOf(ts).globalDomain.activeDomain,
    )
    val intentCreationResult =
      validatorApp.participantClientWithAdminToken.ledger_api_extensions.commands.submitWithResult(
        validatorApp.config.ledgerApiUser,
        Seq(validatorApp.getValidatorPartyId()),
        Seq(validatorApp.getValidatorPartyId()),
        intentCreationCmd,
      )
    val walletInstall = validatorApp.participantClientWithAdminToken.ledger_api_extensions.acs
      .filterJava(WalletAppInstall.COMPANION)(
        validatorParty,
        c => c.data.validatorParty == c.data.endUserParty,
      )
      .headOption
      .getOrElse(
        throw Status.NOT_FOUND
          .withDescription(s"No wallet app install contract found for ${validatorParty}")
          .asRuntimeException()
      )
    val executeBatchCmd = walletInstall.id.exerciseWalletAppInstall_ExecuteBatch(
      new v1.coin.PaymentTransferContext(
        transferContext.coinRules.contract.contractId.toInterface(v1.coin.CoinRules.INTERFACE),
        new v1.coin.TransferContext(
          transferContext.latestOpenMiningRound.contract.contractId
            .toInterface(v1.round.OpenMiningRound.INTERFACE),
          Map.empty[v1.round.Round, v1.round.IssuingMiningRound.ContractId].asJava,
          Map.empty[String, v1.coin.ValidatorRight.ContractId].asJava,
          None.toJava,
        ),
      ),
      List().asJava,
      List[CoinOperation](
        new CO_BuyExtraTraffic(
          trafficAmount,
          new daTypes.either.Left(
            new ValidatorTrafficCreationIntent.ContractId(
              intentCreationResult.contractId.contractId
            )
          ),
          new RelTime(1),
        )
      ).asJava,
    )
    validatorApp.participantClientWithAdminToken.ledger_api_extensions.commands.submitWithResult(
      validatorApp.config.ledgerApiUser,
      Seq(validatorApp.getValidatorPartyId()),
      Seq(validatorApp.getValidatorPartyId()),
      executeBatchCmd,
      disclosedContracts = DisclosedContracts(
        transferContext.coinRules,
        transferContext.latestOpenMiningRound,
      ).toLedgerApiDisclosedContracts,
    )
  }
}
