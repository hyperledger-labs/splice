package org.lfdecentralizedtrust.splice.performance.tests

import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.{ProcessingTimeout, StorageConfig}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.PartyId
import com.typesafe.config.Config
import org.apache.pekko.actor.ActorSystem
import org.lfdecentralizedtrust.splice.config.{IngestionConfig, SpliceConfig}
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.store.{HistoryMetrics, UpdateHistory}
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

import java.nio.file.Path
import scala.concurrent.ExecutionContext

class UpdateHistoryIngestionPerformanceTest(
    dsoParty: PartyId,
    migrationId: Long,
    updateHistoryDumpPath: Path,
    storageConfig: StorageConfig,
    timeouts: ProcessingTimeout,
    loggerFactory: NamedLoggerFactory,
    ingestionConfig: IngestionConfig = IngestionConfig(),
)(implicit ec: ExecutionContext, actorSystem: ActorSystem)
    extends StoreIngestionPerformanceTest(
      updateHistoryDumpPath,
      storageConfig,
      timeouts,
      loggerFactory,
      ingestionConfig,
    ) {

  override type Store = UpdateHistory
  override protected val tablesToSanityCheck: Seq[String] = Seq(
    "update_history_transactions",
    "update_history_creates",
    "update_history_exercises",
    "update_history_last_ingested_offsets",
  )

  override protected def mkStore(storage: DbStorage): UpdateHistory = {
    new UpdateHistory(
      storage = storage,
      domainMigrationInfo = DomainMigrationInfo(migrationId, None),
      storeName = this.getClass.getName,
      participantId = mkParticipantId(this.getClass.getSimpleName),
      updateStreamParty = dsoParty,
      backfillingRequired = UpdateHistory.BackfillingRequirement.BackfillingNotRequired,
      loggerFactory = loggerFactory,
      enableissue12777Workaround = true,
      enableImportUpdateBackfill = false,
      metrics = HistoryMetrics.apply(NoOpMetricsFactory, migrationId),
    )
  }

}

object UpdateHistoryIngestionPerformanceTest {

  case class UpdateHistoryIngestionPerformanceTestConfig(
      dsoParty: String,
      migrationId: Long,
  )
  private implicit val configReader: ConfigReader[UpdateHistoryIngestionPerformanceTestConfig] =
    deriveReader[UpdateHistoryIngestionPerformanceTestConfig]

  def tryCreate(updateHistoryDumpPath: Path, config: Config, loggerFactory: NamedLoggerFactory)(
      implicit
      ec: ExecutionContext,
      actorSystem: ActorSystem,
  ): UpdateHistoryIngestionPerformanceTest = {
    val spliceConfig = SpliceConfig.loadOrThrow(config)
    val sv1Config = spliceConfig.scanApps.getOrElse(
      InstanceName.tryCreate("sv1Scan"),
      throw new IllegalArgumentException("SV1 config not found"),
    )

    configReader
      .from(config.getValue("performance.tests.UpdateHistoryIngestionPerformanceTest"))
      .fold(
        err =>
          throw new IllegalArgumentException(
            s"Failed to read SvDsoStoreIngestionPerformanceTest config: $err"
          ),
        cfg =>
          new UpdateHistoryIngestionPerformanceTest(
            PartyId.tryFromProtoPrimitive(cfg.dsoParty),
            cfg.migrationId,
            updateHistoryDumpPath,
            sv1Config.storage,
            spliceConfig.parameters.timeouts.processing,
            loggerFactory,
            sv1Config.automation.ingestion,
          ),
      )
  }
}
