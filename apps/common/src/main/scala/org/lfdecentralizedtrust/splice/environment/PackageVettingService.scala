// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import com.daml.metrics.api.MetricHandle.LabeledMetricsFactory
import com.digitalasset.canton.caching.ScaffeineCache
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.NonNegativeLong
import com.digitalasset.canton.lifecycle.FlagCloseable
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.metrics.CacheMetrics
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.daml.lf.data.Ref.PackageVersion
import com.github.blemale.scaffeine.Scaffeine
import com.digitalasset.canton.util.MonadUtil

import scala.concurrent.{ExecutionContext, Future}

import PackageVettingService.CacheConfig

class PackageVettingService(
    cacheConfig: CacheConfig,
    connection: BaseLedgerConnection,
    synchronizerId: SynchronizerId,
    clock: Clock,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
    metricsFactory: LabeledMetricsFactory,
)(implicit ec: ExecutionContext)
    extends NamedLogging
    with FlagCloseable
    with RetryProvider.Has {

  val metrics = new CacheMetrics(
    "vetting-cache",
    metricsFactory: LabeledMetricsFactory,
  )

  private val vettingCache = ScaffeineCache
    .buildTracedAsync[Future, (PartyId, PackageIdResolver.Package), Option[PackageVersion]](
      Scaffeine()
        .expireAfterWrite(cacheConfig.ttl.asFiniteApproximation)
        .maximumSize(cacheConfig.size.unwrap),
      implicit tc => key => lookupVettingStateUncached(key._1, key._2),
    )(logger, "vetting-cache")

  private def lookupVettingStateUncached(p: PartyId, pkg: PackageIdResolver.Package)(implicit
      tc: TraceContext
  ): Future[Option[PackageVersion]] =
    connection
      .getSupportedPackageVersion(
        synchronizerId,
        Seq(pkg.packageName -> Seq(p)),
        clock.now,
      )
      .map { prefs =>
        prefs
          .find(pref => pref.packageName == pkg.packageName)
          .map(pkg => PackageVersion.assertFromString(pkg.packageVersion))
      }

  def lookupVettingState(p: PartyId, pkg: PackageIdResolver.Package)(implicit
      tc: TraceContext
  ): Future[Option[PackageVersion]] =
    vettingCache.get((p, pkg))

  def lookupVettingState(parties: Seq[PartyId], pkg: PackageIdResolver.Package)(implicit
      tc: TraceContext
  ): Future[Option[PackageVersion]] = parties match {
    case hd +: tl =>
      lookupVettingState(hd, pkg).flatMap(init =>
        MonadUtil.foldLeftM(init, tl) { case (accO, p) =>
          accO match {
            case None => Future.successful(None)
            case Some(acc) =>
              // This is approximating the minimum version as a viable one. That's correct if there is an overlap in versions.
              // It's incorrect if there is no overlap in the vetted versions but then there is really not much we can do with that contract anyway.
              lookupVettingState(p, pkg).map(_.map(v => if (v < acc) v else acc))
          }
        }
      )
    case _ =>
      Future.failed(
        new IllegalArgumentException("Expected non-empty list of parties in lookupVettingState")
      )
  }

  def splitBatch[T](pkg: PackageIdResolver.Package, informees: T => Seq[PartyId], batch: Seq[T])(
      implicit tc: TraceContext
  ): Future[Map[Option[PackageVersion], Seq[T]]] =
    for {
      versionedBatch <- MonadUtil.sequentialTraverse(batch)(t =>
        lookupVettingState(informees(t), pkg).map(_ -> t)
      )
    } yield versionedBatch.groupMapReduce(_._1)(p => Seq(p._2))(_ ++ _)

  override def onClosed() = {
    metrics.closeAcquired()
  }
}

object PackageVettingService {
  case class CacheConfig(
      size: NonNegativeLong = NonNegativeLong.tryCreate(10000),
      ttl: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofMinutes(10),
  )
}
