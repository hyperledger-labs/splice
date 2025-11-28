package org.lfdecentralizedtrust.splice.performance.tests

import cats.data.NonEmptyList
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import org.apache.pekko.Done
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.lfdecentralizedtrust.splice.config.IngestionConfig
import org.lfdecentralizedtrust.splice.environment.ledger.api.TreeUpdateOrOffsetCheckpoint
import org.lfdecentralizedtrust.splice.environment.{DarResources, RetryProvider}
import org.lfdecentralizedtrust.splice.http.v0.definitions.{
  UpdateHistoryItemV2,
  UpdateHistoryResponseV2,
}
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.scan.admin.http.CompactJsonScanHttpEncodings
import org.lfdecentralizedtrust.splice.store.StoreTest
import org.lfdecentralizedtrust.splice.store.db.SplicePostgresTest
import org.lfdecentralizedtrust.splice.sv.store.SvStore
import org.lfdecentralizedtrust.splice.sv.store.db.DbSvDsoStore
import org.lfdecentralizedtrust.splice.util.{ResourceTemplateDecoder, TemplateJsonDecoder}
import org.scalatest.concurrent.PatienceConfiguration

import java.nio.file.{Files, Paths}
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

class DbSvDsoStoreIngestionPerformanceTest
    extends StoreTest
    with SplicePostgresTest
    with HasActorSystem
    with HasExecutionContext {

  "Ingestion performance test" in {
    // read csv file into pekko stream

    val store = mkStore()
    store.multiDomainAcsStore.ingestionSink.initialize().futureValue
    val timings = mutable.ListBuffer[Long]()
    val ingestionConfig = IngestionConfig()
    val dumpFile = Paths.get("/home/oriolmunoz/mainnetdump/update_history_response.json")
    val dump = (for {
      json <- io.circe.parser
        .parse(
          Files
            .readString(dumpFile)
            .replace( // TODO: maybe just use the store with dsoParty=mainnet's
              "DSO::1220b1431ef217342db44d516bb9befde802be7d8899637d290895fa58880f19accc",
              dsoParty.toProtoPrimitive,
            )
        )
      decoded <- UpdateHistoryResponseV2.decodeUpdateHistoryResponseV2.decodeJson(json)
    } yield decoded)
      .getOrElse(
        throw new IllegalArgumentException(
          "Failed to parse the update history dump provided. It should have the structure of UpdateHistoryResponseV2."
        )
      )
    val txs = dump.transactions.collect {
      // deliberately ignoring reassignments
      case UpdateHistoryItemV2.members.UpdateHistoryTransactionV2(update) =>
        CompactJsonScanHttpEncodings.httpToLapiUpdate(update)
    }

    Source
      .fromIterator(() => txs.iterator)
      .batch(ingestionConfig.maxBatchSize.toLong, Vector(_))(_ :+ _)
      .zipWithIndex
      .runWith(Sink.foreachAsync(parallelism = 1) { case (batch, index) =>
        println(s"Ingesting batch $index of ${batch.length} elements")
        val before = System.nanoTime()
        store.multiDomainAcsStore.ingestionSink
          .ingestUpdateBatch(
            NonEmptyList.fromListUnsafe(
              txs
                .map(tx =>
                  TreeUpdateOrOffsetCheckpoint.Update(tx.update.update, tx.update.synchronizerId)
                )
                .toList
            )
          )
          .map { _ =>
            val after = System.nanoTime()
            val duration = after - before
            timings ++= Seq.fill(batch.length)(duration / batch.length)
            val avg = timings.sum.toDouble / timings.size
            println(
              f"Ingested batch $index (${batch.length} elements) in $duration ns, average per-item time: $avg%.2f ns over ${timings.size} records, total time: ${timings.sum} ns"
            )
          }
      })
      .futureValue(timeout = PatienceConfiguration.Timeout(FiniteDuration(12, "hours"))) should be(
      Done
    )

    import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
    storage
      .querySingle(
        sql"select count(*) from dso_acs_store".as[Int].headOption,
        "count",
      )
      .value
      .failOnShutdown("")
      .futureValue
      .valueOrFail("count is there") should be >= 30_312
  }

  private val storeSvParty = providerParty(42)
  def mkStore() = {
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResources(
        DarResources.amulet.all ++
          DarResources.validatorLifecycle.all ++
          DarResources.dsoGovernance.all
      )
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)
    new DbSvDsoStore(
      SvStore.Key(storeSvParty, dsoParty),
      storage,
      loggerFactory,
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
      DomainMigrationInfo(
        domainMigrationId,
        None,
      ),
      participantId = mkParticipantId("IngestionPerformanceIngestionTest"),
      IngestionConfig(),
    )(parallelExecutionContext, templateJsonDecoder, closeContext)
  }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] = {
    for {
      _ <- resetAllAppTables(storage)
    } yield ()
  }
}
