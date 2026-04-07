// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import com.digitalasset.canton.caching.ScaffeineCache
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import com.github.blemale.scaffeine.Scaffeine
import org.lfdecentralizedtrust.splice.store.{Limit, MultiDomainAcsStore, SynchronizerStore}

import scala.concurrent.{ExecutionContext, Future}

/** A cache over lookupFeaturedAppPartiesAsOf
  * All other methods are forwarded to the underlying store.
  *
  * For lookupFeaturedAppPartiesAsOf, we keep the results of the last two
  * distinct queries only; older entries are evicted. So that we keep in memory
  * the active featured app parties for at most two open rounds while
  * calculating the app activity records for the batch of verdicts, as the
  * processing of verdicts happens in monotonically increasing time order.
  */
class CachingScanRewardsReferenceStore private[splice] (
    store: ScanRewardsReferenceStore,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit override protected val ec: ExecutionContext)
    extends ScanRewardsReferenceStore
    with NamedLogging {

  private val featuredAppPartiesCache
      : ScaffeineCache.TracedAsyncLoadingCache[Future, CantonTimestamp, Set[String]] =
    ScaffeineCache.buildTracedAsync[Future, CantonTimestamp, Set[String]](
      Scaffeine()
        .maximumSize(2L),
      loader = implicit tc => asOf => store.lookupFeaturedAppPartiesAsOf(asOf),
    )(logger, "featuredAppPartiesAsOf")

  override def key: ScanRewardsReferenceStore.Key = store.key

  override def lookupActiveOpenMiningRounds(
      recordTimes: Seq[CantonTimestamp]
  )(implicit tc: TraceContext): Future[Map[CantonTimestamp, (Long, CantonTimestamp)]] =
    store.lookupActiveOpenMiningRounds(recordTimes)

  override def lookupFeaturedAppPartiesAsOf(
      asOf: CantonTimestamp
  )(implicit tc: TraceContext): Future[Set[String]] =
    featuredAppPartiesCache.get(asOf)

  override val storeName: String = store.storeName
  override def defaultLimit: Limit = store.defaultLimit
  override lazy val acsContractFilter = store.acsContractFilter
  override def domains: SynchronizerStore = store.domains
  override def multiDomainAcsStore: MultiDomainAcsStore = store.multiDomainAcsStore
  override def close(): Unit = store.close()
}
