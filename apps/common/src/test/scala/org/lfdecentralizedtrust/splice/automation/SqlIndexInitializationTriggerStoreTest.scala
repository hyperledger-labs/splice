package org.lfdecentralizedtrust.splice.automation

import com.daml.metrics.api.noop.NoOpMetricsFactory
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.store.{StoreErrors, StoreTest}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.time.SimClock
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{FutureHelpers, HasActorSystem, HasExecutionContext}
import org.lfdecentralizedtrust.splice.automation.SqlIndexInitializationTrigger.IndexAction
import org.lfdecentralizedtrust.splice.store.db.{AcsJdbcTypes, AcsTables, SplicePostgresTest}
import org.slf4j.event.Level
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import scala.concurrent.Future

class SqlIndexInitializationTriggerStoreTest
    extends StoreTest
    with HasExecutionContext
    with StoreErrors
    with HasActorSystem
    with SplicePostgresTest
    with AcsJdbcTypes
    with AcsTables
    with FutureHelpers {

  "SqlIndexInitializationTrigger" should {

    "create custom indexes with default settings" in {
      val trigger = new SqlIndexInitializationTrigger(
        storage = storage,
        config = storage.dbConfig,
        context = triggerContext,
      )
      trigger.run(paused = false)
      for {
        _ <- trigger.finished.failOnShutdown
        indexNames <- listIndexNames()
      } yield {
        indexNames should contain allElementsOf SqlIndexInitializationTrigger.customIndexes.keySet
        trigger.isHealthy shouldBe true
      }
    }

    "warn about unknown indexes" in {
      val trigger = new SqlIndexInitializationTrigger(
        storage = storage,
        config = storage.dbConfig,
        context = triggerContext,
        expectedIndexes = Map.empty,
      )
      loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
        within = {
          trigger.run(paused = false)
          trigger.finished.failOnShutdown.map(_ => succeed)
        },
        assertion = entries => {
          entries should have size 1
          entries.loneElement.message should include("Found unexpected SQL indexes")
          SqlIndexInitializationTrigger.flywayIndexes.keySet.foreach(flywayIndexName =>
            entries.loneElement.message should include(flywayIndexName)
          )
          trigger.isHealthy shouldBe true
        },
      )
    }

    "drop index" in {
      val trigger = new SqlIndexInitializationTrigger(
        storage = storage,
        config = storage.dbConfig,
        context = triggerContext,
        expectedIndexes = SqlIndexInitializationTrigger.flywayIndexes ++ Map(
          "test_index" -> IndexAction.Drop
        ),
      )
      for {
        // Note: this is changing the schema. Schema changes are not automatically rolled back
        // between tests (see DbTest.cleanDb). If this test fails, it may leave the index in place.
        _ <- storage.underlying
          .update(
            sqlu"create index test_index on update_history_creates (record_time)",
            "create test index",
          )
          .failOnShutdown
        indexNamesBefore <- listIndexNames()
        _ = indexNamesBefore should contain("test_index")
        _ = trigger.run(paused = false)
        _ <- trigger.finished.failOnShutdown
        indexNamesAfter <- listIndexNames()
        _ = indexNamesAfter should not contain ("test_index")
      } yield succeed
    }

    "become unhealthy if index creation fails" in {
      val trigger = new SqlIndexInitializationTrigger(
        storage = storage,
        config = storage.dbConfig,
        context = triggerContext,
        expectedIndexes = SqlIndexInitializationTrigger.flywayIndexes ++ Map(
          "invalid_index_definition" -> IndexAction.Create(
            sqlu"create index invalid_index on non_existent_table (column_name)"
          )
        ),
      )
      loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.ERROR))(
        within = {
          trigger.run(paused = false)
          trigger.finished.failOnShutdown.map(_ => succeed)
        },
        assertion = entries => {
          entries should have size 1
          entries.loneElement.message should include("Initialization task failed")
          trigger.isHealthy shouldBe false
        },
      )
    }
  }

  private def listIndexNames(): Future[Seq[String]] = {
    storage.underlying
      .query(
        sql"select indexname from pg_indexes where schemaname = 'public'".as[String],
        "listIndexes",
      )
      .failOnShutdown
  }
  private lazy val clock = new SimClock(loggerFactory = loggerFactory)
  private lazy val triggerContext: TriggerContext = TriggerContext(
    AutomationConfig(),
    clock,
    clock,
    TriggerEnabledSynchronization.Noop,
    RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
    loggerFactory,
    NoOpMetricsFactory,
  )
  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] = resetAllAppTables(storage)
}
