package org.lfdecentralizedtrust.splice.performance.tests

import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.{ProcessingTimeout, StorageConfig}
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.typesafe.config.Config
import org.apache.pekko.actor.ActorSystem
import org.lfdecentralizedtrust.splice.config.{IngestionConfig, SpliceConfig}
import org.lfdecentralizedtrust.splice.http.v0.definitions.DamlValueEncoding
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.scan.admin.http.{
  ExternalHashInclusionPolicy,
  ScanHttpEncodings,
}
import org.lfdecentralizedtrust.splice.scan.config.ScanAppBackendConfig
import org.lfdecentralizedtrust.splice.store.{
  HistoryMetrics,
  TreeUpdateWithMigrationId,
  UpdateHistory,
}
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

import java.nio.file.Path
import scala.concurrent.{ExecutionContext, Future}

/** Read performance test for UpdateHistory.
  *
  * Ingests tx updates from a JSON dump into UpdateHistory, then benchmarks
  * reading each update back via getUpdate(updateId).
  */
class UpdateHistoryReadPerformanceTest(
    dsoParty: PartyId,
    migrationId: Long,
    updateHistoryDumpPath: Path,
    storageConfig: StorageConfig,
    timeouts: ProcessingTimeout,
    loggerFactory: NamedLoggerFactory,
    ingestionConfig: IngestionConfig = IngestionConfig(),
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

  override protected def readOperations(
      store: UpdateHistory,
      txs: Seq[TreeUpdateWithMigrationId],
  ): Seq[ReadOperation] =
    Seq(
      getUpdateByIdOperation(store, txs),
      encodeUpdateOperation(txs),
    )

  /** Fetches the update from the store and decode. */
  private def getUpdateByIdOperation(
      store: UpdateHistory,
      txs: Seq[TreeUpdateWithMigrationId],
  ): ReadOperation = {
    val updateIds = txs.map(_.update.update.updateId)
    ReadOperation(
      name = "getUpdate",
      execute = { implicit tc: TraceContext =>
        updateIds.foldLeft(Future.successful(())) { (accF, updateId) =>
          accF.flatMap { _ =>
            store.getUpdate(updateId).map(_ => ())
          }
        }
      },
    )
  }

  /** Runs ScanHttpEncodings.encodeUpdate. This isolates the
    * cost of the encoding step from the cost of the DB read.
    */
  private def encodeUpdateOperation(
      txs: Seq[TreeUpdateWithMigrationId]
  ): ReadOperation = {
    ReadOperation(
      name = "encodeUpdate",
      execute = { implicit tc: TraceContext =>
        implicit val elc: ErrorLoggingContext =
          ErrorLoggingContext.fromTracedLogger(logger)(tc)
        Future {
          txs.foreach { tx =>
            val _ = ScanHttpEncodings.encodeUpdate(
              tx,
              encoding = DamlValueEncoding.members.CompactJson,
              version = ScanHttpEncodings.V1,
              hashInclusionPolicy = ExternalHashInclusionPolicy.ApplyThreshold,
              externalTransactionHashThresholdTime =
                ScanAppBackendConfig.DefaultExternalTransactionHashThresholdTime,
            )
          }
        }
      },
    )
  }

  override protected def verifyReadResults(
      store: UpdateHistory,
      originalTxs: Seq[TreeUpdateWithMigrationId],
  )(implicit tc: TraceContext): Future[Unit] = {
    originalTxs.foldLeft(Future.successful(())) { (accF, originalTx) =>
      accF.flatMap { _ =>
        val updateId = originalTx.update.update.updateId
        store.getUpdate(updateId).map {
          case Some(readBack) =>
            if (readBack.update.update.updateId != originalTx.update.update.updateId) {
              throw new AssertionError(
                s"Update ID mismatch: expected ${originalTx.update.update.updateId}, got ${readBack.update.update.updateId}"
              )
            }
            if (readBack.migrationId != originalTx.migrationId) {
              throw new AssertionError(
                s"Migration ID mismatch for update $updateId: expected ${originalTx.migrationId}, got ${readBack.migrationId}"
              )
            }
            if (readBack.update.update.recordTime != originalTx.update.update.recordTime) {
              throw new AssertionError(
                s"Record time mismatch for update $updateId: expected ${originalTx.update.update.recordTime}, got ${readBack.update.update.recordTime}"
              )
            }
          case None =>
            throw new AssertionError(
              s"Update $updateId was ingested but not found during verification"
            )
        }
      }
    }
  }
}

object UpdateHistoryReadPerformanceTest {

  case class UpdateHistoryReadPerformanceTestConfig(
      dsoParty: String,
      migrationId: Long,
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
          ),
      )
  }
}
