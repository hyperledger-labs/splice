package org.lfdecentralizedtrust.splice.performance.tests

import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.{ProcessingTimeout, StorageConfig}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.typesafe.config.Config
import org.apache.pekko.actor.ActorSystem
import org.lfdecentralizedtrust.splice.config.{IngestionConfig, SpliceConfig}
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.store.{HistoryMetrics, UpdateHistory}
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

import java.nio.file.Path
import scala.concurrent.{ExecutionContext, Future}

/** Read performance test for UpdateHistory.
  *
  * Ingests tx updates from a JSON dump into UpdateHistory, then benchmarks
  * reading each update back via `getUpdate(updateId)`.
  */
class UpdateHistoryReadPerformanceTest(
    dsoParty: PartyId,
    migrationId: Long,
    updateHistoryDumpPath: Path,
    storageConfig: StorageConfig,
    timeouts: ProcessingTimeout,
    loggerFactory: NamedLoggerFactory,
    ingestionConfig: IngestionConfig = IngestionConfig(),
    readIterations: Int = 10,
)(implicit ec: ExecutionContext, actorSystem: ActorSystem)
    extends StoreReadPerformanceTest(
      updateHistoryDumpPath,
      storageConfig,
      timeouts,
      loggerFactory,
      ingestionConfig,
    ) {

  override type Store = UpdateHistory

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

  override protected def readOperations(store: UpdateHistory): Seq[ReadOperation] = {
    // Collect all update IDs from the ingested data to use for read benchmarks.
    // These are loaded from the same dump used for ingestion.
    val updateIds = loadUpdateIds()
    if (updateIds.isEmpty) {
      throw new IllegalStateException(
        "No update IDs found in the dump. Cannot run read benchmarks."
      )
    }

    Seq(
      ReadOperation(
        name = "getUpdateById",
        iterations = readIterations * updateIds.size,
        execute = { implicit tc: TraceContext =>
          // Read all updates sequentially, repeating `readIterations` times
          val iterations = (1 to readIterations).flatMap(_ => updateIds)
          iterations.foldLeft(Future.successful(0L)) { (accF, updateId) =>
            accF.flatMap { count =>
              store.getUpdate(updateId).map {
                case Some(_) => count + 1
                case None =>
                  throw new IllegalStateException(
                    s"Update $updateId was ingested but not found via getUpdate"
                  )
              }
            }
          }
        },
      )
    )
  }
}

object UpdateHistoryReadPerformanceTest {

  case class UpdateHistoryReadPerformanceTestConfig(
      dsoParty: String,
      migrationId: Long,
      readIterations: Option[Int],
  )
  private implicit val configReader: ConfigReader[UpdateHistoryReadPerformanceTestConfig] =
    deriveReader[UpdateHistoryReadPerformanceTestConfig]

  def tryCreate(updateHistoryDumpPath: Path, config: Config, loggerFactory: NamedLoggerFactory)(
      implicit
      ec: ExecutionContext,
      actorSystem: ActorSystem,
  ): UpdateHistoryReadPerformanceTest = {
    val spliceConfig = SpliceConfig.loadOrThrow(config)
    val sv1Config = spliceConfig.scanApps.getOrElse(
      InstanceName.tryCreate("sv1Scan"),
      throw new IllegalArgumentException("SV1 config not found"),
    )

    configReader
      .from(config.getValue("performance.tests.UpdateHistoryRead"))
      .fold(
        err =>
          throw new IllegalArgumentException(
            s"Failed to read UpdateHistoryReadPerformanceTest config: $err"
          ),
        cfg =>
          new UpdateHistoryReadPerformanceTest(
            PartyId.tryFromProtoPrimitive(cfg.dsoParty),
            cfg.migrationId,
            updateHistoryDumpPath,
            sv1Config.storage,
            spliceConfig.parameters.timeouts.processing,
            loggerFactory,
            sv1Config.automation.ingestion,
            cfg.readIterations.getOrElse(10),
          ),
      )
  }
}
