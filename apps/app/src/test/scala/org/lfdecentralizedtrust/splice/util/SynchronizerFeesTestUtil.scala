package org.lfdecentralizedtrust.splice.util

import com.daml.ledger.javaapi
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.sequencing.protocol.TrafficState
import com.digitalasset.canton.topology.{SynchronizerId, Member, PartyId}
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.decentralizedsynchronizer.MemberTraffic
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.IssuingMiningRound
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.buytrafficrequest.BuyTrafficRequest
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.install.amuletoperation.CO_BuyMemberTraffic
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.install.{
  AmuletOperation,
  AmuletOperationOutcome,
  WalletAppInstall,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.topupstate.ValidatorTopUpState
import org.lfdecentralizedtrust.splice.console.ValidatorAppBackendReference
import org.lfdecentralizedtrust.splice.environment.PackageIdResolver
import org.lfdecentralizedtrust.splice.environment.PackageIdResolver.HasAmuletRules
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  SpliceTestConsoleEnvironment,
  TestCommon,
}
import org.lfdecentralizedtrust.splice.wallet.admin.api.client.commands.HttpWalletAppClient.AmuletPosition
import org.lfdecentralizedtrust.splice.wallet.util.ExtraTrafficTopupParameters

import java.time.Instant
import java.util.Optional
import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

