// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import com.digitalasset.canton.config.{DbConfig, StorageConfig}
import com.digitalasset.canton.lifecycle.{FutureUnlessShutdown, *}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.Thereafter.syntax.ThereafterOps
import com.digitalasset.canton.util.MonadUtil
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.automation.SqlIndexInitializationTrigger.{
  IndexAction,
  defaultExpectedIndexes,
}
import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
import slick.dbio.{DBIOAction, Effect, NoStream}
import slick.jdbc.GetResult
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import scala.concurrent.ExecutionContext
import scala.util.Try

/** A trigger that asynchronously creates or drops SQL indexes at application startup.
  */
class SqlIndexInitializationTrigger(
    storage: DbStorage,
    config: StorageConfig,
    protected val context: TriggerContext,
    expectedIndexes: Map[String, IndexAction] = defaultExpectedIndexes,
)(implicit ec: ExecutionContext, tracer: Tracer)
    extends InitializationTrigger
    with HasCloseContext {
  import SqlIndexInitializationTrigger.*

  private def getPostgresSchema(config: DbConfig.Postgres): String =
    Try(config.config.getString("properties.currentSchema")).toOption.getOrElse("public")

  private def listIndexes(
      schemaName: String
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[Map[String, IndexData]] = {
    storage.query(
      sql"""
        select indexname, tablename, indexdef
        from pg_indexes
        where schemaname = $schemaName
    """.as[(String, String, String)]
        .map(rs => rs.map(row => row._1 -> IndexData(row._2, row._3)).toMap),
      "listIndexes",
    )
  }

  override def complete()(implicit traceContext: TraceContext): FutureUnlessShutdown[Unit] = {
    config match {
      case postgresConfig: DbConfig.Postgres =>
        val schemaName = getPostgresSchema(postgresConfig)
        completePostgres(schemaName)
      case _ =>
        logger.info("Not using postgres storage, skipping")
        FutureUnlessShutdown.unit
    }
  }

  private def completePostgres(
      schemaName: String
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[Unit] = {
    for {
      actual <- listIndexes(schemaName)
      unexpected = actual.keySet -- expectedIndexes.keySet
      _ = if (unexpected.nonEmpty) {
        logger.debug(s"Found SQL indexes: $actual")
        logger.warn(s"Found unexpected SQL indexes: ${unexpected.mkString(", ")}. ")
      }
      _ <- MonadUtil.sequentialTraverse(expectedIndexes.toSeq) {
        case (indexName, IndexAction.ManagedByFlyway) =>
          if (!actual.contains(indexName)) {
            logger.warn(
              s"Index $indexName is expected to have been created by Flyway, but was not found"
            )
          }
          FutureUnlessShutdown.unit
        case (indexName, IndexAction.Drop) =>
          logger.info(s"Dropping index $indexName")
          storage.update(sqlu"drop index concurrently if exists #$indexName", indexName)
        case (indexName, IndexAction.Create(action)) =>
          if (!actual.contains(indexName)) {
            logger.info(s"Creating index $indexName")
            storage
              .update(action, indexName)
              .thereafter(_ => logger.info(s"Finished creating index $indexName"))
          } else {
            FutureUnlessShutdown.unit
          }
      }
    } yield ()
  }
}

object SqlIndexInitializationTrigger {
  final case class IndexData(
      tableName: String,
      definition: String,
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] = prettyOfClass(
      param("tableName", _.tableName.doubleQuoted),
      param("definition", _.definition.doubleQuoted),
    )
  }
  implicit val GetResultIndexData: GetResult[IndexData] =
    GetResult { prs =>
      import prs.*
      (IndexData.apply _).tupled(
        (
          <<[String],
          <<[String],
        )
      )
    }

  sealed trait IndexAction
  object IndexAction {

    /** Do not touch this index, it is managed by Flyway. */
    final case object ManagedByFlyway extends IndexAction

    /** Drop this index if it exists. */
    final case object Drop extends IndexAction

    /** Create this index if it does not exist. */
    final case class Create(action: DBIOAction[?, NoStream, Effect.Write & Effect.Transactional])
        extends IndexAction
  }

  /** All indexes created by Flyway after applying all migrations in `apps/common/src/main/resources/db/migration/canton-network/postgres/stable` */
  val flywayIndexes: Map[String, IndexAction] = Map(
    "flyway_schema_history_pk" -> IndexAction.ManagedByFlyway,
    "flyway_schema_history_s_idx" -> IndexAction.ManagedByFlyway,
    "store_descriptors_pkey" -> IndexAction.ManagedByFlyway,
    "store_descriptors_descriptor_key" -> IndexAction.ManagedByFlyway,
    "store_last_ingested_offsets_pkey" -> IndexAction.ManagedByFlyway,
    "acs_store_template_pkey" -> IndexAction.ManagedByFlyway,
    "acs_store_template_sid_mid_cid" -> IndexAction.ManagedByFlyway,
    "acs_store_template_sid_mid_tid_en" -> IndexAction.ManagedByFlyway,
    "acs_store_template_sid_mid_tid_ce" -> IndexAction.ManagedByFlyway,
    "acs_store_sid_mid_sn" -> IndexAction.ManagedByFlyway,
    "acs_store_template_sid_mid_tid_sn" -> IndexAction.ManagedByFlyway,
    "incomplete_reassignments_pkey" -> IndexAction.ManagedByFlyway,
    "incomplete_reassignments_sid_mid_uid" -> IndexAction.ManagedByFlyway,
    "incomplete_reassignments_sid_mid_cid" -> IndexAction.ManagedByFlyway,
    "txlog_store_template_pkey" -> IndexAction.ManagedByFlyway,
    "txlog_store_template_sid_et" -> IndexAction.ManagedByFlyway,
    "txlog_store_template_si_mi_di_rc" -> IndexAction.ManagedByFlyway,
    "txlog_store_sid_mid_did_rt" -> IndexAction.ManagedByFlyway,
    "validator_acs_store_pkey" -> IndexAction.ManagedByFlyway,
    "validator_acs_store_store_id_migration_id_contract_id_idx" -> IndexAction.ManagedByFlyway,
    "validator_acs_store_store_id_migration_id_template_id_quali_idx" -> IndexAction.ManagedByFlyway,
    "validator_acs_store_store_id_migration_id_template_id_qual_idx1" -> IndexAction.ManagedByFlyway,
    "validator_acs_store_store_id_migration_id_state_number_idx" -> IndexAction.ManagedByFlyway,
    "validator_acs_store_store_id_migration_id_template_id_qual_idx2" -> IndexAction.ManagedByFlyway,
    "validator_acs_store_sid_mid_tid_up" -> IndexAction.ManagedByFlyway,
    "validator_acs_store_sid_mid_tid_un" -> IndexAction.ManagedByFlyway,
    "validator_acs_store_sid_mid_tid_pp" -> IndexAction.ManagedByFlyway,
    "validator_acs_store_sid_mid_tid_vp" -> IndexAction.ManagedByFlyway,
    "validator_acs_store_sid_mid_tid_tdi" -> IndexAction.ManagedByFlyway,
    "user_wallet_txlog_store_pkey" -> IndexAction.ManagedByFlyway,
    "user_wallet_txlog_store_store_id_entry_type_idx" -> IndexAction.ManagedByFlyway,
    "user_wallet_txlog_store_store_id_migration_id_domain_id_rec_idx" -> IndexAction.ManagedByFlyway,
    "user_wallet_txlog_store_sid_tid" -> IndexAction.ManagedByFlyway,
    "user_wallet_txlog_store_sid_mid_did_rt" -> IndexAction.ManagedByFlyway,
    "user_wallet_txlog_store_si_ti_ei_key" -> IndexAction.ManagedByFlyway,
    "user_wallet_acs_store_pkey" -> IndexAction.ManagedByFlyway,
    "user_wallet_acs_store_store_id_migration_id_contract_id_idx" -> IndexAction.ManagedByFlyway,
    "user_wallet_acs_store_store_id_migration_id_template_id_qua_idx" -> IndexAction.ManagedByFlyway,
    "user_wallet_acs_store_store_id_migration_id_template_id_qu_idx1" -> IndexAction.ManagedByFlyway,
    "user_wallet_acs_store_store_id_migration_id_state_number_idx" -> IndexAction.ManagedByFlyway,
    "user_wallet_acs_store_store_id_migration_id_template_id_qu_idx2" -> IndexAction.ManagedByFlyway,
    "user_wallet_acs_store_sid_mid_tid_rcr" -> IndexAction.ManagedByFlyway,
    "user_wallet_acs_store_sid_mid_tid_rcv" -> IndexAction.ManagedByFlyway,
    "scan_acs_store_pkey" -> IndexAction.ManagedByFlyway,
    "scan_acs_store_store_id_migration_id_contract_id_idx" -> IndexAction.ManagedByFlyway,
    "scan_acs_store_store_id_migration_id_template_id_qualified__idx" -> IndexAction.ManagedByFlyway,
    "scan_acs_store_store_id_migration_id_template_id_qualified_idx1" -> IndexAction.ManagedByFlyway,
    "scan_acs_store_store_id_migration_id_state_number_idx" -> IndexAction.ManagedByFlyway,
    "scan_acs_store_store_id_migration_id_template_id_qualified_idx2" -> IndexAction.ManagedByFlyway,
    "scan_acs_store_sid_mid_tid_val" -> IndexAction.ManagedByFlyway,
    "scan_acs_store_sid_mid_tid_farp" -> IndexAction.ManagedByFlyway,
    "scan_acs_store_sid_mid_tid_vlrc" -> IndexAction.ManagedByFlyway,
    "scan_acs_store_sid_mid_den_tpo_exp" -> IndexAction.ManagedByFlyway,
    "scan_acs_store_sid_mid_deo_den_exp" -> IndexAction.ManagedByFlyway,
    "scan_acs_store_sid_mid_tidqn_v" -> IndexAction.ManagedByFlyway,
    "scan_acs_store_sid_mid_tid_mtm_mtd" -> IndexAction.ManagedByFlyway,
    "scan_acs_store_wallet_party_idx" -> IndexAction.ManagedByFlyway,
    "scan_acs_store_sid_mid_tid_tpr" -> IndexAction.ManagedByFlyway,
    "update_history_exercises_pkey" -> IndexAction.ManagedByFlyway,
    "updt_hist_exer_hi_mi_rt_co" -> IndexAction.ManagedByFlyway,
    "updt_hist_exer_unique" -> IndexAction.ManagedByFlyway,
    "update_history_creates_pkey" -> IndexAction.ManagedByFlyway,
    "updt_hist_crea_unique" -> IndexAction.ManagedByFlyway,
    "contract_create_lookup" -> IndexAction.ManagedByFlyway,
    "updt_hist_crea_hi_tidmn_tiden_pn_rid" -> IndexAction.ManagedByFlyway,
    "updt_hist_crea_hi_mi_rt" -> IndexAction.ManagedByFlyway,
    "round_totals_pkey" -> IndexAction.ManagedByFlyway,
    "txlog_backfilling_status_pkey" -> IndexAction.ManagedByFlyway,
    "sv_acs_store_pkey" -> IndexAction.ManagedByFlyway,
    "sv_acs_store_store_id_migration_id_contract_id_idx" -> IndexAction.ManagedByFlyway,
    "sv_acs_store_store_id_migration_id_template_id_qualified_na_idx" -> IndexAction.ManagedByFlyway,
    "sv_acs_store_store_id_migration_id_template_id_qualified_n_idx1" -> IndexAction.ManagedByFlyway,
    "sv_acs_store_store_id_migration_id_state_number_idx" -> IndexAction.ManagedByFlyway,
    "sv_acs_store_store_id_migration_id_template_id_qualified_n_idx2" -> IndexAction.ManagedByFlyway,
    "sv_acs_store_sid_mid_tid_vocs" -> IndexAction.ManagedByFlyway,
    "sv_acs_store_sid_mid_tid_asicn" -> IndexAction.ManagedByFlyway,
    "dso_acs_store_pkey" -> IndexAction.ManagedByFlyway,
    "dso_acs_store_store_id_migration_id_contract_id_idx" -> IndexAction.ManagedByFlyway,
    "dso_acs_store_store_id_migration_id_template_id_qualified_n_idx" -> IndexAction.ManagedByFlyway,
    "dso_acs_store_store_id_migration_id_template_id_qualified__idx1" -> IndexAction.ManagedByFlyway,
    "dso_acs_store_store_id_migration_id_state_number_idx" -> IndexAction.ManagedByFlyway,
    "dso_acs_store_store_id_migration_id_template_id_qualified__idx2" -> IndexAction.ManagedByFlyway,
    "dso_acs_store_sid_tid_mr" -> IndexAction.ManagedByFlyway,
    "dso_acs_store_sid_tid_tcid" -> IndexAction.ManagedByFlyway,
    "dso_acs_store_sid_tid_croe" -> IndexAction.ManagedByFlyway,
    "dso_acs_store_sid_tid_ah_c" -> IndexAction.ManagedByFlyway,
    "dso_acs_store_coupons" -> IndexAction.ManagedByFlyway,
    "dso_acs_store_sid_tid_sot" -> IndexAction.ManagedByFlyway,
    "dso_acs_store_sid_mid_tid_scp_scn" -> IndexAction.ManagedByFlyway,
    "dso_acs_store_sid_mid_tid_scn" -> IndexAction.ManagedByFlyway,
    "dso_acs_store_sid_mid_tid_v_tp" -> IndexAction.ManagedByFlyway,
    "dso_acs_store_sid_mid_tid_ah_r" -> IndexAction.ManagedByFlyway,
    "dso_acs_store_sid_mid_tid_ere_r" -> IndexAction.ManagedByFlyway,
    "dso_acs_store_sid_mid_tid_acecc" -> IndexAction.ManagedByFlyway,
    "dso_acs_store_sid_mid_tid_sccid" -> IndexAction.ManagedByFlyway,
    "dso_acs_store_sid_mid_tid_snpd" -> IndexAction.ManagedByFlyway,
    "dso_acs_store_sid_mid_tid_cen_exp" -> IndexAction.ManagedByFlyway,
    "dso_acs_store_wallet_party_idx" -> IndexAction.ManagedByFlyway,
    "dso_acs_store_sid_mid_tid_mtm_mtd" -> IndexAction.ManagedByFlyway,
    "txlog_first_ingested_update_pkey" -> IndexAction.ManagedByFlyway,
    "splitwell_acs_store_pkey" -> IndexAction.ManagedByFlyway,
    "splitwell_acs_store_store_id_migration_id_contract_id_idx" -> IndexAction.ManagedByFlyway,
    "splitwell_acs_store_store_id_migration_id_template_id_quali_idx" -> IndexAction.ManagedByFlyway,
    "splitwell_acs_store_store_id_migration_id_template_id_qual_idx1" -> IndexAction.ManagedByFlyway,
    "splitwell_acs_store_store_id_migration_id_state_number_idx" -> IndexAction.ManagedByFlyway,
    "splitwell_acs_store_store_id_migration_id_template_id_qual_idx2" -> IndexAction.ManagedByFlyway,
    "update_history_descriptors_pkey" -> IndexAction.ManagedByFlyway,
    "unique_party_participant_id_store_name" -> IndexAction.ManagedByFlyway,
    "update_history_last_ingested_offsets_pkey" -> IndexAction.ManagedByFlyway,
    "updt_hist_tran_hi_mi_ui_import_updates" -> IndexAction.ManagedByFlyway,
    "update_history_transactions_pkey" -> IndexAction.ManagedByFlyway,
    "updt_hist_tran_hi_mi_di_rt" -> IndexAction.ManagedByFlyway,
    "updt_hist_tran_hi_mi_rt_di" -> IndexAction.ManagedByFlyway,
    "updt_hist_tran_hi_ui" -> IndexAction.ManagedByFlyway,
    "updt_hist_tran_hi_mi_ui_u" -> IndexAction.ManagedByFlyway,
    "dso_txlog_store_pkey" -> IndexAction.ManagedByFlyway,
    "dso_txlog_store_store_id_entry_type_idx" -> IndexAction.ManagedByFlyway,
    "dso_txlog_store_store_id_migration_id_domain_id_record_time_idx" -> IndexAction.ManagedByFlyway,
    "dso_txlog_store_sid_et_an_e" -> IndexAction.ManagedByFlyway,
    "active_parties_pkey" -> IndexAction.ManagedByFlyway,
    "active_parties_sid_p_cr_idx" -> IndexAction.ManagedByFlyway,
    "update_history_unassignments_pkey" -> IndexAction.ManagedByFlyway,
    "updt_hist_unas_hi_mi_di_rt" -> IndexAction.ManagedByFlyway,
    "updt_hist_unas_hi_mi_rt_di" -> IndexAction.ManagedByFlyway,
    "updt_hist_unas_hi_mi_ui_u" -> IndexAction.ManagedByFlyway,
    "update_history_assignments_pkey" -> IndexAction.ManagedByFlyway,
    "updt_hist_assi_hi_mi_di_rt" -> IndexAction.ManagedByFlyway,
    "updt_hist_assi_hi_mi_rt_di" -> IndexAction.ManagedByFlyway,
    "updt_hist_assi_hi_mi_ui_u" -> IndexAction.ManagedByFlyway,
    "update_history_backfilling_pkey" -> IndexAction.ManagedByFlyway,
    "round_party_totals_pkey" -> IndexAction.ManagedByFlyway,
    "acs_snapshot_data_pkey" -> IndexAction.ManagedByFlyway,
    "acs_snapshot_data_all_filters" -> IndexAction.ManagedByFlyway,
    "acs_snapshot_data_template_only_filter" -> IndexAction.ManagedByFlyway,
    "acs_snapshot_data_stakeholder_only_filter" -> IndexAction.ManagedByFlyway,
    "acs_snapshot_data_cid" -> IndexAction.ManagedByFlyway,
    "acs_snapshot_pkey" -> IndexAction.ManagedByFlyway,
    "external_party_wallet_acs_store_pkey" -> IndexAction.ManagedByFlyway,
    "external_party_wallet_acs_sto_store_id_migration_id_contrac_idx" -> IndexAction.ManagedByFlyway,
    "external_party_wallet_acs_sto_store_id_migration_id_templat_idx" -> IndexAction.ManagedByFlyway,
    "external_party_wallet_acs_sto_store_id_migration_id_templa_idx1" -> IndexAction.ManagedByFlyway,
    "external_party_wallet_acs_sto_store_id_migration_id_state_n_idx" -> IndexAction.ManagedByFlyway,
    "external_party_wallet_acs_sto_store_id_migration_id_templa_idx2" -> IndexAction.ManagedByFlyway,
    "external_party_wallet_acs_store_sid_mid_tid_rcr" -> IndexAction.ManagedByFlyway,
    "scan_txlog_store_sid_et_ei" -> IndexAction.ManagedByFlyway,
    "scan_txlog_store_pkey" -> IndexAction.ManagedByFlyway,
    "scan_txlog_store_store_id_migration_id_domain_id_record_tim_idx" -> IndexAction.ManagedByFlyway,
    "scan_txlog_store_sid_irt_r_en" -> IndexAction.ManagedByFlyway,
    "scan_txlog_store_sid_mid_did_rt" -> IndexAction.ManagedByFlyway,
    "scan_txlog_store_sid_tfer_cmd_sender_nonce" -> IndexAction.ManagedByFlyway,
  )

  /** Indexes managed by this trigger class */
  val customIndexes: Map[String, IndexAction] = Map(
    "updt_hist_crea_hi_mi_ci_import_updates" -> IndexAction.Create(sqlu"""
        create index concurrently if not exists updt_hist_crea_hi_mi_ci_import_updates
        on update_history_creates (history_id, migration_id, contract_id)
        where record_time = -62135596800000000
         """)
  )

  val defaultExpectedIndexes: Map[String, IndexAction] = flywayIndexes ++ customIndexes
}
