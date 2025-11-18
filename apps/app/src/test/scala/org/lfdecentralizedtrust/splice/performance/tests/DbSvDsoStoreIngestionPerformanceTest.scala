package org.lfdecentralizedtrust.splice.performance.tests

import cats.data.NonEmptyList
import com.daml.ledger.api.v2.TraceContextOuterClass
import com.daml.ledger.javaapi.data.{CreatedEvent, Identifier, Transaction}
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import com.google.protobuf.ByteString
import org.apache.pekko.Done
import org.apache.pekko.stream.connectors.csv.scaladsl.*
import org.apache.pekko.stream.scaladsl.{FileIO, Sink}
import org.lfdecentralizedtrust.splice.config.IngestionConfig
import org.lfdecentralizedtrust.splice.environment.ledger.api.{
  TransactionTreeUpdate,
  TreeUpdateOrOffsetCheckpoint,
}
import org.lfdecentralizedtrust.splice.environment.{DarResources, RetryProvider}
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.store.StoreTest
import org.lfdecentralizedtrust.splice.store.db.SplicePostgresTest
import org.lfdecentralizedtrust.splice.sv.store.SvStore
import org.lfdecentralizedtrust.splice.sv.store.db.DbSvDsoStore
import org.lfdecentralizedtrust.splice.util.{
  PackageQualifiedName,
  ResourceTemplateDecoder,
  TemplateJsonDecoder,
  ValueJsonCodecProtobuf as ProtobufCodec,
}
import org.scalatest.concurrent.PatienceConfiguration

import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.util.UUID
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*

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
    FileIO
      .fromPath(Paths.get(getClass.getResource("/performance/creates.csv").toURI))
      .via(CsvParsing.lineScanner(maximumLineLength = Int.MaxValue))
      .via(CsvToMap.toMapAsStrings(StandardCharsets.UTF_8))
      .batch(ingestionConfig.maxBatchSize.toLong, Vector(_))(_ :+ _)
      .zipWithIndex
      .runWith(Sink.foreachAsync(parallelism = 1) { case (_batch, index) =>
        val batch = _batch.map(_.map { case (key, value) =>
          key -> value.replace(
            "DSO::12209471e1a52edc2995ad347371597a5872f2704cb2cb4bb330a849e7309598259e",
            dsoParty.toProtoPrimitive,
          )
        })
        println(s"Ingesting batch $index of ${batch.length} elements")
        val txs = batch.map { line =>
          val synchronizerId = SynchronizerId.tryFromString(line("domain_id"))
          val recordTime = CantonTimestamp.MinValue.plusSeconds(index)
          val templateId = new Identifier(
            line("template_id_package_id"),
            line("template_id_module_name"),
            line("template_id_entity_name"),
          )
          val packageName = PackageQualifiedName.getFromResources(templateId).packageName
          TreeUpdateOrOffsetCheckpoint.Update(
            update = TransactionTreeUpdate(
              new Transaction(
                UUID.randomUUID().toString, // updateId
                "canton-network-acs-import-something", // commandId
                "canton-network-acs-import-something", // workflowId
                recordTime.toInstant, // effectiveAt
                java.util.List.of(
                  new CreatedEvent(
                    parseArray(line("observers")), // witnessParties
                    index, // offset
                    1, // nodeId
                    templateId, // templateId
                    packageName, // packageName
                    line("contract_id"), // contractId
                    ProtobufCodec
                      .deserializeValue(line("create_arguments"))
                      .asRecord()
                      .get(), // arguments
                    ByteString.copyFromUtf8(line("create_arguments")), // createdEventBlob
                    java.util.Map.of(), // interfaceViews
                    java.util.Map.of(), // failedInterfaceViews
                    java.util.Optional.empty(), // contractKey
                    parseArray(line("signatories")), // signatories
                    parseArray(line("observers")), // observers
                    recordTime.toInstant, // createdAt
                    false, // acsDelta
                    templateId.getPackageId, // representativePackageId
                  )
                ), // events
                index, // offset
                synchronizerId.toProtoPrimitive,
                TraceContextOuterClass.TraceContext.newBuilder().build(),
                recordTime.toInstant,
              )
            ),
            synchronizerId = synchronizerId,
          )
        }

        val before = System.nanoTime()
        store.multiDomainAcsStore.ingestionSink
          .ingestUpdateBatch(NonEmptyList.fromListUnsafe(txs.toList))
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
      .valueOrFail("count is there") should be >= 500_000
  }

  private def parseArray(str: String) = {
    str
      .stripPrefix("{")
      .stripSuffix("}")
      .split(",")
      .toList
      .map(_.trim)
      .filter(_.nonEmpty)
      .asJava
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
