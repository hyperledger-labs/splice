package org.lfdecentralizedtrust.splice.store.db

import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.HasActorSystem
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.store.db.DbTest
import com.digitalasset.canton.topology.ParticipantId
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.environment.{DarResources, RetryProvider}
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.store.StoreTest.testTxLogConfig
import org.lfdecentralizedtrust.splice.store.{
  MultiDomainAcsStore,
  MultiDomainAcsStoreTest,
  TestTxLogEntry,
}
import org.lfdecentralizedtrust.splice.util.{ResourceTemplateDecoder, TemplateJsonDecoder}
import slick.jdbc.JdbcProfile

import scala.concurrent.Future

class IngestionPerfTest
    extends MultiDomainAcsStoreTest[DbMultiDomainAcsStore[TestTxLogEntry]]
    with SplicePostgresTest
    with DbTest.DisableDbStorageIdempotency
    with HasActorSystem
    with AcsJdbcTypes {

  override lazy val profile: JdbcProfile = storage.api.jdbcProfile

  "IngestionPerfTest" should {

    "Create and Archive many events and measure performance" in {
      implicit val store =
        mkStore(acsId = 1, txLogId = Some(1), 1L, ParticipantId("IngestPerfTest"))

      for {
        _ <- initWithAcs()(store)
        // TODO use more realistic data, and ingest different kinds of events and combinations of creates, exercises and archives
        // Read from CILR, generate a dump of a reasonable size, and the use that as a consistent input instead.
        nrEvents = 1000
        coupons = (0 to nrEvents).map(i => c(i))
        start = System.currentTimeMillis()
        _ <- Future.traverse(coupons)(c => d1.create(c)(store))
        _ <- Future.traverse(coupons)(c => d1.archive(c)(store))
        end = System.currentTimeMillis()
        duration = end - start
        _ = println(s"Ingestion of $nrEvents took $duration ms")
        // verify with sql that the data did actually get ingested
      } yield succeed
    }
  }

  override def mkStore(
      acsId: Int,
      txLogId: Option[Int],
      migrationId: Long,
      participantId: ParticipantId,
      filter: MultiDomainAcsStore.ContractFilter[
        GenericAcsRowData,
        GenericInterfaceRowData,
      ],
  ) = {
    mkStoreWithAcsRowDataF(
      acsId,
      txLogId,
      migrationId,
      participantId,
      filter,
      "acs_store_template",
      txLogId.map(_ => "txlog_store_template"),
      Some("interface_views_template"),
    )
  }

  def mkStoreWithAcsRowDataF[R <: AcsRowData](
      acsId: Int,
      txLogId: Option[Int],
      migrationId: Long,
      participantId: ParticipantId,
      filter: MultiDomainAcsStore.ContractFilter[R, GenericInterfaceRowData],
      acsTableName: String,
      txLogTableName: Option[String],
      interfaceViewsTableNameOpt: Option[String],
  ) = {
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResources(
        DarResources.amulet.all ++ DarResources.TokenStandard.allPackageResources.flatMap(_.all)
      )
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)

    new DbMultiDomainAcsStore(
      storage,
      acsTableName,
      txLogTableName,
      interfaceViewsTableNameOpt,
      storeDescriptor(acsId, participantId),
      txLogId.map(storeDescriptor(_, participantId)),
      loggerFactory,
      filter,
      testTxLogConfig,
      DomainMigrationInfo(
        migrationId,
        None,
      ),
      participantId,
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
    )
  }

  private def storeDescriptor(id: Int, participantId: ParticipantId) =
    DbMultiDomainAcsStore.StoreDescriptor(
      version = 1,
      name = "IngestionPerfTest",
      party = dsoParty,
      participant = participantId,
      key = Map(
        "id" -> id.toString
      ),
    )

  /** Hook for cleaning database before running next test. */
  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext) = {
    for {
      _ <- resetAllAppTables(storage)
    } yield ()
  }
}
