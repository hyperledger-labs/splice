// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.digitalasset.canton.caching.ScaffeineCache
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
import org.lfdecentralizedtrust.splice.scan.config.{CacheConfig, ScanCacheConfig}
import org.lfdecentralizedtrust.splice.scan.store.db.{DbScanStoreMetrics, ScanAggregator}
import org.lfdecentralizedtrust.splice.store.{
  Limit,
  MiningRoundsStore,
  MultiDomainAcsStore,
  PageLimit,
  SortOrder,
  SynchronizerStore,
  TxLogStore,
  UpdateHistory,
}
import org.lfdecentralizedtrust.splice.util.{Contract, ContractWithState}

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future, blocking}

class CachingScanStore(
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
    store: ScanStore,
    cacheConfig: ScanCacheConfig,
    storeMetrics: DbScanStoreMetrics,
)(implicit
    override protected val ec: ExecutionContext
) extends ScanStore
    with FlagCloseableAsync
    with RetryProvider.Has {

  override val storeName: String = store.storeName
  override lazy val txLogConfig: TxLogStore.Config[TxLogEntry] = store.txLogConfig

  override def key: ScanStore.Key = store.key

  private val cacheOfCaches = scala.collection.concurrent
    .TrieMap[String, ScaffeineCache.TracedAsyncLoadingCache[Future, ?, ?]]()

  override protected[store] def domainMigrationId: Long = store.domainMigrationId

  // TODO(#800) remove when amulet expiry works again
  override def getTotalAmuletBalance(
      asOfEndOfRound: Long
  )(implicit tc: TraceContext): Future[Option[BigDecimal]] = {
    getCache(
      "totalAmuletBalance",
      cacheConfig.totalAmuletBalance,
      store.getTotalAmuletBalance _,
    ).get(asOfEndOfRound)
  }

  override def listSvNodeStates()(implicit tc: TraceContext): Future[Seq[SvNodeState]] = {
    getCache(
      "svNodeStateCache",
      cacheConfig.svNodeState,
      (_: Unit) => store.listSvNodeStates(),
    ).get(())
  }

  override def aggregate()(implicit tc: TraceContext): Future[Option[ScanAggregator.RoundTotals]] =
    store.aggregate()

  override def backFillAggregates()(implicit tc: TraceContext): Future[Option[Long]] =
    store.backFillAggregates()

  override def lookupAmuletRules()(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[AmuletRules.ContractId, AmuletRules]]] =
    getCache(
      "lookupAmuletRules",
      cacheConfig.amuletRules,
      (_: Unit) => store.lookupAmuletRules(),
    ).get(())

  override def getExternalPartyAmuletRules()(implicit
      tc: TraceContext
  ): Future[ContractWithState[ExternalPartyAmuletRules.ContractId, ExternalPartyAmuletRules]] =
    getCache(
      "externalPartyAmuletRules",
      cacheConfig.amuletRules,
      (_: Unit) => store.getExternalPartyAmuletRules(),
    ).get(())

  override def lookupAnsRules()(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[AnsRules.ContractId, AnsRules]]] =
    getCache(
      "lookupAnsRules",
      cacheConfig.ansRules,
      (_: Unit) => store.lookupAnsRules(),
    ).get(())

  override def getTotalRewardsCollectedEver()(implicit tc: TraceContext): Future[BigDecimal] =
    getCache(
      "totalRewardsCollected",
      cacheConfig.totalRewardsCollected,
      (_: Unit) => store.getTotalRewardsCollectedEver(),
    ).get(())

  override def getRewardsCollectedInRound(round: Long)(implicit
      tc: TraceContext
  ): Future[BigDecimal] = {
    getCache(
      "rewardsCollectedInRound",
      cacheConfig.rewardsCollectedInRound,
      store.getRewardsCollectedInRound,
    ).get(round)
  }

  override def getWalletBalance(partyId: PartyId, asOfEndOfRound: Long)(implicit
      tc: TraceContext
  ): Future[BigDecimal] = {
    getCache(
      "walletBalance",
      cacheConfig.walletBalance,
      (store.getWalletBalance _) tupled,
    ).get((partyId -> asOfEndOfRound))
  }

  override def getAmuletConfigForRound(round: Long)(implicit
      tc: TraceContext
  ): Future[OpenMiningRoundTxLogEntry] =
    getCache(
      "getAmuletConfigForRound",
      cacheConfig.amuletConfigForRound,
      store.getAmuletConfigForRound,
    ).get(round)

  override def lookupRoundOfLatestData()(implicit
      tc: TraceContext
  ): Future[Option[(Long, Instant)]] =
    getCache(
      "roundOfLatestData",
      cacheConfig.roundOfLatestData,
      (_: Unit) => store.lookupRoundOfLatestData(),
    ).get(())

  override def getTopProvidersByAppRewards(asOfEndOfRound: Long, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[(PartyId, BigDecimal)]] = {
    getCache(
      "topProvidersByAppRewards",
      cacheConfig.topProvidersByAppRewards,
      store.getTopProvidersByAppRewards _ tupled,
    ).get((asOfEndOfRound, limit))
  }

  override def getTopValidatorsByValidatorRewards(asOfEndOfRound: Long, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[(PartyId, BigDecimal)]] =
    getCache(
      "topValidatorsByValidatorRewards",
      cacheConfig.topValidators,
      store.getTopValidatorsByValidatorRewards _ tupled,
    ).get((asOfEndOfRound, limit))

  override def getTopValidatorsByPurchasedTraffic(asOfEndOfRound: Long, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[HttpScanAppClient.ValidatorPurchasedTraffic]] =
    getCache(
      "topValidatorsByPurchasedTraffic",
      cacheConfig.topValidators,
      store.getTopValidatorsByPurchasedTraffic _ tupled,
    ).get((asOfEndOfRound, limit))

  override def getTopValidatorLicenses(limit: Limit)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[ValidatorLicense.ContractId, ValidatorLicense]]] =
    getCache(
      "topValidatorLicenses",
      cacheConfig.topValidators,
      store.getTopValidatorLicenses,
    ).get(limit)

  override def getValidatorLicenseByValidator(validator: Vector[PartyId])(implicit
      tc: TraceContext
  ): Future[Seq[Contract[ValidatorLicense.ContractId, ValidatorLicense]]] =
    getCache(
      "validatorLicenseByValidator",
      cacheConfig.validatorLicenseByValidator,
      store.getValidatorLicenseByValidator,
    ).get(validator)

  override def getTotalPurchasedMemberTraffic(memberId: Member, synchronizerId: SynchronizerId)(
      implicit tc: TraceContext
  ): Future[Long] =
    getCache(
      "totalPurchasedMemberTraffic",
      cacheConfig.totalPurchasedMemberTraffic,
      store.getTotalPurchasedMemberTraffic _ tupled,
    ).get((memberId, synchronizerId))

  override def lookupFeaturedAppRight(providerPartyId: PartyId)(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[FeaturedAppRight.ContractId, FeaturedAppRight]]] =
    getCache(
      "featuredAppRight",
      cacheConfig.cachedByParty,
      store.lookupFeaturedAppRight,
    ).get(providerPartyId)

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
    getCache(
      "lookupTransferPreapprovalByParty",
      cacheConfig.cachedByParty,
      store.lookupTransferPreapprovalByParty,
    ).get(partyId)

  override def lookupTransferCommandCounterByParty(partyId: PartyId)(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[TransferCommandCounter.ContractId, TransferCommandCounter]]] =
    getCache(
      "lookupTransferCommandCounterByParty",
      cacheConfig.cachedByParty,
      store.lookupTransferCommandCounterByParty,
    ).get(partyId)

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
  ): Future[Option[ScanAggregator.RoundRange]] =
    getCache(
      "aggregatedRounds",
      cacheConfig.aggregatedRounds,
      (_: Unit) => store.getAggregatedRounds(),
    ).get(())

  override def getRoundTotals(startRound: Long, endRound: Long)(implicit
      tc: TraceContext
  ): Future[Seq[ScanAggregator.RoundTotals]] =
    getCache(
      "roundTotals",
      cacheConfig.roundTotals,
      store.getRoundTotals _ tupled,
    ).get((startRound, endRound))

  override def getRoundPartyTotals(startRound: Long, endRound: Long)(implicit
      tc: TraceContext
  ): Future[Seq[ScanAggregator.RoundPartyTotals]] =
    getCache(
      "roundPartyTotals",
      cacheConfig.roundTotals,
      store.getRoundPartyTotals _ tupled,
    ).get((startRound, endRound))

  override def lookupLatestTransferCommandEvents(sender: PartyId, nonce: Long, limit: Int)(implicit
      tc: TraceContext
  ): Future[Map[TransferCommand.ContractId, TransferCommandTxLogEntry]] =
    store.lookupLatestTransferCommandEvents(sender, nonce, limit)

  override def lookupContractByRecordTime[C, TCId <: ContractId[?], T](
      companion: C,
      updateHistory: UpdateHistory,
      recordTime: CantonTimestamp,
  )(implicit
      companionClass: MultiDomainAcsStore.ContractCompanion[C, TCId, T],
      tc: TraceContext,
  ): Future[Option[Contract[TCId, T]]] = store.lookupContractByRecordTime(
    companion,
    updateHistory,
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
    getCache(
      "listVoteRequestResults",
      cacheConfig.voteRequests,
      store.listVoteRequestResults _ tupled,
    ).get(
      (
        actionName,
        accepted,
        requester,
        effectiveFrom,
        effectiveTo,
        limit,
      )
    )

  override def listVoteRequestsByTrackingCid(
      voteRequestCids: Seq[VoteRequest.ContractId],
      limit: Limit,
  )(implicit tc: TraceContext): Future[Seq[Contract[VoteRequest.ContractId, VoteRequest]]] =
    getCache(
      "listVoteRequestsByTrackingCid",
      cacheConfig.voteRequests,
      store.listVoteRequestsByTrackingCid _ tupled,
    ).get((voteRequestCids, limit))

  override def lookupVoteRequest(contractId: VoteRequest.ContractId)(implicit
      tc: TraceContext
  ): Future[Option[Contract[VoteRequest.ContractId, VoteRequest]]] =
    getCache(
      "lookupVoteRequest",
      cacheConfig.voteRequests,
      store.lookupVoteRequest,
    ).get(contractId)

  override def lookupSvNodeState(svPartyId: PartyId)(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[SvNodeState.ContractId, SvNodeState]]] =
    getCache(
      "lookupSvNodeState",
      cacheConfig.svNodeState,
      store.lookupSvNodeState,
    ).get(svPartyId)

  override def lookupOpenMiningRoundTriple()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[MiningRoundsStore.OpenMiningRoundTriple]] =
    getCache(
      "openMiningRounds",
      cacheConfig.openMiningRounds,
      (_: Unit) => store.lookupOpenMiningRoundTriple(),
    ).get(())

  override def domains: SynchronizerStore = store.domains

  override def multiDomainAcsStore: MultiDomainAcsStore = store.multiDomainAcsStore

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  private def getCache[Key, Value](
      cacheName: String,
      cacheConfig: CacheConfig,
      loader: Key => Future[Value],
  )(implicit tc: TraceContext) = {
    cacheOfCaches
      .getOrElseUpdate(
        cacheName,
        blocking {
          synchronized {
            // the function to provide the value if the key is not present can in theory be called multiple times by concurrent threads.
            // that would cause the same gauges to be created multiple times
            cacheOfCaches.getOrElseUpdate(
              cacheName,
              ScaffeineCache.buildTracedAsync[Future, Key, Value](
                Scaffeine()
                  .expireAfterWrite(cacheConfig.ttl.asFiniteApproximation)
                  .maximumSize(cacheConfig.maxSize),
                _ => key => loader(key),
                metrics =
                  storeMetrics.registerNewCacheMetrics(cacheName).map(Some(_)).onShutdown(None),
              )(logger, cacheName),
            )
          }
        },
      )
      .asInstanceOf[ScaffeineCache.TracedAsyncLoadingCache[Future, Key, Value]]
  }

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
