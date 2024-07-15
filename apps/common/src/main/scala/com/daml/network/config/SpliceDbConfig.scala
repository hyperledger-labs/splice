// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.config

import cats.data.EitherT
import cats.implicits.*
import com.digitalasset.canton.config.*
import com.digitalasset.canton.lifecycle.{CloseContext, UnlessShutdown}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.metrics.DbStorageMetrics
import com.digitalasset.canton.resource.{DbStorageSingle, Storage, StorageFactory}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import com.typesafe.config.Config

import java.util.concurrent.ScheduledExecutorService
import scala.concurrent.ExecutionContext

sealed trait SpliceDbConfig extends StorageConfig

object SpliceDbConfig {
  // Copy of Postgres in CommunityDbConfig, but with our own migration paths
  case class Postgres(
      override val config: Config,
      override val parameters: DbParametersConfig = DbParametersConfig(),
  ) extends SpliceDbConfig
      with PostgresDbConfig {
    override type Self = Postgres

    override protected val stableMigrationPath: String = postgresMigrationsPathStable
    override protected val devMigrationPath: String = postgresMigrationsPathDev

  }

  private val stableDir = "stable"
  private val devDir = "dev"
  private val baseMigrationsPath: String = "classpath:db/migration/canton-network/"
  private val basePostgresMigrationsPath: String = baseMigrationsPath + "postgres/"
  private val postgresMigrationsPathStable: String = basePostgresMigrationsPath + stableDir
  private val postgresMigrationsPathDev: String = basePostgresMigrationsPath + devDir
}

// Copy of canton's CommunityStorageFactory that is not tied to CommunityStorageConfig
class ANStorageFactory(val config: SpliceDbConfig) extends StorageFactory {
  override def create(
      connectionPoolForParticipant: Boolean,
      logQueryCost: Option[QueryCostMonitoringConfig],
      clock: Clock,
      scheduler: Option[ScheduledExecutorService],
      metrics: DbStorageMetrics,
      timeouts: ProcessingTimeout,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
      closeContext: CloseContext,
  ): EitherT[UnlessShutdown, String, Storage] = {
    config match {
      case config: SpliceDbConfig.Postgres =>
        DbStorageSingle
          .create(
            config,
            connectionPoolForParticipant,
            logQueryCost,
            clock,
            scheduler,
            metrics,
            timeouts,
            loggerFactory,
          )
          .widen[Storage]
    }
  }
}
