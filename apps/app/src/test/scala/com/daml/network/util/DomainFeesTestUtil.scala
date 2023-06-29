package com.daml.network.util

import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.codegen.java.cc.globaldomain.ValidatorTraffic
import com.daml.network.codegen.java.cn.wallet.install.coinoperation.CO_BuyExtraTraffic
import com.daml.network.codegen.java.cn.wallet.install.coinoperationoutcome.COO_BuyExtraTraffic
import com.daml.network.codegen.java.cn.wallet.install.{CoinOperation, WalletAppInstall}
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.codegen.java.da.types as daTypes
import com.daml.network.console.ValidatorAppBackendReference
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeTestCommon,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.validator.util.ExtraTrafficTopupParameters
import com.daml.network.wallet.admin.api.client.commands.HttpWalletAppClient.CoinPosition
import com.digitalasset.canton.data.CantonTimestamp

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.math.BigDecimal.javaBigDecimal2bigDecimal

trait DomainFeesTestUtil extends CNNodeTestCommon {
  this: CommonCNNodeAppInstanceReferences =>

  def getValidatorTraffic(
      validatorApp: ValidatorAppBackendReference
  )(implicit env: CNNodeTestConsoleEnvironment): ValidatorTraffic.Contract = {
    val validatorParty = validatorApp.getValidatorPartyId()
    inside(
      sv1ValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
        .filterJava(ValidatorTraffic.COMPANION)(
          validatorParty,
          _.data.validator == validatorParty.toProtoPrimitive,
        )
    ) { case Seq(traffic) => traffic }
  }

  /** Buy extra traffic for a given validator with the provided coins.
    *
    * Currently assumes that a previous ValidatorTraffic contract already exists.
    * For SVs, this will always be true because they are granted some traffic at
    * the time of founding or joining the SVC.
    */
  def buyExtraTraffic(
      validatorApp: ValidatorAppBackendReference,
      inputCoins: Seq[CoinPosition],
      trafficAmount: Long,
      ts: CantonTimestamp,
  )(implicit env: CNNodeTestConsoleEnvironment): Unit = {
    val validatorParty = validatorApp.getValidatorPartyId()
    val transferContext = sv1ScanBackend.getTransferContextWithInstances(ts)
    val prevTraffic = getValidatorTraffic(validatorApp)
    val walletInstall = inside(
      validatorApp.participantClientWithAdminToken.ledger_api_extensions.acs
        .filterJava(WalletAppInstall.COMPANION)(
          validatorParty,
          c => c.data.validatorParty == c.data.endUserParty,
        )
    ) { case Seq(install) => install }
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
      inputCoins
        .map(_.contract.contractId.contractId)
        .map[v1.coin.TransferInput](cid =>
          new v1.coin.transferinput.InputCoin(new v1.coin.Coin.ContractId(cid))
        )
        .asJava,
      List[CoinOperation](
        new CO_BuyExtraTraffic(
          trafficAmount,
          new daTypes.either.Right(prevTraffic.id),
          new RelTime(1),
        )
      ).asJava,
    )
    inside(
      validatorApp.participantClientWithAdminToken.ledger_api_extensions.commands
        .submitWithResult(
          validatorApp.config.ledgerApiUser,
          Seq(validatorApp.getValidatorPartyId()),
          Seq(validatorApp.getValidatorPartyId()),
          executeBatchCmd,
          disclosedContracts = DisclosedContracts(
            transferContext.coinRules,
            transferContext.latestOpenMiningRound,
          ).toLedgerApiDisclosedContracts,
        )
        .exerciseResult
        .outcomes
        .asScala
        .toSeq
    ) { case Seq(_: COO_BuyExtraTraffic) =>
      ()
    }
  }

  def getTopupParameters(validatorApp: ValidatorAppBackendReference, ts: CantonTimestamp)(implicit
      env: CNNodeTestConsoleEnvironment
  ): ExtraTrafficTopupParameters = {
    ExtraTrafficTopupParameters(
      sv1ScanBackend.getCoinConfigAsOf(ts).globalDomain.fees,
      validatorApp.config.domains.global.buyExtraTraffic,
      validatorApp.config.automation.pollingInterval,
    )
  }

  def computeDomainFees(trafficAmount: Long, ts: CantonTimestamp)(implicit
      env: CNNodeTestConsoleEnvironment
  ): (BigDecimal, BigDecimal) = {
    val trafficPriceUsd = sv1ScanBackend.getCoinConfigAsOf(ts).globalDomain.fees.extraTrafficPrice
    val totalCostUsd = BigDecimal(trafficAmount) / 1e6 * trafficPriceUsd
    val coinPrice = sv1ScanBackend.getLatestOpenMiningRound(ts).contract.payload.coinPrice
    val totalCostCc = totalCostUsd / coinPrice
    (totalCostUsd, totalCostCc)
  }
}
