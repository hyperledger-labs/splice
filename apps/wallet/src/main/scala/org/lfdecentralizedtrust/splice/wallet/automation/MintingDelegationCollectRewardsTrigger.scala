// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.automation

import org.lfdecentralizedtrust.splice.automation.{PollingTrigger, TriggerContext}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.{
  PaymentTransferContext,
  TransferContext,
  TransferInput,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.transferinput.{
  InputAmulet,
  InputAppRewardCoupon,
  InputUnclaimedActivityRecord,
  InputValidatorLivenessActivityRecord,
  InputValidatorRewardCoupon,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{
  AppRewardCoupon,
  Amulet,
  UnclaimedActivityRecord,
  ValidatorRewardCoupon,
  ValidatorRight,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.{
  IssuingMiningRound,
  OpenMiningRound,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense.ValidatorLivenessActivityRecord
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.mintingdelegation.MintingDelegation
import org.lfdecentralizedtrust.splice.environment.{RetryFor, SpliceLedgerConnection}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection
import org.lfdecentralizedtrust.splice.store.Limit
import org.lfdecentralizedtrust.splice.util.{
  AssignedContract,
  Contract,
  ContractWithState,
  SpliceUtil,
}
import org.lfdecentralizedtrust.splice.wallet.store.ExternalPartyWalletStore
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import org.apache.pekko.stream.Materializer
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

// Although this trigger is part of external-party automation
// The work performed here is done as the validatorParty (ie as the delegate of MintingDelegation)
class MintingDelegationCollectRewardsTrigger(
    override protected val context: TriggerContext,
    store: ExternalPartyWalletStore,
    scanConnection: BftScanConnection,
    spliceLedgerConnection: SpliceLedgerConnection,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
    materializer: Materializer,
) extends PollingTrigger {

  private def validatorParty = store.key.validatorParty
  private def externalParty = store.key.externalParty

  override protected def extraMetricLabels = Seq("party" -> externalParty.toString)

  override def isRewardOperationTrigger: Boolean = true

  override def performWorkIfAvailable()(implicit tc: TraceContext): Future[Boolean] = {
    context.retryProvider.retry(
      RetryFor.Automation,
      "collect_rewards_as_delegate",
      "Collect rewards as delegate for the minting delegation",
      collectRewardsAsDelegate(),
      logger,
    )
  }

  private def collectRewardsAsDelegate()(implicit tc: TraceContext): Future[Boolean] = {
    for {
      delegations <- store.multiDomainAcsStore.listContracts(
        MintingDelegation.COMPANION,
        Limit.DefaultLimit,
      )

      // We expect either none, or only one active delegation per beneficiary
      result <- delegations.flatMap(_.toAssignedContract).headOption match {
        case Some(delegation) => processDelegation(delegation)
        case None => Future.successful(false)
      }
    } yield result
  }

  private def processDelegation(
      assignedDelegation: AssignedContract[MintingDelegation.ContractId, MintingDelegation]
  )(implicit tc: TraceContext): Future[Boolean] = {
    val delegation = assignedDelegation.contract
    val now = context.clock.now.toInstant
    if (delegation.payload.expiresAt.isBefore(now)) {
      logger.info(
        s"Skipping reward collection for expired minting delegation (expired at ${delegation.payload.expiresAt})"
      )
      Future.successful(false)
    } else {
      for {
        (openRound, openIssuingRounds, issuingRoundsMap, amuletRules) <- fetchDataFromScan()
        couponsData <- fetchCouponsData(issuingRoundsMap)
        amulets <- store.listAmulets()
        validatorRightOpt <- store.lookupValidatorRight()
        result <- performMintIfNeeded(
          delegation,
          openRound,
          openIssuingRounds,
          amuletRules,
          couponsData,
          amulets,
          validatorRightOpt,
        )
      } yield result
    }
  }

  private def performMintIfNeeded(
      delegation: Contract[
        MintingDelegation.ContractId,
        MintingDelegation,
      ],
      openRound: ContractWithState[OpenMiningRound.ContractId, OpenMiningRound],
      openIssuingRounds: Seq[ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]],
      amuletRules: ContractWithState[AmuletRules.ContractId, AmuletRules],
      couponsData: CouponsData,
      amulets: Seq[Contract[
        Amulet.ContractId,
        Amulet,
      ]],
      validatorRightOpt: Option[Contract[ValidatorRight.ContractId, ValidatorRight]],
  )(implicit tc: TraceContext): Future[Boolean] = {
    val mergeLimit = delegation.payload.amuletMergeLimit.longValue()
    // Ignore ValidatorRewardCoupons if we don't have the ValidatorRight to collect them as beneficiary
    val validatorRewardCouponsToCollect =
      if (validatorRightOpt.isDefined) couponsData.validatorRewardCoupons else Seq.empty
    val hasRewardsToCollect = couponsData.livenessActivityRecords.nonEmpty ||
      validatorRewardCouponsToCollect.nonEmpty ||
      couponsData.appRewardCoupons.nonEmpty ||
      couponsData.unclaimedActivityRecords.nonEmpty
    // Merge amulets if:
    // 1. We're above the merge limit, OR
    // 2. We're at the merge limit AND have rewards to collect (which would create a new amulet)
    val shouldMergeAmulets = amulets.size > mergeLimit ||
      (amulets.size == mergeLimit && hasRewardsToCollect)

    if (hasRewardsToCollect || shouldMergeAmulets) {
      val amuletsToMerge = if (shouldMergeAmulets) {
        // Merge the smallest amounts first
        // we do +1 here to maintain exactly 'mergeLimit' amulets after the mint
        amulets
          .sortBy(a =>
            BigDecimal(SpliceUtil.currentAmount(a.payload, openRound.payload.round.number))
          )
          .take(amulets.size - mergeLimit.toInt + 1)
      } else Seq.empty

      // Use filtered couponsData with only collectable ValidatorRewardCoupons
      val filteredCouponsData = couponsData.copy(
        validatorRewardCoupons = validatorRewardCouponsToCollect
      )

      val inputs = buildTransferInputs(filteredCouponsData, amuletsToMerge)
      val transferContext =
        buildTransferContext(openRound, openIssuingRounds, filteredCouponsData, validatorRightOpt)
      val paymentContext = new PaymentTransferContext(
        amuletRules.contractId,
        transferContext,
      )

      val contractsToDisclose = spliceLedgerConnection.disclosedContracts(
        amuletRules,
        openRound,
      ) addAll openIssuingRounds

      spliceLedgerConnection
        .submit(
          actAs = Seq(validatorParty),
          readAs = Seq(validatorParty, externalParty),
          delegation.contractId.exerciseMintingDelegation_Mint(inputs.asJava, paymentContext),
        )
        .withDisclosedContracts(contractsToDisclose)
        .noDedup
        .yieldUnit()
        .map { _ =>
          logger.debug(
            s"Collected ${filteredCouponsData.livenessActivityRecords.size} liveness activity records, " +
              s"${filteredCouponsData.validatorRewardCoupons.size} validator reward coupons, " +
              s"${filteredCouponsData.appRewardCoupons.size} app reward coupons, " +
              s"${filteredCouponsData.unclaimedActivityRecords.size} unclaimed activity records, " +
              s"and merged ${amuletsToMerge.size} amulets for delegation ${delegation.contractId}"
          )
          true
        }
    } else {
      Future.successful(false)
    }
  }

  // Helper APIs
  private def fetchDataFromScan()(implicit tc: TraceContext): Future[
    (
        ContractWithState[OpenMiningRound.ContractId, OpenMiningRound],
        Seq[ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]],
        Map[Round, IssuingMiningRound],
        ContractWithState[AmuletRules.ContractId, AmuletRules],
    )
  ] = {
    for {
      (openRounds, issuingRounds) <- scanConnection.getOpenAndIssuingMiningRounds()
      amuletRules <- scanConnection.getAmuletRulesWithState()
    } yield {
      val now = context.clock.now
      val openRound = SpliceUtil.selectLatestOpenMiningRound(now, openRounds)
      val openIssuingRounds = issuingRounds.filter(c => c.payload.opensAt.isBefore(now.toInstant))
      val issuingRoundsMap = openIssuingRounds.view.map { r =>
        val imr = r.payload
        (imr.round, imr)
      }.toMap
      (openRound, openIssuingRounds, issuingRoundsMap, amuletRules)
    }
  }

  private case class CouponsData(
      livenessActivityRecords: Seq[Contract[
        ValidatorLivenessActivityRecord.ContractId,
        ValidatorLivenessActivityRecord,
      ]],
      validatorRewardCoupons: Seq[Contract[
        ValidatorRewardCoupon.ContractId,
        ValidatorRewardCoupon,
      ]],
      appRewardCoupons: Seq[Contract[
        AppRewardCoupon.ContractId,
        AppRewardCoupon,
      ]],
      unclaimedActivityRecords: Seq[Contract[
        UnclaimedActivityRecord.ContractId,
        UnclaimedActivityRecord,
      ]],
  )

  private def fetchCouponsData(
      issuingRoundsMap: Map[
        Round,
        IssuingMiningRound,
      ]
  )(implicit tc: TraceContext): Future[CouponsData] = {
    for {
      livenessActivityRecordsWithQuantity <- store.listSortedLivenessActivityRecords(
        issuingRoundsMap
      )
      validatorRewardCoupons <- store.listSortedValidatorRewards(
        Some(issuingRoundsMap.keySet.map(_.number))
      )
      appRewardCouponsWithQuantity <- store.listSortedAppRewards(issuingRoundsMap)
      unclaimedActivityRecords <- store.listUnclaimedActivityRecords()
    } yield CouponsData(
      livenessActivityRecordsWithQuantity.map(_._1),
      validatorRewardCoupons,
      appRewardCouponsWithQuantity.map(_._1),
      unclaimedActivityRecords,
    )
  }

  private def buildTransferInputs(
      couponsData: CouponsData,
      amuletsToMerge: Seq[Contract[
        Amulet.ContractId,
        Amulet,
      ]],
  ): Seq[TransferInput] = {
    val livenessInputs: Seq[TransferInput] = couponsData.livenessActivityRecords.map { record =>
      new InputValidatorLivenessActivityRecord(record.contractId): TransferInput
    }

    val validatorCouponInputs: Seq[TransferInput] = couponsData.validatorRewardCoupons.map {
      coupon =>
        new InputValidatorRewardCoupon(coupon.contractId): TransferInput
    }

    val appCouponInputs: Seq[TransferInput] = couponsData.appRewardCoupons.map { coupon =>
      new InputAppRewardCoupon(coupon.contractId): TransferInput
    }

    val unclaimedActivityRecordInputs: Seq[TransferInput] =
      couponsData.unclaimedActivityRecords.map { record =>
        new InputUnclaimedActivityRecord(record.contractId): TransferInput
      }

    val amuletInputs: Seq[TransferInput] = amuletsToMerge.map { amulet =>
      new InputAmulet(amulet.contractId): TransferInput
    }

    livenessInputs ++ validatorCouponInputs ++ appCouponInputs ++ unclaimedActivityRecordInputs ++ amuletInputs
  }

  private def buildTransferContext(
      openRound: ContractWithState[OpenMiningRound.ContractId, OpenMiningRound],
      openIssuingRounds: Seq[ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]],
      couponsData: CouponsData,
      validatorRightOpt: Option[Contract[ValidatorRight.ContractId, ValidatorRight]],
  ): TransferContext = {
    // Only include ValidatorRight in context if we're actually collecting ValidatorRewardCoupons
    val validatorRightsMap =
      (validatorRightOpt, couponsData.validatorRewardCoupons.nonEmpty) match {
        case (Some(vr), true) => Map(vr.payload.user -> vr.contractId)
        case _ => Map.empty[String, ValidatorRight.ContractId]
      }

    new TransferContext(
      openRound.contractId,
      openIssuingRounds.view
        .filter(r =>
          couponsData.livenessActivityRecords.exists(_.payload.round == r.payload.round) ||
            couponsData.validatorRewardCoupons.exists(_.payload.round == r.payload.round) ||
            couponsData.appRewardCoupons.exists(_.payload.round == r.payload.round)
        )
        .map(r => (r.payload.round, r.contractId))
        .toMap[
          Round,
          IssuingMiningRound.ContractId,
        ]
        .asJava,
      validatorRightsMap.asJava,
      None.toJava,
    )
  }
}
