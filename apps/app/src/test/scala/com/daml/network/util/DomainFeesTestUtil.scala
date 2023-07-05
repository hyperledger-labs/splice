package com.daml.network.util

import com.daml.ledger.javaapi
import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.codegen.java.cc.globaldomain.{
  ValidatorTraffic,
  ValidatorTrafficCreationIntent,
}
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
import com.digitalasset.canton.topology.DomainId

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.math.BigDecimal.javaBigDecimal2bigDecimal

trait DomainFeesTestUtil extends CNNodeTestCommon {
  this: CommonCNNodeAppInstanceReferences =>

  private def listValidatorContracts[
      TC <: javaapi.data.codegen.Contract[TCid, T],
      TCid <: javaapi.data.codegen.ContractId[T],
      T <: javaapi.data.Template,
  ](
      templateCompanion: javaapi.data.codegen.ContractCompanion[TC, TCid, T]
  )(
      validatorApp: ValidatorAppBackendReference,
      filterPredicate: TC => Boolean = (_: TC) => true,
  ): Seq[TC] = {
    validatorApp.participantClientWithAdminToken.ledger_api_extensions.acs
      .filterJava(templateCompanion)(
        validatorApp.getValidatorPartyId(),
        predicate = filterPredicate,
      )
  }

  private def getOrCreateIntentOrTrafficCid(
      validatorApp: ValidatorAppBackendReference,
      ts: CantonTimestamp,
  )(implicit
      env: CNNodeTestConsoleEnvironment
  ): daTypes.Either[ValidatorTrafficCreationIntent.ContractId, ValidatorTraffic.ContractId] = {
    inside(listValidatorContracts(ValidatorTrafficCreationIntent.COMPANION)(validatorApp)) {
      case Seq(intent) => new daTypes.either.Left(intent.id)
      case Seq() =>
        inside(listValidatorContracts(ValidatorTraffic.COMPANION)(validatorApp)) {
          case Seq(traffic) => new daTypes.either.Right(traffic.id)
          case Seq() =>
            val validatorParty = validatorApp.getValidatorPartyId()
            val domainId =
              DomainId.tryFromString(sv1ScanBackend.getCoinConfigAsOf(ts).globalDomain.activeDomain)
            val intentCreationCmd = ValidatorTrafficCreationIntent.create(
              validatorApp.getValidatorPartyId().toProtoPrimitive,
              domainId.toProtoPrimitive,
            )
            val intentCid =
              validatorApp.participantClientWithAdminToken.ledger_api_extensions.commands
                .submitWithResult(
                  userId = validatorApp.config.ledgerApiUser,
                  actAs = Seq(validatorParty),
                  readAs = Seq(validatorParty),
                  update = intentCreationCmd,
                  domainId = Some(domainId),
                )
                .contractId
            new daTypes.either.Left(
              new ValidatorTrafficCreationIntent.ContractId(intentCid.contractId)
            )
        }
    }
  }

  /** Buy extra traffic for a given validator with the provided coins.
    */
  def buyExtraTraffic(
      validatorApp: ValidatorAppBackendReference,
      inputCoins: Seq[CoinPosition],
      trafficAmount: Long,
      ts: CantonTimestamp,
  )(implicit env: CNNodeTestConsoleEnvironment): Unit = {
    val validatorParty = validatorApp.getValidatorPartyId()
    val transferContext = sv1ScanBackend.getTransferContextWithInstances(ts)
    val intentOrTrafficCid = getOrCreateIntentOrTrafficCid(validatorApp, ts)
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
          intentOrTrafficCid,
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