trait SynchronizerFeesTestUtil extends TestCommon {
  this: CommonAppInstanceReferences =>

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
      synchronizerId: SynchronizerId,
  )(implicit env: SpliceTestConsoleEnvironment): Long = {
    sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
      .filterJava(MemberTraffic.COMPANION)(
        sv1Backend.getDsoInfo().dsoParty,
        co =>
          co.data.synchronizerId == synchronizerId.toProtoPrimitive && co.data.memberId == memberId.toProtoPrimitive,
      )
      .map(_.data.totalPurchased.toLong)
      .sum
  }

  private def getOrCreateTopupStateCid(
      validatorApp: ValidatorAppBackendReference,
      memberId: Member,
      synchronizerId: SynchronizerId,
      packageIdResolverCustom: Option[PackageIdResolver],
  )(implicit env: SpliceTestConsoleEnvironment): ValidatorTopUpState.ContractId = {
    inside(listValidatorContracts(ValidatorTopUpState.COMPANION)(validatorApp)) {
      case Seq(topupState) => topupState.id
      case Seq() =>
        val validatorParty = validatorApp.getValidatorPartyId()
        val topupStateCreationCmd = ValidatorTopUpState.create(
          dsoParty.toProtoPrimitive,
          validatorParty.toProtoPrimitive,
          memberId.toProtoPrimitive,
          synchronizerId.toProtoPrimitive,
          validatorApp.config.domainMigrationId,
          Instant.ofEpochSecond(0),
        )
        val topupStateCid =
          validatorApp.participantClientWithAdminToken.ledger_api_extensions.commands
            .submitWithResult(
              userId = validatorApp.config.ledgerApiUser,
              actAs = Seq(validatorParty),
              readAs = Seq(validatorParty),
              update = topupStateCreationCmd,
              synchronizerId = Some(synchronizerId),
              packageIdResolverCustom = packageIdResolverCustom,
            )
            .contractId
        new ValidatorTopUpState.ContractId(topupStateCid.contractId)
    }
  }

  /** Perform a self top-up with the provided amulets.
    *
    * A self top-up is one where a (super-)validator purchases traffic for its own participant.
    * On DevNet, we auto-tap amulets for extra traffic purchases, so inputAmulets can be omitted for DevNet clusters.
    */
  def buyMemberTraffic(
      validatorApp: ValidatorAppBackendReference,
      trafficAmount: Long,
      ts: CantonTimestamp,
      inputAmulets: Seq[AmuletPosition] = Seq(),
      scanConnection: Option[HasAmuletRules] = None,
  )(implicit env: SpliceTestConsoleEnvironment): AmuletOperationOutcome = {
    implicit val ec: ExecutionContext = env.executionContext

    val packageIdResolverCustom = scanConnection match {
      case Some(scanConnection) =>
        Some(
          PackageIdResolver.inferFromAmuletRulesIfEnabled(
            false,
            env.environment.clock,
            scanConnection,
            loggerFactory,
          ): PackageIdResolver
        )
      case None => None
    }
    val memberId = validatorApp.participantClient.id
    val validatorParty = validatorApp.getValidatorPartyId()
    val synchronizerId =
      SynchronizerId.tryFromString(
        sv1ScanBackend.getAmuletConfigAsOf(ts).decentralizedSynchronizer.activeSynchronizer
      )
    val transferContext = sv1ScanBackend.getTransferContextWithInstances(ts)
    val topupStateCid =
      getOrCreateTopupStateCid(validatorApp, memberId, synchronizerId, packageIdResolverCustom)
    val walletInstall = inside(
      validatorApp.participantClientWithAdminToken.ledger_api_extensions.acs
        .filterJava(WalletAppInstall.COMPANION)(
          validatorParty,
          c => c.data.validatorParty == c.data.endUserParty,
        )
    ) { case Seq(install) => install }
    val executeBatchCmd = walletInstall.id.exerciseWalletAppInstall_ExecuteBatch(
      new splice.amuletrules.PaymentTransferContext(
        transferContext.amuletRules.contract.contractId,
        new splice.amuletrules.TransferContext(
          transferContext.latestOpenMiningRound.contract.contractId,
          Map.empty[Round, IssuingMiningRound.ContractId].asJava,
          Map.empty[String, splice.amulet.ValidatorRight.ContractId].asJava,
          None.toJava,
        ),
      ),
      inputAmulets
        .map(_.contract.contractId.contractId)
        .map[splice.amuletrules.TransferInput](cid =>
          new splice.amuletrules.transferinput.InputAmulet(new splice.amulet.Amulet.ContractId(cid))
        )
        .asJava,
      List[AmuletOperation](
        new CO_BuyMemberTraffic(
          trafficAmount,
          memberId.toProtoPrimitive,
          synchronizerId.toProtoPrimitive,
          validatorApp.config.domainMigrationId,
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
          disclosedContracts = DisclosedContracts
            .forTesting(
              transferContext.amuletRules,
              transferContext.latestOpenMiningRound,
            )
            .toLedgerApiDisclosedContracts,
          packageIdResolverCustom = packageIdResolverCustom,
        )
        .exerciseResult
        .outcomes
        .asScala
        .toSeq
    ) { case Seq(outcome) =>
      outcome
    }
  }

  def createBuyTrafficRequest(
      validatorApp: ValidatorAppBackendReference,
      buyer: PartyId,
      memberId: String,
      trafficAmount: Long,
      trackingId: String,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): BuyTrafficRequest.ContractId = {
    val now = env.environment.clock.now
    val synchronizerId =
      SynchronizerId.tryFromString(
        sv1ScanBackend.getAmuletConfigAsOf(now).decentralizedSynchronizer.activeSynchronizer
      )
    val validatorParty = validatorApp.getValidatorPartyId()
    val walletInstall = inside(
      validatorApp.participantClientWithAdminToken.ledger_api_extensions.acs
        .filterJava(WalletAppInstall.COMPANION)(
          validatorApp.getValidatorPartyId(),
          c => c.data.endUserParty == buyer.toProtoPrimitive,
        )
    ) { case Seq(install) => install }
    val cmd = walletInstall.id.exerciseWalletAppInstall_CreateBuyTrafficRequest(
      memberId,
      synchronizerId.toProtoPrimitive,
      validatorApp.config.domainMigrationId,
      trafficAmount,
      now.plus(java.time.Duration.ofMinutes(1)).toInstant,
      trackingId,
    )
    val result = validatorApp.participantClientWithAdminToken.ledger_api_extensions.commands
      .submitWithResult(
        validatorApp.config.ledgerApiUser,
        Seq(validatorParty),
        Seq(buyer),
        cmd,
        None,
        disclosedContracts = DisclosedContracts
          .forTesting(
            sv1ScanBackend.getAmuletRules(),
            sv1ScanBackend.getLatestOpenMiningRound(now),
          )
          .toLedgerApiDisclosedContracts,
      )
    result.exerciseResult.buyTrafficRequest
  }

  def getTopupParameters(validatorApp: ValidatorAppBackendReference, ts: CantonTimestamp)(implicit
      env: SpliceTestConsoleEnvironment
  ): ExtraTrafficTopupParameters = {
    ExtraTrafficTopupParameters(
      validatorApp.config.domains.global.buyExtraTraffic.targetThroughput,
      validatorApp.config.domains.global.buyExtraTraffic.minTopupInterval,
      sv1ScanBackend.getAmuletConfigAsOf(ts).decentralizedSynchronizer.fees.minTopupAmount,
      validatorApp.config.automation.topupTriggerPollingInterval_,
    )
  }

  def computeSynchronizerFees(trafficAmount: Long)(implicit
      env: SpliceTestConsoleEnvironment
  ): (BigDecimal, BigDecimal) = {
    val ts = env.environment.clock.now
    val extraTrafficPrice =
      sv1ScanBackend.getAmuletConfigAsOf(ts).decentralizedSynchronizer.fees.extraTrafficPrice
    val amuletPrice = sv1ScanBackend.getLatestOpenMiningRound(ts).contract.payload.amuletPrice
    SpliceUtil.synchronizerFees(trafficAmount, extraTrafficPrice, amuletPrice)
  }

  def getTrafficState(
      validatorApp: ValidatorAppBackendReference,
      synchronizerId: SynchronizerId,
  ): TrafficState = {
    validatorApp.participantClientWithAdminToken.traffic_control
      .traffic_state(synchronizerId)
  }

  def activeSynchronizerId(implicit env: SpliceTestConsoleEnvironment) =
    SynchronizerId.tryFromString(
      sv1ScanBackend
        .getAmuletConfigAsOf(env.environment.clock.now)
        .decentralizedSynchronizer
        .activeSynchronizer
    )
}
