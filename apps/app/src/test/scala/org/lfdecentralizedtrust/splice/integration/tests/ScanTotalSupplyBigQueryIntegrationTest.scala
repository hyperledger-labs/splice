package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.util.*
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import com.digitalasset.canton.BaseTest.getResourcePath
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.{HasCloseContext, FlagCloseable}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.daml.lf.data.Time.Timestamp as LfTimestamp
import com.google.cloud.bigquery as bq
import bq.{Field, JobInfo, Schema, TableId}
import bq.storage.v1.{JsonStreamWriter, TableSchema}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.*

import java.nio.file.Paths
import java.util.UUID
import scala.concurrent.duration.*
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*
import slick.jdbc.GetResult

import java.time.Duration

class ScanTotalSupplyBigQueryIntegrationTest
    extends SpliceTests.IntegrationTest
    with WalletTestUtil
    with SplitwellTestUtil
    with TimeTestUtil
    with HasActorSystem
    with HasExecutionContext
    with FlagCloseable
    with HasCloseContext
    with UpdateHistoryTestUtil {
  private val totalSupplySqlPath = getResourcePath("total-supply-bigquery.sql")

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)

  // BigQuery client instance and test dataset
  private lazy val bigquery: bq.BigQuery = bq.BigQueryOptions.getDefaultInstance.getService
  private val datasetName =
    s"scantotalsupply_test_dataset_${UUID.randomUUID().toString.replace("-", "_")}"
  private val (createsBqTableName, exercisesBqTableName) = {
    val prefix = "scan_sv_1_"
    (
      s"${prefix}update_history_creates",
      s"${prefix}update_history_exercises",
    )
  }

  // Test data parameters
  private val mintedAmount = BigDecimal("1000000")
  private val lockedAmount = BigDecimal("200000")
  private val burnedAmount = BigDecimal("304")
  private val unlockedAmount = mintedAmount - lockedAmount - burnedAmount
  private val unmintedAmount = BigDecimal("149927.0015223501")

  override def beforeAll() = {
    super.beforeAll()
    logger.info(s"Creating BigQuery dataset: $datasetName")

    // Create a temporary BigQuery dataset for testing; 1hr is the minimum
    // TODO (#1095) consider setting ACL to private
    val datasetInfo =
      bq.DatasetInfo.newBuilder(datasetName).setDefaultTableLifetime(1.hour.toMillis).build()
    bigquery.create(datasetInfo)

    createEmptyTables()
  }

  override def afterAll() = {
    logger.info(s"Cleaning up BigQuery dataset: $datasetName")

    // Delete the temporary BigQuery dataset after tests
    bigquery.delete(datasetName, bq.BigQuery.DatasetDeleteOption.deleteContents())
    super.afterAll()
  }

  "test bigquery queries" in { implicit env =>
    withClue("create test data on Splice ledger") {
      val (aliceParty, bobParty) = onboardAliceAndBob()

      // Create test data with more-or-less known amounts
      createTestData(aliceParty, bobParty)
    }

    withClue("exporting PostgreSQL tables to BigQuery") {
      exportPostgresToBigQuery()
    }

    val results = withClue("running total supply queries in BigQuery") {
      runTotalSupplyQueries()
    }

    withClue(s"verify total supply results") {
      verifyResults(results)
    }
  }

  import bq.storage.v1.TableFieldSchema as TFS

  private case class ConvertibleColumn(
      name: String,
      bqType: bq.LegacySQLTypeName,
      bqStreamType: TFS.Type,
      pgIsTextArray: Boolean,
  ) {
    def bqSchemaField =
      Field
        .newBuilder(name, bqType)
        .setMode(Field.Mode.NULLABLE)
        .build()

    def bqsSchemaField =
      bq.storage.v1.TableFieldSchema
        .newBuilder()
        .setName(name)
        .setType(bqStreamType)
        .setMode(TFS.Mode.NULLABLE)
        .build()
  }

  private def cc(name: String, bqType: bq.LegacySQLTypeName, pgIsTextArray: Boolean = false) =
    ConvertibleColumn(name, bqType, convertToBigQueryStorageType(bqType), pgIsTextArray)

  import bq.LegacySQLTypeName.{INTEGER, STRING, TIMESTAMP, JSON, BOOLEAN}

  // Datastream variant of update_history_creates
  // contains Amulet, LockedAmulet, UnclaimedReward, &c
  private val createsSchema = Seq(
    cc("history_id", INTEGER),
    cc("row_id", INTEGER),
    cc("ingested_at", TIMESTAMP),
    cc("event_id", STRING),
    cc("update_row_id", INTEGER),
    cc("contract_id", STRING),
    cc("created_at", INTEGER),
    cc("template_id_package_id", STRING),
    cc("template_id_module_name", STRING),
    cc("template_id_entity_name", STRING),
    cc("package_name", STRING),
    cc("create_arguments", JSON),
    cc("signatories", JSON, pgIsTextArray = true),
    cc("observers", JSON, pgIsTextArray = true),
    cc("contract_key", JSON),
    cc("record_time", INTEGER),
    cc("update_id", STRING),
    cc("domain_id", STRING),
    cc("migration_id", INTEGER),
  )

  // Datastream variant of update_history_exercises
  // contains AmuletRules_Transfer &c
  private val exercisesSchema = Seq(
    cc("history_id", INTEGER),
    cc("row_id", INTEGER),
    cc("ingested_at", TIMESTAMP),
    cc("event_id", STRING),
    cc("update_row_id", INTEGER),
    cc("child_event_ids", JSON, pgIsTextArray = true),
    cc("choice", STRING),
    cc("template_id_package_id", STRING),
    cc("template_id_module_name", STRING),
    cc("template_id_entity_name", STRING),
    cc("contract_id", STRING),
    cc("consuming", BOOLEAN),
    cc("argument", JSON),
    cc("result", JSON),
    cc("package_name", STRING),
    cc("interface_id_package_id", STRING),
    cc("interface_id_module_name", STRING),
    cc("interface_id_entity_name", STRING),
    cc("acting_parties", JSON, pgIsTextArray = true),
    cc("record_time", INTEGER),
    cc("update_id", STRING),
    cc("domain_id", STRING),
    cc("migration_id", INTEGER),
  )

  private type ConvertibleSchema = Seq[ConvertibleColumn]

  private def bqSchema(schema: ConvertibleSchema): Schema =
    Schema.of(schema.map(_.bqSchemaField)*)

  // create empty tables in BigQuery that match the schema inferred by Datastream,
  // less the datastream_metadata column (which we don't use)
  private def createEmptyTables(): Unit = {
    // row_id is primary key but this is not currently enforced even in actual
    // deployment
    createTable(createsBqTableName, createsSchema)
    createTable(exercisesBqTableName, exercisesSchema)
  }

  private def createTable(tableName: String, schema: ConvertibleSchema): Unit = {
    val tableId = TableId.of(datasetName, tableName)
    val tableDefinition = bq.StandardTableDefinition of bqSchema(schema)
    val tableInfo = bq.TableInfo.of(tableId, tableDefinition)
    bigquery.create(tableInfo)
  }

  /** Creates test data with known amounts for all metrics
    */
  private def createTestData(aliceParty: PartyId, bobParty: PartyId)(implicit
      env: FixtureParam
  ): Unit = {
    // TODO (#1095) use a realistic minting method; best not to support tap in the SQL
    withClue("step forward to an open round") {
      advanceTimeAndWaitForRoundAutomation(Duration.ofDays(10))
      advanceTimeToRoundOpen
    }
    aliceWalletClient.tap(walletAmuletToUsd(mintedAmount))

    // TODO (#1095) Lock a portion of Amulet (lockedAmount)
    val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()
    actAndCheck(
      "Lock amulet",
      lockAmulets(
        aliceValidatorBackend,
        aliceParty,
        aliceValidatorParty,
        aliceWalletClient.list().amulets,
        lockedAmount,
        sv1ScanBackend,
        java.time.Duration.ofHours(1),
        CantonTimestamp.now(),
      ),
    )(
      "Wait for locked amulet to appear",
      _ => aliceWalletClient.list().lockedAmulets.loneElement,
    )
    // TODO (#1095) Ensure some unminted exists (unmintedAmount)
    // TODO (#1095) Create UnclaimedReward contracts

    // burn fees
    val transferAmount = BigDecimal("100000")
    p2pTransfer(aliceWalletClient, bobWalletClient, bobParty, transferAmount)

    // maybe more burns
    // TODO (#1095) find expected non-zero burnedAmount
  }

  // copy from PostgreSQL tables to BigQuery
  private def exportPostgresToBigQuery()(implicit env: FixtureParam): Unit = {
    val sourceDb = sv1ScanBackend.appState.storage match {
      case db: DbStorage => db
      case s => fail(s"non-DB storage configured, unsupported for BigQuery: ${s.getClass}")
    }
    val sourceHistoryId = sv1ScanBackend.appState.store.updateHistory.historyId

    copyTableToBigQuery(
      "update_history_creates",
      createsBqTableName,
      createsSchema,
      sourceDb,
      sourceHistoryId,
    )
    copyTableToBigQuery(
      "update_history_exercises",
      exercisesBqTableName,
      exercisesSchema,
      sourceDb,
      sourceHistoryId,
    )
  }

  private def copyTableToBigQuery(
      sourceTable: String,
      targetTable: String,
      targetSchema: ConvertibleSchema,
      sourceDb: DbStorage,
      historyId: Long,
  ): Unit = {
    import org.json.JSONObject

    val writer = createJsonStreamWriter(targetTable, createStreamSchema(targetSchema))

    try {
      // runtime interpretation of bq Schema; convert Slick+PG to BigQuery
      implicit val r: GetResult[JSONObject] = interpretPGRowWithSchema(targetSchema)

      // Fetch all rows from the source table
      val fieldNames = targetSchema.view.map(_.name).mkString(", ")
      val rows = sourceDb
        .query(
          // we share table in testing, so filter by history_id to limit to sv1.
          // this is unnecessary in production
          sql"""SELECT #$fieldNames FROM #$sourceTable
            WHERE history_id = $historyId
             """.as[JSONObject],
          s"Export $sourceTable to BigQuery",
        )
        .futureValueUS

      logger.debug(
        s"Sending ${rows.size} rows to BigQuery $targetTable. Sample JSON row prepared for BigQuery: ${rows.headOption
            .map(_.toString(2))
            .getOrElse("empty")}"
      )

      // Stream rows to BigQuery in batches
      // see AppendRows request size on https://cloud.google.com/bigquery/quotas#write-api-limits
      val batchSize = 500
      Future
        .traverse(rows grouped batchSize) { batch =>
          import org.json.JSONArray
          Future(writer.append(new JSONArray(batch.asJava)).get())
            .recoverWith(reportAppendSerializationErrors)
        }
        .futureValue
    } finally {
      // Close the writer
      writer.close()
    }
  }

  private def interpretPGRowWithSchema(
      targetSchema: ConvertibleSchema
  ) = GetResult { r =>
    import org.json.{JSONArray, JSONObject}
    new JSONObject(
      targetSchema.view
        .map { field =>
          val n = field.name
          n -> {
            try
              field.bqType match {
                case STRING => r.rs.getString(n)
                case JSON =>
                  val raw = r.rs.getString(n)
                  if (!r.rs.wasNull) {
                    try {
                      if (field.pgIsTextArray)
                        r.rs.getArray(n).getArray match {
                          case a: Array[String] =>
                            new JSONArray(a.toSeq.asJava).toString
                          case e => fail(s"$e not a text array")
                        }
                      else {
                        // the only supported way to pass a JSON object within a
                        // JSON object property is in the form of the string that
                        // parses to that JSON
                        raw
                      }
                    } catch {
                      case e: org.json.JSONException =>
                        fail(s"error parsing JSON field $n, contents $raw", e)
                    }
                  } else null
                case INTEGER =>
                  val value = r.rs.getLong(n)
                  if (!r.rs.wasNull) value.toString
                  else null
                case TIMESTAMP =>
                  val ts = r.rs.getTimestamp(n)
                  if (ts ne null)
                    LfTimestamp.assertFromInstant(ts.toInstant).toString
                  else null
                case BOOLEAN =>
                  val value = r.rs.getBoolean(n)
                  if (!r.rs.wasNull) value else null
                case other => throw new IllegalArgumentException(s"Unsupported type: $other")
              }
            catch {
              case e: java.sql.SQLException =>
                throw new java.sql.SQLException(
                  s"reading '$n' of BQ type '${field.bqType}': ${e.getMessage}",
                  e,
                )
            }
          }
        }
        .toMap
        .asJava
    )
  }

  private def createStreamSchema(schema: ConvertibleSchema): TableSchema = {
    val tableSchemaBuilder = TableSchema.newBuilder()

    schema.foreach { field =>
      tableSchemaBuilder.addFields(field.bqsSchemaField)
    }

    tableSchemaBuilder.build()
  }

  private def convertToBigQueryStorageType(
      legacyType: bq.LegacySQLTypeName
  ): TFS.Type = legacyType match {
    case STRING => TFS.Type.STRING
    case INTEGER => TFS.Type.INT64
    case TIMESTAMP => TFS.Type.TIMESTAMP
    case BOOLEAN => TFS.Type.BOOL
    case JSON => TFS.Type.JSON
    case _ => throw new IllegalArgumentException(s"Unsupported type: $legacyType")
  }

  private def createJsonStreamWriter(
      targetTable: String,
      tableSchema: TableSchema,
  ): JsonStreamWriter = {
    val parent = s"projects/${bigquery.getOptions.getProjectId}/datasets/$datasetName"
    val fullTableId = s"$parent/tables/$targetTable"

    // Create the JSON writer
    JsonStreamWriter.newBuilder(fullTableId, tableSchema).build()
  }

  private def reportAppendSerializationErrors: PartialFunction[Throwable, Future[Nothing]] = {
    case e: bq.storage.v1.Exceptions.AppendSerializationError =>
      val maxErrors = 20
      Future fromTry util.Try(
        fail(
          e.getRowIndexToErrorMessage.asScala
            .to(collection.immutable.SortedMap)
            .take(maxErrors)
            .map { case (rowIndex, errorMessage) =>
              s"Row $rowIndex: $errorMessage"
            }
            .mkString("\n"),
          e,
        )
      )
  }

  /** Runs the total supply queries from the SQL file
    */
  private def runTotalSupplyQueries(): ExpectedMetrics = {
    // slurp BigQuery SQL file
    val sqlContent = java.nio.file.Files
      .readString(Paths get totalSupplySqlPath, java.nio.charset.StandardCharsets.UTF_8)

    // Replace prod dataset name with test dataset name
    val modifiedSql = sqlContent.replace("mainnet_da2_scan", datasetName)
    modifiedSql should not be sqlContent withClue "expected dataset name absent"
    // TODO (#1095) substitute migrationId SQL var binding

    // Execute the query
    val queryConfig = bq.QueryJobConfiguration
      .newBuilder(modifiedSql)
      .setUseLegacySql(false)
      .build()

    val jobId = bq.JobId.of(UUID.randomUUID().toString)
    val job = bigquery.create(JobInfo.newBuilder(queryConfig).setJobId(jobId).build())

    // Wait for query to complete
    // #waitFor takes additional RetryOptions, worth considering for flakes
    // over our usual local retry mechanisms
    job.waitFor()

    // results should be available now
    val result = job.getQueryResults()
    parseQueryResults(result)
  }

  private case class ExpectedMetrics(
      locked: BigDecimal,
      unlocked: BigDecimal,
      currentSupplyTotal: BigDecimal,
      unminted: BigDecimal,
      minted: BigDecimal,
      allowedMint: BigDecimal,
      burned: BigDecimal,
  )

  private def parseQueryResults(result: bq.TableResult) = {
    // We expect the final query to return a single row with all metrics
    val row = result.iterateAll().iterator().next()
    logger.debug(s"Query row: $row; schema ${result.getSchema}")

    def bd(column: String) = {
      val field = row get column
      if (field.isNull)
        fail(s"Column '$column' in total-supply results is null")
      else
        BigDecimal(field.getStringValue)
    }

    ExpectedMetrics(
      locked = bd("locked"),
      unlocked = bd("unlocked"),
      currentSupplyTotal = bd("current_supply_total"),
      unminted = bd("unminted"),
      minted = bd("minted"),
      allowedMint = bd("allowed_mint"),
      burned = bd("burned"),
    )
  }

  private def verifyResults(results: ExpectedMetrics): Unit = {
    // TODO (#1095) use expected ranges instead
    // Verify individual metrics
    val expectedMinted = BigDecimal(0) // mintedAmount
    results.minted shouldBe expectedMinted withClue "minted"
    results.locked shouldBe lockedAmount withClue "locked"
    results.unlocked shouldBe unlockedAmount withClue "unlocked"
    results.unminted shouldBe unmintedAmount withClue "unminted"
    results.burned shouldBe burnedAmount withClue "burned"

    // Verify derived metrics
    results.currentSupplyTotal shouldBe (lockedAmount + unlockedAmount) withClue "current_supply_total"
    results.allowedMint shouldBe (unmintedAmount + expectedMinted) withClue "allowed_mint"
  }
}
