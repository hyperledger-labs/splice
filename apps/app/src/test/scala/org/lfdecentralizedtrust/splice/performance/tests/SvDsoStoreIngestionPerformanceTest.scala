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
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore
import org.lfdecentralizedtrust.splice.sv.store.SvStore
import org.lfdecentralizedtrust.splice.sv.store.db.DbSvDsoStore
import org.lfdecentralizedtrust.splice.util.{ResourceTemplateDecoder, TemplateJsonDecoder}
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

import java.nio.file.Path
import scala.concurrent.ExecutionContext

class SvDsoStoreIngestionPerformanceTest(
    svParty: PartyId,
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

  override protected def mkStore(storage: DbStorage): MultiDomainAcsStore = {
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResources(
        DarResources.amulet.all ++
          DarResources.validatorLifecycle.all ++
          DarResources.dsoGovernance.all
      )
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)
    new DbSvDsoStore(
      SvStore.Key(svParty, dsoParty),
      storage,
      loggerFactory,
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory)(
        NoReportingTracerProvider.tracer
      ),
      DomainMigrationInfo(
        migrationId,
        None,
      ),
      participantId = mkParticipantId("IngestionPerformanceIngestionTest"),
      IngestionConfig(),
    )(ec, templateJsonDecoder, closeContext).multiDomainAcsStore
  }

}

object SvDsoStoreIngestionPerformanceTest {

  case class SvDsoStoreIngestionPerformanceTestConfig(
      svParty: String,
      dsoParty: String,
      migrationId: Long,
  )
  private implicit val configReader: ConfigReader[SvDsoStoreIngestionPerformanceTestConfig] =
    deriveReader[SvDsoStoreIngestionPerformanceTestConfig]

  def tryCreate(updateHistoryDumpPath: Path, config: Config, loggerFactory: NamedLoggerFactory)(
      implicit
      ec: ExecutionContext,
      actorSystem: ActorSystem,
  ): SvDsoStoreIngestionPerformanceTest = {
    val spliceConfig = SpliceConfig.loadOrThrow(config)
    val sv1Config = spliceConfig.svApps.getOrElse(
      InstanceName.tryCreate("sv1"),
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
          new SvDsoStoreIngestionPerformanceTest(
            PartyId.tryFromProtoPrimitive(cfg.svParty),
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
