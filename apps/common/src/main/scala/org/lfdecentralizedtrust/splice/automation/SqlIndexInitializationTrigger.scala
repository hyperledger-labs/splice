// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import com.digitalasset.canton.config.DbConfig
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.lifecycle.{FutureUnlessShutdown, *}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.SqlIndexInitializationTrigger.IndexAction
import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
import slick.dbio.{DBIOAction, Effect, NoStream}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future, Promise}

/** A trigger that asynchronously creates or drops SQL indexes at application startup.
  *
  * Indexes are processed in the order they are provided.
  * If a created index becomes invalid, it is dropped and recreated.
  */
class SqlIndexInitializationTrigger(
    storage: DbStorage,
    protected val context: TriggerContext,
    indexActions: List[IndexAction],
)(implicit ec: ExecutionContextExecutor, override val tracer: Tracer, mat: Materializer)
    extends PollingParallelTaskExecutionTrigger[SqlIndexInitializationTrigger.Task]
    with HasCloseContext {
  import SqlIndexInitializationTrigger.*

  assert(
    indexActions.distinct == indexActions,
    "Index actions must be unique.",
  )
  private val remainingActions: AtomicReference[List[IndexAction]] = new AtomicReference(
    indexActions
  )
  private[automation] val remainingActionsEmpty: Promise[Unit] = Promise()

  private def nextTask(actions: List[IndexAction])(implicit
      tc: TraceContext
  ): FutureUnlessShutdown[Seq[Task]] =
    actions.headOption match {
      case None =>
        FutureUnlessShutdown.pure(Seq.empty)
      case Some(head) =>
        for {
          headStatus <- storage.query(
            getIndexStatusAction(head.indexName),
            "getIndexStatusAction",
          )
        } yield (head, headStatus) match {
          case (_, IndexStatus.InProgress(pid)) =>
            logger.info(
              s"Index ${head.indexName} is being built by backend process $pid, skipping."
            )
            // Do not mess with the index if it is being built.
            // Return no task, causing the trigger to try again after the next polling interval.
            Seq.empty

          case (IndexAction.Create(indexName, _), IndexStatus.Valid) =>
            logger.info(s"Index $indexName should be created and is valid, skipping.")
            Seq(Task.ConfirmActionCompleted(head))

          case (IndexAction.Create(indexName, _), IndexStatus.DoesNotExist) =>
            logger.info(s"Index $indexName should be created and does not exist, creating it.")
            Seq(Task.ExecuteAction(head))

          case (IndexAction.Create(indexName, _), IndexStatus.Invalid) =>
            logger.warn(s"Index $indexName should be created and is invalid, dropping it.")
            Seq(Task.ExecuteAction(IndexAction.Drop(indexName)))

          case (IndexAction.Drop(indexName), IndexStatus.DoesNotExist) =>
            logger.info(s"Index $indexName should be dropped and does not exist, skipping.")
            Seq(Task.ConfirmActionCompleted(head))

          case (IndexAction.Drop(indexName), IndexStatus.Valid) =>
            logger.info(s"Index $indexName should be dropped and is valid, dropping.")
            Seq(Task.ExecuteAction(head))

          case (IndexAction.Drop(indexName), IndexStatus.Invalid) =>
            logger.warn(s"Index $indexName should be dropped and is invalid, dropping.")
            Seq(Task.ExecuteAction(head))
        }
    }

  override def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[SqlIndexInitializationTrigger.Task]] = {
    storage.dbConfig match {
      case postgresConfig: DbConfig.Postgres =>
        nextTask(remainingActions.get())
          .failOnShutdownToAbortException("Retrieve SqlIndexInitializationTrigger tasks")
      case _ =>
        // We only really support Postgres in our apps.
        throw new RuntimeException(s"Unsupported dbConfig ${storage.dbConfig}")
    }
  }

  override protected def isStaleTask(task: SqlIndexInitializationTrigger.Task)(implicit
      tc: TraceContext
  ): Future[Boolean] = {
    // We are using "if not exists" for index creation and dropping.
    // Statements should be safe to retry, and failing tasks should be reported as errors.
    Future.successful(false)
  }

  override protected def completeTask(task: SqlIndexInitializationTrigger.Task)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = task match {
    case Task.ExecuteAction(IndexAction.Drop(indexName)) =>
      logger.info(s"Dropping index $indexName")
      storage
        .update(
          sqlu"drop index concurrently if exists #$indexName",
          "drop_" + indexName,
        )
        .unwrap
        .map { _ =>
          logger.info(s"Finished dropping index $indexName")
          TaskSuccess(s"Dropped index $indexName")
        }

    case Task.ExecuteAction(IndexAction.Create(indexName, createAction)) =>
      logger.info(s"Creating index $indexName")
      storage
        .update(createAction, "create_" + indexName)
        .unwrap
        .map { _ =>
          logger.info(s"Finished creating index $indexName")
          TaskSuccess(s"Created index $indexName")
        }

    case Task.ConfirmActionCompleted(action) =>
      remainingActions.updateAndGet(_.filterNot(_ == action))
      if (remainingActions.get().isEmpty) {
        remainingActionsEmpty.trySuccess(()).discard
      }
      logger.info(s"Confirmed action completed for index ${action.indexName}")
      Future.successful(TaskSuccess(s"Confirmed action completed for index ${action.indexName}"))
  }
}

