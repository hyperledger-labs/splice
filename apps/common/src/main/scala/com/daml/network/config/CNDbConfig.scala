package com.daml.network.config

import cats.data.EitherT
import cats.implicits.*
import com.digitalasset.canton.config.*
import com.digitalasset.canton.lifecycle.{CloseContext, UnlessShutdown}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.metrics.DbStorageMetrics
import com.digitalasset.canton.resource.{DbStorageSingle, MemoryStorage, Storage, StorageFactory}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import com.typesafe.config.{Config, ConfigFactory}

import java.util.concurrent.ScheduledExecutorService
import scala.concurrent.ExecutionContext

sealed trait CNDbConfig extends StorageConfig

object CNDbConfig {
  // Copy of Postgres in CommunityDbConfig, but with our own migration paths
  case class Postgres(
      override val config: Config,
      override val parameters: DbParametersConfig = DbParametersConfig(),
  ) extends CNDbConfig
      with PostgresDbConfig {
    override type Self = Postgres

    // TODO (#4420): replace with the actual migration paths
    override protected val stableMigrationPath: String = DbConfig.postgresMigrationsPathStable
    override protected val devMigrationPath: String = DbConfig.postgresMigrationsPathDev

  }

  case class Memory(
      override val config: Config = ConfigFactory.empty(),
      override val parameters: DbParametersConfig = DbParametersConfig(),
  ) extends CNDbConfig
      with MemoryStorageConfig {
    override type Self = Memory
  }

}

// Copy of canton's CommunityStorageFactory that is not tied to CommunityStorageConfig
class CNStorageFactory(val config: CNDbConfig) extends StorageFactory {
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
      case config: CNDbConfig.Postgres =>
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
      case _: CNDbConfig.Memory =>
        EitherT.rightT(new MemoryStorage(loggerFactory, timeouts))
    }
  }
}
