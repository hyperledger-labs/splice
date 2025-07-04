// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.digitalasset.canton.caching.ScaffeineCache
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, FlagCloseableAsync, SyncCloseable}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.{Member, PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.github.blemale.scaffeine.Scaffeine
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.FeaturedAppRight
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.{
  AmuletRules,
  TransferPreapproval,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.ans.{AnsEntry, AnsRules}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.svstate.SvNodeState
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  DsoRules_CloseVoteRequestResult,
  VoteRequest,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.externalpartyamuletrules.{
  ExternalPartyAmuletRules,
  TransferCommand,
  TransferCommandCounter,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense.ValidatorLicense
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanStore.{CacheKey, CacheValue}
import org.lfdecentralizedtrust.splice.scan.store.db.{
  DbScanStore,
  DbScanStoreMetrics,
  ScanAggregator,
}
import org.lfdecentralizedtrust.splice.store.{
  Limit,
  MultiDomainAcsStore,
  PageLimit,
  SortOrder,
  SynchronizerStore,
  TxLogStore,
  UpdateHistory,
}
import org.lfdecentralizedtrust.splice.util.{Contract, ContractWithState}

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

class CachingScanStore(
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
    store: ScanStore,
    svNodeStateCacheTtl: NonNegativeFiniteDuration,
    storeMetrics: DbScanStoreMetrics,
)(implicit
    override protected val ec: ExecutionContext
) extends ScanStore
    with FlagCloseableAsync
    with RetryProvider.Has {

  override lazy val txLogConfig: TxLogStore.Config[TxLogEntry] = store.txLogConfig

  override def key: ScanStore.Key = store.key

  private val totalAmuletBalanceCache
      : ScaffeineCache.TracedAsyncLoadingCache[Future, CacheKey, CacheValue] = {
    ScaffeineCache.buildTracedAsync[Future, DbScanStore.CacheKey, DbScanStore.CacheValue](
      Scaffeine()
        .maximumSize(1000),
      implicit tc => key => store.getTotalAmuletBalance(key),
      metrics = Some(storeMetrics.totalAmuletBalanceCache),
    )(logger, "amuletBalanceCache")
  }

  private val svNodeStateCache
      : ScaffeineCache.TracedAsyncLoadingCache[Future, Unit, Seq[SvNodeState]] = {
    ScaffeineCache.buildTracedAsync[Future, Unit, Seq[SvNodeState]](
      Scaffeine()
        .expireAfterWrite(svNodeStateCacheTtl.asFiniteApproximation),
      implicit tc => _ => store.listSvNodeStates(),
      metrics = Some(storeMetrics.svNodeStateCache),
    )(logger, "svNodeStateCache")
  }

  // TODO(#800) remove when amulet expiry works again
  override def getTotalAmuletBalance(
      asOfEndOfRound: Long
  )(implicit tc: TraceContext): Future[BigDecimal] = {
    totalAmuletBalanceCache.get(asOfEndOfRound)
  }

  override def listSvNodeStates()(implicit tc: TraceContext): Future[Seq[SvNodeState]] =
    svNodeStateCache.get(())

  override def aggregate()(implicit tc: TraceContext): Future[Option[ScanAggregator.RoundTotals]] =
    store.aggregate()

  override def backFillAggregates()(implicit tc: TraceContext): Future[Option[Long]] =
    store.backFillAggregates()

  override protected[store] def domainMigrationId: Long = store.domainMigrationId

  override def lookupAmuletRules()(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[AmuletRules.ContractId, AmuletRules]]] =
    store.lookupAmuletRules()

  override def getExternalPartyAmuletRules()(implicit
      tc: TraceContext
  ): Future[ContractWithState[ExternalPartyAmuletRules.ContractId, ExternalPartyAmuletRules]] =
    store.getExternalPartyAmuletRules()

  override def lookupAnsRules()(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[AnsRules.ContractId, AnsRules]]] = store.lookupAnsRules()

  override def getTotalRewardsCollectedEver()(implicit tc: TraceContext): Future[BigDecimal] =
    store.getTotalRewardsCollectedEver()

  override def getRewardsCollectedInRound(round: Long)(implicit
      tc: TraceContext
  ): Future[BigDecimal] = store.getRewardsCollectedInRound(round)

  override def getWalletBalance(partyId: PartyId, asOfEndOfRound: Long)(implicit
      tc: TraceContext
  ): Future[BigDecimal] = store.getWalletBalance(partyId, asOfEndOfRound)

  override def getAmuletConfigForRound(round: Long)(implicit
      tc: TraceContext
  ): Future[OpenMiningRoundTxLogEntry] = store.getAmuletConfigForRound(round)

  override def getRoundOfLatestData()(implicit tc: TraceContext): Future[(Long, Instant)] =
    store.getRoundOfLatestData()

  override def getTopProvidersByAppRewards(asOfEndOfRound: Long, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[(PartyId, BigDecimal)]] = store.getTopProvidersByAppRewards(asOfEndOfRound, limit)

  override def getTopValidatorsByValidatorRewards(asOfEndOfRound: Long, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[(PartyId, BigDecimal)]] =
    store.getTopValidatorsByValidatorRewards(asOfEndOfRound, limit)

  override def getTopValidatorsByPurchasedTraffic(asOfEndOfRound: Long, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[HttpScanAppClient.ValidatorPurchasedTraffic]] =
    store.getTopValidatorsByPurchasedTraffic(asOfEndOfRound, limit)

  override def getTopValidatorLicenses(limit: Limit)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[ValidatorLicense.ContractId, ValidatorLicense]]] =
    store.getTopValidatorLicenses(limit)

  override def getValidatorLicenseByValidator(validator: Vector[PartyId])(implicit
      tc: TraceContext
  ): Future[Seq[Contract[ValidatorLicense.ContractId, ValidatorLicense]]] =
    store.getValidatorLicenseByValidator(validator)

  override def getTotalPurchasedMemberTraffic(memberId: Member, synchronizerId: SynchronizerId)(
      implicit tc: TraceContext
  ): Future[Long] = store.getTotalPurchasedMemberTraffic(memberId, synchronizerId)

  override def lookupFeaturedAppRight(providerPartyId: PartyId)(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[FeaturedAppRight.ContractId, FeaturedAppRight]]] =
    store.lookupFeaturedAppRight(providerPartyId)

  override def listEntries(namePrefix: String, now: CantonTimestamp, limit: Limit)(implicit
      tc: TraceContext
  ): Future[Seq[ContractWithState[AnsEntry.ContractId, AnsEntry]]] =
    store.listEntries(namePrefix, now, limit)

  override def lookupEntryByParty(partyId: PartyId, now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[AnsEntry.ContractId, AnsEntry]]] =
    store.lookupEntryByParty(partyId, now)

  override def lookupEntryByName(name: String, now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[AnsEntry.ContractId, AnsEntry]]] =
    store.lookupEntryByName(name, now)

  override def lookupTransferPreapprovalByParty(partyId: PartyId)(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[TransferPreapproval.ContractId, TransferPreapproval]]] =
    store.lookupTransferPreapprovalByParty(partyId)

  override def lookupTransferCommandCounterByParty(partyId: PartyId)(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[TransferCommandCounter.ContractId, TransferCommandCounter]]] =
    store.lookupTransferCommandCounterByParty(partyId)

  override def listTransactions(
      pageEndEventId: Option[String],
      sortOrder: SortOrder,
      limit: PageLimit,
  )(implicit tc: TraceContext): Future[Seq[TxLogEntry.TransactionTxLogEntry]] =
    store.listTransactions(
      pageEndEventId,
      sortOrder,
      limit,
    )

  override def getAggregatedRounds()(implicit
      tc: TraceContext
  ): Future[Option[ScanAggregator.RoundRange]] = store.getAggregatedRounds()

  override def getRoundTotals(startRound: Long, endRound: Long)(implicit
      tc: TraceContext
  ): Future[Seq[ScanAggregator.RoundTotals]] = store.getRoundTotals(startRound, endRound)

  override def getRoundPartyTotals(startRound: Long, endRound: Long)(implicit
      tc: TraceContext
  ): Future[Seq[ScanAggregator.RoundPartyTotals]] = store.getRoundPartyTotals(startRound, endRound)

  override def lookupLatestTransferCommandEvents(sender: PartyId, nonce: Long, limit: Int)(implicit
      tc: TraceContext
  ): Future[Map[TransferCommand.ContractId, TransferCommandTxLogEntry]] =
    store.lookupLatestTransferCommandEvents(sender, nonce, limit)

  override def lookupContractByRecordTime[C, TCId <: ContractId[_], T](
      companion: C,
      recordTime: CantonTimestamp,
  )(implicit
      companionClass: MultiDomainAcsStore.ContractCompanion[C, TCId, T],
      tc: TraceContext,
  ): Future[Option[Contract[TCId, T]]] = store.lookupContractByRecordTime(
    companion,
    recordTime,
  )

  override def listVoteRequestResults(
      actionName: Option[String],
      accepted: Option[Boolean],
      requester: Option[String],
      effectiveFrom: Option[String],
      effectiveTo: Option[String],
      limit: Limit,
  )(implicit tc: TraceContext): Future[Seq[DsoRules_CloseVoteRequestResult]] =
    store.listVoteRequestResults(
      actionName,
      accepted,
      requester,
      effectiveFrom,
      effectiveTo,
      limit,
    )

  override def listVoteRequestsByTrackingCid(
      voteRequestCids: Seq[VoteRequest.ContractId],
      limit: Limit,
  )(implicit tc: TraceContext): Future[Seq[Contract[VoteRequest.ContractId, VoteRequest]]] =
    store.listVoteRequestsByTrackingCid(
      voteRequestCids,
      limit,
    )

  override def lookupVoteRequest(contractId: VoteRequest.ContractId)(implicit
      tc: TraceContext
  ): Future[Option[Contract[VoteRequest.ContractId, VoteRequest]]] = store.lookupVoteRequest(
    contractId
  )

  override def lookupSvNodeState(svPartyId: PartyId)(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[SvNodeState.ContractId, SvNodeState]]] =
    store.lookupSvNodeState(svPartyId)

  override def domains: SynchronizerStore = store.domains

  override def multiDomainAcsStore: MultiDomainAcsStore = store.multiDomainAcsStore

  override def updateHistory: UpdateHistory = store.updateHistory

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = {
    Seq(
      SyncCloseable(
        "actual_scan_store",
        store.close(),
      ),
      SyncCloseable("db_scan_store_metrics", storeMetrics.close()),
    )
  }
}
