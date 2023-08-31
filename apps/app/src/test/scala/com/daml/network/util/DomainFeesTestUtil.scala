package com.daml.network.util

import com.daml.ledger.javaapi
import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.codegen.java.cc.globaldomain.MemberTraffic
import com.daml.network.codegen.java.cn.wallet.install.coinoperation.CO_BuyMemberTraffic
import com.daml.network.codegen.java.cn.wallet.install.{
  CoinOperation,
  CoinOperationOutcome,
  WalletAppInstall,
}
import com.daml.network.codegen.java.cn.wallet.topupstate.ValidatorTopUpState
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.console.ValidatorAppBackendReference
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeTestCommon,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.validator.util.ExtraTrafficTopupParameters
import com.daml.network.wallet.admin.api.client.commands.HttpWalletAppClient.CoinPosition
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.sequencing.protocol.SequencedEventTrafficState
import com.digitalasset.canton.topology.{DomainId, Member}

import java.time.Instant
import java.util.Optional
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

  def getTotalPurchasedTraffic(
      memberId: Member,
      domainId: DomainId,
  )(implicit env: CNNodeTestConsoleEnvironment): Long = {
    sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
      .filterJava(MemberTraffic.COMPANION)(
        sv1Backend.getSvcInfo().svcParty,
        co => co.data.domainId == domainId.toProtoPrimitive && co.data.memberId == memberId.toProtoPrimitive,
      )
      .map(_.data.totalPurchased.toLong)
      .sum
  }

  private def getOrCreateTopupStateCid(
      validatorApp: ValidatorAppBackendReference,
      memberId: Member,
      domainId: DomainId,
  ): ValidatorTopUpState.ContractId = {
    inside(listValidatorContracts(ValidatorTopUpState.COMPANION)(validatorApp)) {
      case Seq(topupState) => topupState.id
      case Seq() =>
        val validatorParty = validatorApp.getValidatorPartyId()
        val topupStateCreationCmd = ValidatorTopUpState.create(
          validatorParty.toProtoPrimitive,
          memberId.toProtoPrimitive,
          domainId.toProtoPrimitive,
          Instant.ofEpochSecond(0),
        )
        val topupStateCid =
          validatorApp.participantClientWithAdminToken.ledger_api_extensions.commands
            .submitWithResult(
              userId = validatorApp.config.ledgerApiUser,
              actAs = Seq(validatorParty),
              readAs = Seq(validatorParty),
              update = topupStateCreationCmd,
              domainId = Some(domainId),
            )
            .contractId
        new ValidatorTopUpState.ContractId(topupStateCid.contractId)
    }
  }

  /** Perform a self top-up with the provided coins.
    *
    * A self top-up is one where a (super-)validator purchases traffic for its own participant.
    * On DevNet, we auto-tap coins for extra traffic purchases, so inputCoins can be omitted for DevNet clusters.
    */
  def buyMemberTraffic(
      validatorApp: ValidatorAppBackendReference,
      trafficAmount: Long,
      ts: CantonTimestamp,
      inputCoins: Seq[CoinPosition] = Seq(),
  )(implicit env: CNNodeTestConsoleEnvironment): CoinOperationOutcome = {
    val memberId = validatorApp.participantClient.id
    val validatorParty = validatorApp.getValidatorPartyId()
    val domainId =
      DomainId.tryFromString(sv1ScanBackend.getCoinConfigAsOf(ts).globalDomain.activeDomain)
    val transferContext = sv1ScanBackend.getTransferContextWithInstances(ts)
    val topupStateCid = getOrCreateTopupStateCid(validatorApp, memberId, domainId)
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
        new CO_BuyMemberTraffic(
          trafficAmount,
          memberId.toProtoPrimitive,
          domainId.toProtoPrimitive,
          new RelTime(1),
          Optional.of(topupStateCid),
        )
      ).asJava,
    )
    inside(
      validatorApp.participantClientWithAdminToken.ledger_api_extensions.commands
        .submitWithResult(
          validatorApp.config.ledgerApiUser,
          Seq(validatorParty),
          Seq(validatorParty),
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
    ) { case Seq(outcome) =>
      outcome
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

  def getTrafficState(
      validatorApp: ValidatorAppBackendReference,
      domainId: DomainId,
  ): SequencedEventTrafficState = {
    validatorApp.participantClientWithAdminToken.traffic_control
      .traffic_state(domainId)
      .trafficState
  }

}
