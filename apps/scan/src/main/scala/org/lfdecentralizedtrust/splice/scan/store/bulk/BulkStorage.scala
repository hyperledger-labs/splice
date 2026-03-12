// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.daml.metrics.api.MetricHandle.LabeledMetricsFactory
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, FlagCloseableAsync}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.actor.ActorSystem
import org.lfdecentralizedtrust.splice.PekkoRetryingService
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.scan.config.{BulkStorageConfig, ScanStorageConfig}
import org.lfdecentralizedtrust.splice.scan.store.{AcsSnapshotStore, ScanKeyValueProvider}
import org.lfdecentralizedtrust.splice.store.{HistoryMetrics, S3BucketConnection, UpdateHistory}

import scala.concurrent.ExecutionContext

class BulkStorage(
    storageConfig: ScanStorageConfig,
    appConfig: BulkStorageConfig,
    acsSnapshotStore: AcsSnapshotStore,
    updateHistory: UpdateHistory,
    currentMigrationId: Long,
    kvProvider: ScanKeyValueProvider,
    metricsFactory: LabeledMetricsFactory,
    automationConfig: AutomationConfig,
    backoffClock: Clock,
    override protected val retryProvider: RetryProvider,
    override val loggerFactory: NamedLoggerFactory,
)(implicit actorSystem: ActorSystem, tc: TraceContext, ec: ExecutionContext, tracer: Tracer)
    extends NamedLogging
    with FlagCloseableAsync
    with RetryProvider.Has {

  private val services = appConfig.s3.fold {
    logger.debug("s3 connection not configured, not dumping to bulk storage")
    Seq.empty[PekkoRetryingService[?]]
  } { s3Config =>
    val s3Connection = S3BucketConnection(s3Config, loggerFactory)
    val historyMetrics = HistoryMetrics(metricsFactory, currentMigrationId)

    Seq(
      new AcsSnapshotBulkStorage(
        storageConfig,
        appConfig,
        acsSnapshotStore,
        updateHistory,
        s3Connection,
        kvProvider,
        historyMetrics,
        loggerFactory,
      ).asRetryableService(automationConfig, backoffClock, retryProvider),
      new UpdateHistoryBulkStorage(
        storageConfig,
        appConfig,
        updateHistory,
        kvProvider,
        currentMigrationId,
        s3Connection,
        historyMetrics,
        loggerFactory,
      ).asRetryableService(automationConfig, backoffClock, retryProvider),
    )
  }
  final override def closeAsync(): Seq[AsyncOrSyncCloseable] = {
    services.flatMap(_.closeAsync())
  }
}
