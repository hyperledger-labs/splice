// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import com.digitalasset.canton.caching.ScaffeineCache
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import com.github.blemale.scaffeine.Scaffeine
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.{AmuletConfig, USD}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.rewardaccountingv2.CalculateRewardsV2
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.OpenMiningRound
import org.lfdecentralizedtrust.splice.store.{Limit, MultiDomainAcsStore, SynchronizerStore}
import org.lfdecentralizedtrust.splice.util.Contract

import scala.concurrent.{ExecutionContext, Future}

/** A cache over the as-of lookups used during app-activity computation.
  * All other methods are forwarded to the underlying store.
  *
  * Each as-of cache keeps the results of the last two distinct queries; older
  * entries are evicted. This keeps in memory the data for at most two open
  * rounds while calculating activity records for a batch of verdicts, since
  * verdicts are processed in monotonically increasing time order.
  */
class CachingScanRewardsReferenceStore private[splice] (
    store: ScanRewardsReferenceStore,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit override protected val ec: ExecutionContext)
    extends ScanRewardsReferenceStore
    with NamedLogging {

  private val featuredAppPartiesCache: ScaffeineCache.TracedAsyncLoadingCache[
    Future,
    CantonTimestamp,
    ScanRewardsReferenceStore.FeaturedAppParties,
  ] =
    ScaffeineCache.buildTracedAsync[
      Future,
      CantonTimestamp,
      ScanRewardsReferenceStore.FeaturedAppParties,
    ](
      Scaffeine()
        .maximumSize(2L),
      loader = implicit tc => asOf => store.lookupFeaturedAppPartiesAsOf(asOf),
    )(logger, "featuredAppPartiesAsOf")

  private val svParticipantIdsCache
      : ScaffeineCache.TracedAsyncLoadingCache[Future, CantonTimestamp, Set[String]] =
    ScaffeineCache.buildTracedAsync[Future, CantonTimestamp, Set[String]](
      Scaffeine()
        .maximumSize(2L),
      loader = implicit tc => asOf => store.lookupSvParticipantIdsAsOf(asOf),
    )(logger, "svParticipantIdsAsOf")

  private val amuletConfigCache: ScaffeineCache.TracedAsyncLoadingCache[
    Future,
    CantonTimestamp,
    Option[AmuletConfig[USD]],
  ] =
    ScaffeineCache.buildTracedAsync[Future, CantonTimestamp, Option[AmuletConfig[USD]]](
      Scaffeine()
        .maximumSize(2L),
      loader = implicit tc => asOf => store.lookupAmuletConfigAsOf(asOf),
    )(logger, "amuletConfigAsOf")

  override def key: ScanRewardsReferenceStore.Key = store.key

  override def waitUntilInitialized: Future[Unit] = store.waitUntilInitialized

  override def lookupActiveOpenMiningRounds(
      recordTimes: Seq[CantonTimestamp]
  )(implicit
      tc: TraceContext
  ): Future[Map[CantonTimestamp, ScanRewardsReferenceStore.ActiveRoundInfo]] =
    store.lookupActiveOpenMiningRounds(recordTimes)

  override def lookupFeaturedAppPartiesAsOf(
      asOf: CantonTimestamp
  )(implicit
      tc: TraceContext
  ): Future[ScanRewardsReferenceStore.FeaturedAppParties] =
    featuredAppPartiesCache.get(asOf)

  override def lookupSvParticipantIdsAsOf(
      asOf: CantonTimestamp
  )(implicit tc: TraceContext): Future[Set[String]] =
    svParticipantIdsCache.get(asOf)

  override def lookupAmuletConfigAsOf(
      asOf: CantonTimestamp
  )(implicit tc: TraceContext): Future[Option[AmuletConfig[USD]]] =
    amuletConfigCache.get(asOf)

  override def lookupOpenMiningRoundByNumber(
      roundNumber: Long
  )(implicit
      tc: TraceContext
  ): Future[Option[Contract[OpenMiningRound.ContractId, OpenMiningRound]]] =
    store.lookupOpenMiningRoundByNumber(roundNumber)

  override def lookupLatestArchivedOpenMiningRound(
      asOf: CantonTimestamp
  )(implicit tc: TraceContext): Future[Option[Long]] =
    store.lookupLatestArchivedOpenMiningRound(asOf)

  override def listActiveCalculateRewardsV2(limit: Limit = defaultLimit)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[CalculateRewardsV2.ContractId, CalculateRewardsV2]]] =
    store.listActiveCalculateRewardsV2(limit)

  override def listActiveCalculateRewardsV2ForRound(roundNumber: Long)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[CalculateRewardsV2.ContractId, CalculateRewardsV2]]] =
    store.listActiveCalculateRewardsV2ForRound(roundNumber)

  override def lookupArchivedAtForOpenMiningRound(
      roundNumber: Long
  )(implicit tc: TraceContext): Future[Option[CantonTimestamp]] =
    store.lookupArchivedAtForOpenMiningRound(roundNumber)

  override def lookupLowestPrunableArchivedRewardRound()(implicit
      tc: TraceContext
  ): Future[Option[Long]] =
    store.lookupLowestPrunableArchivedRewardRound()

  override def pruneArchivedUpToRound(
      roundNumber: Long
  )(implicit tc: TraceContext): Future[Long] =
    store.pruneArchivedUpToRound(roundNumber)

  override val storeName: String = store.storeName
  override def defaultLimit: Limit = store.defaultLimit
  override lazy val acsContractFilter = store.acsContractFilter
  override def domains: SynchronizerStore = store.domains
  override def multiDomainAcsStore: MultiDomainAcsStore = store.multiDomainAcsStore
  override def close(): Unit = store.close()
}
