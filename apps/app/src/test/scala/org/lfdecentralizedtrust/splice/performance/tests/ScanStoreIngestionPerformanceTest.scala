package org.lfdecentralizedtrust.splice.performance.tests

import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.{ProcessingTimeout, StorageConfig}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.NoReportingTracerProvider
import com.typesafe.config.Config
import org.apache.pekko.actor.ActorSystem
import org.lfdecentralizedtrust.splice.config.{IngestionConfig, SpliceConfig}
import org.lfdecentralizedtrust.splice.environment.{DarResources, RetryProvider}
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.scan.store.ScanStore
import org.lfdecentralizedtrust.splice.scan.store.db.{DbScanStore, DbScanStoreMetrics, ScanTables}
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore
import org.lfdecentralizedtrust.splice.util.{ResourceTemplateDecoder, TemplateJsonDecoder}
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

import java.nio.file.Path
import scala.concurrent.ExecutionContext

class ScanStoreIngestionPerformanceTest(
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

  override type Store = MultiDomainAcsStore

  override protected val tablesToSanityCheck: Seq[String] =
    Seq(ScanTables.acsTableName, ScanTables.txLogTableName)

  override protected def mkStore(storage: DbStorage): MultiDomainAcsStore = {
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResources(
        DarResources.amulet.all ++
          DarResources.validatorLifecycle.all ++
          DarResources.dsoGovernance.all
      )
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)
    new DbScanStore(
      ScanStore.Key(dsoParty),
      storage,
      isFirstSv = true,
      loggerFactory,
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory)(
        NoReportingTracerProvider.tracer
      ),
      _ => throw new RuntimeException("Scan Aggregates do not matter for this test."),
      DomainMigrationInfo(
        migrationId,
        None,
      ),
      participantId = mkParticipantId("IngestionPerformanceIngestionTest"),
      IngestionConfig(),
      new DbScanStoreMetrics(NoOpMetricsFactory, loggerFactory, timeouts),
      0L,
    )(ec, templateJsonDecoder, closeContext).multiDomainAcsStore
  }

}

object ScanStoreIngestionPerformanceTest {

  case class ScanStoreIngestionPerformanceTestConfig(
      dsoParty: String,
      migrationId: Long,
  )
  private implicit val configReader: ConfigReader[ScanStoreIngestionPerformanceTestConfig] =
    deriveReader[ScanStoreIngestionPerformanceTestConfig]

  def tryCreate(updateHistoryDumpPath: Path, config: Config, loggerFactory: NamedLoggerFactory)(
      implicit
      ec: ExecutionContext,
      actorSystem: ActorSystem,
  ): ScanStoreIngestionPerformanceTest = {
    val spliceConfig = SpliceConfig.loadOrThrow(config)
    val sv1Config = spliceConfig.scanApps.getOrElse(
      InstanceName.tryCreate("sv1Scan"),
      throw new IllegalArgumentException("SV1 config not found"),
    )

    configReader
      .from(config.getValue("performance.tests.DbSvDsoStore"))
      .fold(
        err =>
          throw new IllegalArgumentException(
            s"Failed to read SvDsoStoreIngestionPerformanceTest config: $err"
          ),
        cfg =>
          new ScanStoreIngestionPerformanceTest(
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
