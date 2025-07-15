package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import ConfigTransforms.{ConfigurableApp, updateAutomationConfig}
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.AdvanceOpenMiningRoundTrigger
import org.lfdecentralizedtrust.splice.util.*
import com.digitalasset.canton.BaseTest.getResourcePath
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.lifecycle.{HasCloseContext, FlagCloseable}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.daml.lf.data.Time.Timestamp as LfTimestamp
import com.google.cloud.bigquery as bq
import bq.{Field, InsertAllRequest, JobInfo, Schema, TableId}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.*

import java.nio.file.Paths
import java.util.UUID
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import slick.jdbc.GetResult

class ScanTotalSupplyBigQueryIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with SplitwellTestUtil
    with TimeTestUtil
    with HasActorSystem
    with HasExecutionContext
    with FlagCloseable
    with HasCloseContext
    with UpdateHistoryTestUtil {
  private val totalSupplySqlPath = getResourcePath("/total-supply-bigquery.sql")

  // TODO (#1095) copied verbatim from UpdateHistoryIntegrationTest, maybe diverge or factor
  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withAdditionalSetup(implicit env => {
        aliceValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPath)
        bobValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPath)
      })
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
          _.withPausedTrigger[AdvanceOpenMiningRoundTrigger]
        )(config)
      )
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppFoundDsoConfigs_(
          _.copy(initialTickDuration = NonNegativeFiniteDuration.ofMillis(500))
        )(config)
      )
      .withTrafficTopupsDisabled

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
  private val burnedAmount = BigDecimal("50000")
  private val unlockedAmount = mintedAmount - lockedAmount - burnedAmount
  private val unmintedAmount = BigDecimal("500000")

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

    withClue("running total supply queries in BigQuery") {
      // Run total supply queries from the SQL file
      val results = runTotalSupplyQueries()

      // Verify that results match our expected values
      verifyResults(results)
    }
  }

  import bq.LegacySQLTypeName.{INTEGER, STRING, TIMESTAMP, JSON, BOOLEAN}

  private def fld(name: String, typ: bq.LegacySQLTypeName) =
    Field.newBuilder(name, typ).setMode(Field.Mode.NULLABLE).build()

  // Datastream variant of update_history_creates
  // contains Amulet, LockedAmulet, UnclaimedReward, &c
  private val createsSchema = Schema.of(
    fld("history_id", INTEGER),
    fld("row_id", INTEGER),
    fld("ingested_at", TIMESTAMP),
    fld("event_id", STRING),
    fld("update_row_id", INTEGER),
    fld("contract_id", STRING),
    fld("created_at", INTEGER),
    fld("template_id_package_id", STRING),
    fld("template_id_module_name", STRING),
    fld("template_id_entity_name", STRING),
    fld("package_name", STRING),
    fld("create_arguments", JSON),
    fld("signatories", JSON),
    fld("observers", JSON),
    fld("contract_key", JSON),
    fld("record_time", INTEGER),
    fld("update_id", STRING),
    fld("domain_id", STRING),
    fld("migration_id", INTEGER),
  )

  // Datastream variant of update_history_exercises
  // contains AmuletRules_Transfer &c
  private val exercisesSchema = Schema.of(
    fld("history_id", INTEGER),
    fld("row_id", INTEGER),
    fld("ingested_at", TIMESTAMP),
    fld("event_id", STRING),
    fld("update_row_id", INTEGER),
    fld("child_event_ids", JSON),
    fld("choice", STRING),
    fld("template_id_package_id", STRING),
    fld("template_id_module_name", STRING),
    fld("template_id_entity_name", STRING),
    fld("contract_id", STRING),
    fld("consuming", BOOLEAN),
    fld("argument", JSON),
    fld("result", JSON),
    fld("package_name", STRING),
    fld("interface_id_package_id", STRING),
    fld("interface_id_module_name", STRING),
    fld("interface_id_entity_name", STRING),
    fld("acting_parties", JSON),
    fld("record_time", INTEGER),
    fld("update_id", STRING),
    fld("domain_id", STRING),
    fld("migration_id", INTEGER),
  )

  // create empty tables in BigQuery that match the schema inferred by Datastream,
  // less the datastream_metadata column (which we don't use)
  private def createEmptyTables(): Unit = {
    // row_id is primary key but this is not currently enforced even in actual
    // deployment
    createTable(createsBqTableName, createsSchema)
    createTable(exercisesBqTableName, exercisesSchema)
  }

  private def createTable(tableName: String, schema: Schema): Unit = {
    val tableId = TableId.of(datasetName, tableName)
    val tableDefinition = bq.StandardTableDefinition.of(schema)
    val tableInfo = bq.TableInfo.of(tableId, tableDefinition)
    bigquery.create(tableInfo)
  }

  /** Creates test data with known amounts for all metrics
    */
  private def createTestData(aliceParty: PartyId, bobParty: PartyId)(implicit
      env: FixtureParam
  ): Unit = {
    aliceParty shouldBe aliceParty // TODO (#1095) use it
    // mint for Alice
    aliceWalletClient.tap(walletAmuletToUsd(mintedAmount))

    // TODO (#1095) Lock a portion of Amulet (lockedAmount)

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

    copyTableToBigQuery(
      "update_history_creates",
      createsBqTableName,
      createsSchema,
      sourceDb,
    )
    copyTableToBigQuery(
      "update_history_exercises",
      exercisesBqTableName,
      exercisesSchema,
      sourceDb,
    )
  }

  private def copyTableToBigQuery(
      sourceTable: String,
      targetTable: String,
      targetSchema: Schema,
      sourceDb: DbStorage,
  ): Unit = {
    // runtime interpretation of bq Schema; convert Slick+PG to BigQuery
    implicit val r: GetResult[InsertAllRequest.RowToInsert] = GetResult { r =>
      InsertAllRequest.RowToInsert of targetSchema.getFields.asScala.view
        .map { field =>
          val n = field.getName
          n -> {
            try
              field.getType match {
                // assuming JSON is stored as String
                case STRING | JSON => r.rs getString n
                case INTEGER => r.rs getLong n
                case TIMESTAMP =>
                  LfTimestamp.assertFromInstant(r.rs.getTimestamp(n).toInstant).toString
                case BOOLEAN => r.rs getBoolean n
                case other => throw new IllegalArgumentException(s"Unsupported type: $other")
              }
            catch {
              case e: java.sql.SQLException =>
                throw new java.sql.SQLException(
                  s"reading '$n' of BQ type '${field.getType}' and PG column ${r.rs.getObject(n).getClass.getName}: ${e.getMessage}",
                  e,
                )
            }
          }
        }
        .toMap
        .asJava
    }

    // fetch all rows and prepare the BigQuery InsertAllRequest
    val fieldNames = targetSchema.getFields.asScala.view.map(_.getName).mkString(", ")
    val insertAll = sourceDb
      .query(
        sql"SELECT #$fieldNames FROM #$sourceTable".as[InsertAllRequest.RowToInsert],
        s"Export $sourceTable to BigQuery",
      )
      .map { rows =>
        InsertAllRequest.of(TableId.of(datasetName, targetTable), rows.asJava)
      }
      .futureValueUS

    // load into BigQuery
    val insertResponse = bigquery.insertAll(insertAll)
    if (insertResponse.hasErrors) {
      val allErrors = insertResponse.getInsertErrors.asScala.view
        .map { case (row, errors) =>
          s"Row $row: ${errors.asScala.view.map(_.getMessage).mkString(", ")}"
        }
        .mkString(";\n")
      fail(s"Failed to insert rows into BigQuery: $allErrors")
    }
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

    ExpectedMetrics(
      locked = BigDecimal(row.get("locked").getStringValue),
      unlocked = BigDecimal(row.get("unlocked").getStringValue),
      currentSupplyTotal = BigDecimal(row.get("current_supply_total").getStringValue),
      unminted = BigDecimal(row.get("unminted").getStringValue),
      minted = BigDecimal(row.get("minted").getStringValue),
      allowedMint = BigDecimal(row.get("allowed_mint").getStringValue),
      burned = BigDecimal(row.get("burned").getStringValue),
    )
  }

  private def verifyResults(results: ExpectedMetrics): Unit = {
    // TODO (#1095) use expected ranges instead
    // Verify individual metrics
    results.locked shouldBe lockedAmount
    results.unlocked shouldBe unlockedAmount
    results.unminted shouldBe unmintedAmount
    results.minted shouldBe mintedAmount
    results.burned shouldBe burnedAmount

    // Verify derived metrics
    results.currentSupplyTotal should be(lockedAmount + unlockedAmount)
    results.allowedMint should be(unmintedAmount + mintedAmount)
  }
}
