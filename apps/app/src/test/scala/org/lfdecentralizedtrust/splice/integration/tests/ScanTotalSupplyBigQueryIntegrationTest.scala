package org.lfdecentralizedtrust.splice.integration.tests

/** Note: to execute this locally, you might need to first `export GCLOUD_PROJECT=$CLOUDSDK_CORE_PROJECT` * */

import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.util.*
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import com.digitalasset.canton.lifecycle.{FlagCloseable, HasCloseContext}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.daml.lf.data.Time.Timestamp as LfTimestamp
import com.google.cloud.bigquery as bq
import bq.{Field, JobInfo, Schema, TableId}
import bq.storage.v1.{JsonStreamWriter, TableSchema}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.*
import slick.jdbc.GetResult

import java.io.File
import java.nio.file.{Path, Paths}
import java.util.UUID
import scala.concurrent.duration.*
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*
import scala.jdk.DurationConverters.*
import scala.sys.process.Process
import java.time.temporal.ChronoUnit

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

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      // prevent ReceiveFaucetCouponTrigger from seeing stale caches
      .withScanDisabledMiningRoundsCache()
      .withAmuletPrice(walletAmuletPrice)

  def coinPrice = BigDecimal(0.00001)
  override def walletAmuletPrice = SpliceUtil.damlDecimal(coinPrice)

  override protected def runTokenStandardCliSanityCheck = false

  // BigQuery client instance and test dataset
  private lazy val bigquery: bq.BigQuery = bq.BigQueryOptions.getDefaultInstance.getService
  private val uuid = UUID.randomUUID().toString.replace("-", "_")
  private val datasetName =
    s"scantotalsupply_test_dataset_$uuid"
  private val (createsBqTableName, exercisesBqTableName) = {
    val prefix = "scan_sv_1_"
    (
      s"${prefix}update_history_creates",
      s"${prefix}update_history_exercises",
    )
  }
  private val functionsDatasetName = s"functions_$uuid"
  private val dashboardsDatasetName = s"dashboards_$uuid"

  // Test data parameters
  private val mintedAppRewardsAmount = BigDecimal(0)
  private val mintedValidatorRewardsAmount = BigDecimal("152207.0015220704")
  private val mintedSvRewardsAmount = BigDecimal("2435312.024352")
  private val mintedUnclaimedsAmount = BigDecimal(0)
  private val mintedAmount =
    mintedAppRewardsAmount + mintedValidatorRewardsAmount + mintedSvRewardsAmount + mintedUnclaimedsAmount
  private val aliceValidatorMintedAmount = BigDecimal("26046.0426105176")
  private val lockedAmount = BigDecimal("5000")
  private val burnedAmount = BigDecimal("60032.83108")
  private val unlockedAmount = mintedAmount - lockedAmount - burnedAmount
  private val unmintedAmount = BigDecimal("570776.255709163")
  private val amuletHolders = 5
  private val validators = 4 // one SV + 3 validators
  // The test currently produces 80 transactions, which is 0.000926 tps over 24 hours,
  // so we assert for a range of 70-85 transactions, or 0.0008-0.00099 tps.
  private val avgTps = (0.0008, 0.00099)
  // The peak is 17 transactions in a (simulated) minute, or 0.28333 tps over a minute,
  // so we assert 15-20 transactions, or 0.25-0.34 tps
  private val peakTps = (0.25, 0.34)

  override def beforeAll() = {
    super.beforeAll()
    logger.info(s"Creating BigQuery dataset: $datasetName as user: ${inferBQUser()}")

    // Create a temporary BigQuery dataset for testing
    // 1hr is the minimum per https://github.com/googleapis/java-bigquery/blob/v2.53.0/google-cloud-bigquery/src/main/java/com/google/cloud/bigquery/DatasetInfo.java#L97-L108
    val datasetInfo =
      bq.DatasetInfo.newBuilder(datasetName).setDefaultTableLifetime(1.hour.toMillis).build()
    bigquery.create(datasetInfo)

    createEmptyTables()

    val functionsDatasetInfo =
      bq.DatasetInfo
        .newBuilder(functionsDatasetName)
        .setDefaultTableLifetime(1.hour.toMillis)
        .build()
    bigquery.create(functionsDatasetInfo)

    // Note that the dashboard tables are never actually populated in this test,
    // but we do test creating them from the codegen'ed schemas, and creating the
    // functions and procedures for populating them, so we get some sanity check
    // on the queries for syntax and type errors.
    val dashboardsDatasetInfo =
      bq.DatasetInfo
        .newBuilder(dashboardsDatasetName)
        .setDefaultTableLifetime(1.hour.toMillis)
        .build()
    bigquery.create(dashboardsDatasetInfo)
  }

  private[this] def inferBQUser(): String = {
    import com.google.auth.oauth2 as o
    val credentials = bigquery.getOptions.getCredentials
    credentials match {
      case sa: o.ServiceAccountCredentials => sa.getClientEmail
      case sa: o.ServiceAccountJwtAccessCredentials => sa.getClientEmail
      case _ => "unknown"
    }
  }

  override def afterAll() = {
    logger.info(s"Cleaning up BigQuery dataset: $datasetName")

    // Delete the temporary BigQuery datasets after tests
    bigquery.delete(datasetName, bq.BigQuery.DatasetDeleteOption.deleteContents())
    bigquery.delete(functionsDatasetName, bq.BigQuery.DatasetDeleteOption.deleteContents())
    bigquery.delete(dashboardsDatasetName, bq.BigQuery.DatasetDeleteOption.deleteContents())
    super.afterAll()
  }

  "test bigquery queries" in { implicit env =>
    withClue("create test data on Splice ledger") {
      val (_, bobParty) = onboardAliceAndBob()
      waitForWalletUser(aliceValidatorWalletClient)

      // Create test data with more-or-less known amounts
      createTestData(bobParty)
    }

    withClue("exporting PostgreSQL tables to BigQuery") {
      exportPostgresToBigQuery()
    }

    withClue("Creating BigQuery functions") {
      createBigQueryFunctions()
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
  private def createTestData(bobParty: PartyId)(implicit
      env: FixtureParam
  ): Unit = {
    actAndCheck(
      "step forward many rounds", {
        advanceTimeToRoundOpen
        (1 to 5).foreach { _ =>
          advanceRoundsByOneTick
        }
      },
    )(
      "alice validator receives rewards",
      _ => {
        aliceValidatorWalletClient.balance().unlockedQty shouldBe aliceValidatorMintedAmount
      },
    )

    val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()
    val (lockingParty, lockingClient) = (aliceValidatorParty, aliceValidatorWalletClient)
    actAndCheck(
      "Lock amulet",
      lockAmulets(
        aliceValidatorBackend,
        lockingParty,
        aliceValidatorParty,
        lockingClient.list().amulets,
        lockedAmount,
        sv1ScanBackend,
        10.days.toJava,
        getLedgerTime,
      ),
    )(
      "Wait for locked amulet to appear",
      _ => lockingClient.list().lockedAmulets.loneElement,
    )

    // burn fees
    val transferAmount = BigDecimal("1000")
    p2pTransfer(aliceValidatorWalletClient, bobWalletClient, bobParty, transferAmount)
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

  /** Creates all auxiliary functions in BigQuery. First codegen's from the Pulumi definitions
    * the query that creates them, then runs that query in BQ.
    */
  private def createBigQueryFunctions() = {
    val sqlDir: Path = Paths.get("apps/app/src/test/resources/dumps/sql")
    if (!sqlDir.toFile.exists())
      sqlDir.toFile.mkdirs()
    val sqlFile = sqlDir.resolve("functions.sql")

    val ret = Process(
      s"npm run sql-codegen ${bigquery.getOptions.getProjectId} ${functionsDatasetName} ${datasetName} ${dashboardsDatasetName} ${sqlFile.toAbsolutePath}",
      new File("cluster/pulumi/canton-network"),
    ).!
    if (ret != 0) {
      fail("Failed to codegen the sql query for creating functions in BigQuery")
    }

    val sqlContent =
      java.nio.file.Files.readString(sqlFile, java.nio.charset.StandardCharsets.UTF_8)

    logger.info(s"Creating BQ functions using the following SQL statement: $sqlContent")

    // Execute the query
    val queryConfig = bq.QueryJobConfiguration
      .newBuilder(sqlContent)
      .setUseLegacySql(false)
      .build()

    val jobId = bq.JobId.of(UUID.randomUUID().toString)
    val job = bigquery.create(JobInfo.newBuilder(queryConfig).setJobId(jobId).build())

    job.waitFor()
  }

  /** Runs the total supply queries from the SQL file
    */
  private def runTotalSupplyQueries()(implicit env: FixtureParam): ExpectedMetrics = {
    val project = bigquery.getOptions.getProjectId
    // The TPS query assumes staleness of up to 4 hours, so we query for stats 5 hours after the current ledger time.
    val timestamp = getLedgerTime.toInstant.plus(5, ChronoUnit.HOURS).toString
    val sql =
      s"SELECT * FROM `$project.$functionsDatasetName.all_stats`('$timestamp', 0);"

    logger.info(s"Querying all stats as of $timestamp")

    // Execute the query
    val queryConfig = bq.QueryJobConfiguration
      .newBuilder(sql)
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
      mintedAppRewards: BigDecimal,
      mintedValidatorRewards: BigDecimal,
      mintedSvRewards: BigDecimal,
      mintedUnclaimed: BigDecimal,
      burned: BigDecimal,
      numAmuletHolders: Long,
      numActiveValidators: Long,
      avgTps: Double,
      peakTps: Double,
      minCoinPrice: BigDecimal,
      maxCoinPrice: BigDecimal,
      avgCoinPrice: BigDecimal,
  )

  private def parseQueryResults(result: bq.TableResult) = {
    // We expect the final query to return a single row with all metrics
    val row = result.iterateAll().iterator().next()
    logger.debug(s"Query row: $row; schema ${result.getSchema}")

    def required(column: String) = {
      val field = row get column
      if (field.isNull)
        fail(s"Column '$column' in all-stats results is null")
      field
    }

    def bd(column: String) = {
      BigDecimal(required(column).getStringValue)
    }

    def int(column: String) = {
      required(column).getLongValue
    }

    def float(column: String) = {
      required(column).getDoubleValue
    }

    ExpectedMetrics(
      locked = bd("locked"),
      unlocked = bd("unlocked"),
      currentSupplyTotal = bd("current_supply_total"),
      unminted = bd("unminted"),
      mintedAppRewards = bd("daily_mint_app_rewards"),
      mintedValidatorRewards = bd("daily_mint_validator_rewards"),
      mintedSvRewards = bd("daily_mint_sv_rewards"),
      mintedUnclaimed = bd("daily_mint_unclaimed_activity_records"),
      burned = bd("daily_burn"),
      numAmuletHolders = int("num_amulet_holders"),
      numActiveValidators = int("num_active_validators"),
      avgTps = float("average_tps"),
      peakTps = float("peak_tps"),
      minCoinPrice = bd("daily_min_coin_price"),
      maxCoinPrice = bd("daily_max_coin_price"),
      avgCoinPrice = bd("daily_avg_coin_price"),
    )
  }

  private def verifyResults(results: ExpectedMetrics): Unit = {
    // Verify individual metrics
    forEvery(
      Seq(
        // base metrics
        ("minted_appRewards", results.mintedAppRewards, mintedAppRewardsAmount),
        ("minted_validatorRewards", results.mintedValidatorRewards, mintedValidatorRewardsAmount),
        ("minted_svRewards", results.mintedSvRewards, mintedSvRewardsAmount),
        ("minted_unclaimed", results.mintedUnclaimed, mintedUnclaimedsAmount),
        ("locked", results.locked, lockedAmount),
        ("unlocked", results.unlocked, unlockedAmount),
        ("unminted", results.unminted, unmintedAmount),
        ("burned", results.burned, burnedAmount),
        ("current_supply_total", results.currentSupplyTotal, lockedAmount + unlockedAmount),
        ("num_amulet_holders", results.numAmuletHolders, amuletHolders),
        ("num_active_validators", results.numActiveValidators, validators),
        ("daily_min_coin_price", results.minCoinPrice, coinPrice),
        ("daily_max_coin_price", results.maxCoinPrice, coinPrice),
        ("daily_avg_coin_price", results.avgCoinPrice, coinPrice),
      )
    ) { case (clue, actual, expected) =>
      actual shouldBe expected withClue clue
    }

    forEvery(
      Seq(
        ("average_tps", results.avgTps, avgTps),
        ("peak_tps", results.peakTps, peakTps),
      )
    ) { case (clue, actual, expected) =>
      actual shouldBe >=(expected._1) withClue clue
      actual shouldBe <=(expected._2) withClue clue
    }

    // other derived metrics
    (mintedAmount - burnedAmount) shouldBe (
      lockedAmount + unlockedAmount
    ) withClue "separate paths to total supply match"
  }
}
