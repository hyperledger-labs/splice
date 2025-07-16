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
import com.digitalasset.canton.util.MonadUtil
import com.digitalasset.canton.{FutureHelpers, HasActorSystem, HasExecutionContext}
import org.lfdecentralizedtrust.splice.automation.SqlIndexInitializationTrigger.IndexAction
import org.lfdecentralizedtrust.splice.store.db.{AcsJdbcTypes, AcsTables, SplicePostgresTest}
import org.slf4j.event.Level
import slick.dbio.DBIOAction
import slick.jdbc.{GetResult, PositionedResult}
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

    "use if not exists concurrently" in {
      SqlIndexInitializationTrigger.defaultIndexActions.foreach {
        case IndexAction.Create(_, statement) =>
          // "if not exists" is already enforced by DbStorageIdempotency retrying all statements in tests,
          // but a check here gives better feedback.
          // "concurrently" is not strictly required for the trigger to work, but a non-concurrent index creation
          // would be suspicious.
          statement.getDumpInfo.mainInfo should include("create index concurrently if not exists")
        case IndexAction.Drop(_) => succeed
      }
      succeed
    }

    "run with default settings" in {
      val trigger = SqlIndexInitializationTrigger(
        storage = storage,
        triggerContext = triggerContext,
      )
      for {
        _ <- runTriggerUntilAllTasksDone(trigger)
        indexNames <- listIndexNames()
        _ <- dumpIndexes()
      } yield {
        indexNames should contain allElementsOf Seq("updt_hist_crea_hi_mi_ci_import_updates")
      }
    }

    "create an index" in {
      val trigger = SqlIndexInitializationTrigger(
        storage = storage,
        triggerContext = triggerContext,
        indexActions = List(
          IndexAction.Create(
            "test_index",
            sqlu"create index if not exists test_index on update_history_creates (record_time)",
          )
        ),
      )

      for {
        _ <- runTriggerUntilAllTasksDone(trigger)
        indexNames <- listIndexNames()
      } yield {
        indexNames should contain allElementsOf List("test_index")
        trigger.isHealthy shouldBe true
      }
    }

    "drop index" in {
      val trigger = new SqlIndexInitializationTrigger(
        storage = storage,
        context = triggerContext,
        indexActions = List(
          IndexAction.Drop("test_index")
        ),
      )
      for {
        _ <- storage.underlying
          .update(
            sqlu"create index test_index on update_history_creates (record_time)",
            "create test index",
          )
          .failOnShutdown
        indexNamesBefore <- listIndexNames()
        _ = indexNamesBefore should contain("test_index")
        _ <- runTriggerUntilAllTasksDone(trigger)
        indexNamesAfter <- listIndexNames()
        _ = indexNamesAfter should not contain ("test_index")
      } yield succeed
    }

    "do not create an index if it already exists" in {
      val trigger = SqlIndexInitializationTrigger(
        storage = storage,
        triggerContext = triggerContext,
        indexActions = List(
          IndexAction.Create(
            "test_index",
            sqlu"create index if not exists test_index on update_history_creates (record_time)",
          )
        ),
      )

      for {
        _ <- storage.underlying
          .update(
            sqlu"create index test_index on update_history_creates (record_time)",
            "create test index",
          )
          .failOnShutdown
        tasks <- trigger.retrieveTasks()
        _ = tasks.loneElement shouldBe a[SqlIndexInitializationTrigger.Task.ConfirmActionCompleted]
        _ <- runTriggerUntilAllTasksDone(trigger)
        indexNames <- listIndexNames()
      } yield {
        indexNames should contain allElementsOf List("test_index")
        trigger.isHealthy shouldBe true
      }
    }

    "do not drop an index if it does not exists" in {
      val trigger = SqlIndexInitializationTrigger(
        storage = storage,
        triggerContext = triggerContext,
        indexActions = List(
          IndexAction.Drop("test_index")
        ),
      )

      for {
        tasks <- trigger.retrieveTasks()
        _ = tasks.loneElement shouldBe a[SqlIndexInitializationTrigger.Task.ConfirmActionCompleted]
        _ <- runTriggerUntilAllTasksDone(trigger)
        indexNames <- listIndexNames()
      } yield {
        indexNames should not contain "test_index"
        trigger.isHealthy shouldBe true
      }
    }

    "delete invalid index" in {
      val trigger = new SqlIndexInitializationTrigger(
        storage = storage,
        context = triggerContext,
        indexActions = List(
          IndexAction.Create(
            "test_index",
            sqlu"create index concurrently if not exists test_index on active_parties (closed_round)",
          )
        ),
      )
      for {
        _ <- Future.unit
        _ <- storage.underlying
          .update(
            DBIOAction
              .seq(
                sqlu"""
                  insert into active_parties (store_id, party, closed_round)
                  values (1, 'test_party', 1)
                """,
                sqlu"""
                  insert into active_parties (store_id, party, closed_round)
                  values (2, 'test_party2', 1)
                """,
              ),
            "insert test data",
          )
          .failOnShutdown
        _ <- storage.underlying
          .update(
            sqlu"""
              create or replace function slow_function(text) returns text as $$$$
              begin
                  perform pg_sleep(5); -- simulate a long-running operation
                  return $$1;
              end;
              $$$$ language plpgsql immutable;
              """,
            "insert test data",
          )
          .failOnShutdown
        _ <- storage.underlying
          .update(
            DBIOAction
              .seq(
                sqlu"set statement_timeout to '1s'",
                // This statement will be aborted, because slow_function() takes 5 seconds to execute per row,
                // and the statement timeout is set to 1 second above.
                // 'create index concurrently' internally consists of 3 transactions: one to register the index as invalid,
                // and two table scans to build the index. Aborting the statement will leave the index in an invalid state.
                sqlu"create index concurrently if not exists test_index on active_parties (slow_function(party))",
              )
              .asTry,
            "insert test data",
          )
          .failOnShutdown

        indexNamesBefore <- listIndexNames()
        _ = indexNamesBefore should contain("test_index")

        tasks <- loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
          within = { trigger.retrieveTasks() },
          assertion = { entries =>
            forExactly(1, entries) {
              _.message should include(
                "Index test_index should be created and is invalid, dropping it"
              )
            }
          },
        )
        _ = tasks.loneElement match {
          case SqlIndexInitializationTrigger.Task.ExecuteAction(IndexAction.Drop("test_index")) =>
            succeed
          case other =>
            fail(s"Expected Drop for test_index, got $other")
        }
        _ <- loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
          within = { trigger.runOnce() },
          assertion = { entries =>
            forExactly(1, entries) {
              _.message should include(
                "Index test_index should be created and is invalid, dropping it"
              )
            }
          },
        )
        indexNamesAfterDrop <- listIndexNames()
        _ = indexNamesAfterDrop should not contain ("test_index")

        _ <- runTriggerUntilAllTasksDone(trigger)
        indexNamesAfter <- listIndexNames()
      } yield {
        indexNamesAfter should contain("test_index")
      }
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

  // One row in pg_indexes
  private case class IndexesEntry(
      schemaName: String,
      tableName: String,
      indexName: String,
      indexDefinition: String,
  )

  private implicit val GetResultIndexesEntry: GetResult[IndexesEntry] = { (pp: PositionedResult) =>
    IndexesEntry(
      pp.<<,
      pp.<<,
      pp.<<,
      pp.<<,
    )
  }

  // Dumps information about all indexes in the database to the log.
  // Used during development to verify that the indexes are created correctly.
  private def dumpIndexes(): Future[Unit] = {
    storage.underlying
      .query(
        sql"""
      select
        i.schemaname, i.tablename, i.indexname, i.indexdef
      from
        pg_indexes i
    """.as[IndexesEntry],
        "dumpIndexes",
      )
      .map { indexes =>
        logger.info(s"Indexes: ${indexes.mkString("\n  ", "\n  ", "\n")}")
      }
      .failOnShutdown
  }

  private def dropIndexes(indexNames: Seq[String]): FutureUnlessShutdown[Unit] = {
    MonadUtil
      .sequentialTraverse(indexNames) { indexName =>
        storage
          .update(
            sqlu"drop index if exists #${indexName}",
            s"drop index $indexName",
          )
      }
      .map(_ => ())
  }

  private def runTriggerUntilAllTasksDone(trigger: SqlIndexInitializationTrigger): Future[Unit] = {
    trigger.run(paused = false)
    trigger.remainingActionsEmpty.future
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
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] = for {
    _ <- resetAllAppTables(storage)
    _ <- dropIndexes(
      SqlIndexInitializationTrigger.defaultIndexActions.map(_.indexName) ++ Seq("test_index")
    )
  } yield ()
}