object SqlIndexInitializationTrigger {

  def apply(
      storage: DbStorage,
      triggerContext: TriggerContext,
      indexActions: List[IndexAction] = defaultIndexActions,
  )(implicit
      ec: ExecutionContextExecutor,
      tracer: Tracer,
      mat: Materializer,
  ): SqlIndexInitializationTrigger = {
    new SqlIndexInitializationTrigger(
      storage,
      triggerContext,
      indexActions,
    )
  }

  sealed trait IndexStatus
  object IndexStatus {

    /** Index is valid and ready for use */
    final case object Valid extends IndexStatus

    /** Index is permanently invalid */
    final case object Invalid extends IndexStatus

    /** Index does not exist */
    final case object DoesNotExist extends IndexStatus

    /** Index is being built by the given background process */
    final case class InProgress(pid: Long) extends IndexStatus
  }

  def getIndexStatusAction(
      indexName: String
  )(implicit
      ec: ExecutionContext
  ): DBIOAction[IndexStatus, NoStream, Effect.Read] = {
    sql"""
      select
        i.indisvalid, pspci.pid
      from
        pg_class c
      join
        pg_namespace n on n.oid = c.relnamespace
      join
        pg_index i on i.indexrelid = c.oid
      left join
        pg_stat_progress_create_index pspci on pspci.index_relid = c.oid
      where
        c.relname = $indexName
        and n.nspname = current_schema
        and c.relkind = 'i'
    """.as[(Boolean, Option[Long])].headOption.map {
      // The status is "in progress" if a backend process exists that is actively building the index
      case Some((_, Some(pid))) => IndexStatus.InProgress(pid)
      case Some((true, None)) => IndexStatus.Valid
      case Some((false, None)) => IndexStatus.Invalid
      case None => IndexStatus.DoesNotExist
    }
  }

  sealed trait IndexAction {
    def indexName: String
  }
  object IndexAction {

    /** Drop this index if it exists. */
    final case class Drop(indexName: String) extends IndexAction

    /** Create this index if it does not exist. */
    final case class Create(
        indexName: String,
        createAction: DBIOAction[?, NoStream, Effect.Write & Effect.Transactional],
    ) extends IndexAction
  }

  /** Indexes managed by this trigger class */
  val defaultIndexActions: List[IndexAction] = List(
    IndexAction
      .Create(
        indexName = "updt_hist_crea_hi_mi_ci_import_updates",
        // Create index statements do not seem to support parameters, so we use the literal value interpolation instead.
        createAction = sqlu"""
          create index concurrently if not exists updt_hist_crea_hi_mi_ci_import_updates
          on update_history_creates (history_id, migration_id, contract_id)
          where record_time = #${CantonTimestamp.MinValue.toMicros}
        """,
      ),
    IndexAction
      .Create(
        indexName = "round_party_totals_sid_pid_cr",
        createAction = sqlu"""
          create index concurrently if not exists round_party_totals_sid_pid_cr
          on round_party_totals (store_id, party, closed_round desc)
        """,
      ),
    IndexAction
      .Create(
        indexName = "updt_hist_tran_hi_eth",
        createAction = sqlu"""
          create index concurrently if not exists updt_hist_tran_hi_eth
          on update_history_transactions (history_id, external_transaction_hash)
          where external_transaction_hash is not null
        """,
      ),
  )

  sealed trait Task extends Product with Serializable with PrettyPrinting
  object Task {

    final case class ExecuteAction(action: IndexAction) extends Task {
      override def pretty: Pretty[this.type] =
        prettyOfClass(
          param("action", _.action.showType),
          param("index", _.action.indexName.unquoted),
        )
    }

    final case class ConfirmActionCompleted(action: IndexAction) extends Task {
      override def pretty: Pretty[this.type] =
        prettyOfClass(
          param("action", _.action.showType),
          param("index", _.action.indexName.unquoted),
        )
    }
  }
}
