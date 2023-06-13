package com.daml.network.directory.store.db

import com.daml.network.codegen.java.cn.directory.{DirectoryEntry, DirectoryInstall}
import com.daml.network.directory.config.DirectoryDomainConfig
import com.daml.network.directory.store.DirectoryStore
import com.daml.network.environment.RetryProvider
import com.daml.network.store.MultiDomainAcsStore
import com.daml.network.store.db.AcsTables.AcsStoreRowTemplate
import com.daml.network.store.db.{AcsQueries, AcsTables, DbCNNodeAppStoreWithoutHistory}
import com.daml.network.util.{Contract, TemplateJsonDecoder}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}
import com.daml.network.codegen.java.cn.directory as directoryCodegen
import com.daml.network.codegen.java.cn.wallet.subscriptions as subsCodegen
import io.circe.Json

class DbDirectoryStore(
    override val providerParty: PartyId,
    override val svcParty: PartyId,
    storage: DbStorage,
    override protected[this] val domainConfig: DirectoryDomainConfig,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
)(implicit
    override protected val ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends DbCNNodeAppStoreWithoutHistory(
      storage,
      DbDirectoryStore.tableName,
      // TODO (#5544): change this to something better
      storeDescriptor = Json.obj("version" -> Json.fromInt(1)),
    )
    with DirectoryStore
    with AcsTables
    with AcsQueries {

  import storage.api.jdbcProfile.api.*
  import multiDomainAcsStore.{waitUntilAcsIngested, lastIngestedOffset}

  def storeId: Int = multiDomainAcsStore.storeId

  override def lookupInstallByUserWithOffset(user: PartyId)(implicit tc: TraceContext): Future[
    MultiDomainAcsStore.QueryResult[Option[Contract[DirectoryInstall.ContractId, DirectoryInstall]]]
  ] = waitUntilAcsIngested {
    for {
      offset <- lastIngestedOffset(storage, storeId)
      row <- storage
        .querySingle(
          sql"""
              #${selectFromAcsTable(DbDirectoryStore.tableName)}
              where store_id = $storeId
                and template_id = ${directoryCodegen.DirectoryInstall.COMPANION.TEMPLATE_ID}
                and directory_install_user = $user
              limit 1
          """.as[AcsStoreRowTemplate].headOption,
          "lookupInstallByUserWithOffset",
        )
        .value
    } yield MultiDomainAcsStore.QueryResult(
      offset,
      row.map(contractFromRow(directoryCodegen.DirectoryInstall.COMPANION)(_)),
    )
  }

  override def lookupEntryByNameWithOffset(name: String)(implicit tc: TraceContext): Future[
    MultiDomainAcsStore.QueryResult[Option[Contract[DirectoryEntry.ContractId, DirectoryEntry]]]
  ] = waitUntilAcsIngested {
    for {
      offset <- lastIngestedOffset(storage, storeId)
      row <- storage
        .querySingle(
          sql"""
            #${selectFromAcsTable(DbDirectoryStore.tableName)}
            where store_id = $storeId
              and template_id = ${directoryCodegen.DirectoryEntry.COMPANION.TEMPLATE_ID}
              and directory_entry_name = ${lengthLimited(name)}
            limit 1
        """.as[AcsStoreRowTemplate].headOption,
          "lookupEntryByNameWithOffset",
        )
        .value
    } yield MultiDomainAcsStore.QueryResult(
      offset,
      row.map(contractFromRow(directoryCodegen.DirectoryEntry.COMPANION)(_)),
    )
  }

  override def lookupEntryByParty(partyId: PartyId)(implicit
      tc: TraceContext
  ): Future[Option[Contract[DirectoryEntry.ContractId, DirectoryEntry]]] =
    waitUntilAcsIngested {
      for {
        row <- storage
          .querySingle(
            sql"""
              #${selectFromAcsTable(DbDirectoryStore.tableName)}
              where store_id = $storeId
                and template_id = ${directoryCodegen.DirectoryEntry.COMPANION.TEMPLATE_ID}
                and directory_entry_owner = $partyId
                and directory_entry_name >= ''
              order by directory_entry_name
              limit 1
          """.as[AcsStoreRowTemplate].headOption,
            "lookupEntryByParty",
          )
          .value
      } yield row.map(contractFromRow(directoryCodegen.DirectoryEntry.COMPANION)(_))
    }

  override def listEntries(namePrefix: String, pageSize: Int)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[DirectoryEntry.ContractId, DirectoryEntry]]] = waitUntilAcsIngested {
    val limitedPrefix = lengthLimited(namePrefix)
    for {
      rows <- storage
        .query(
          sql"""
              #${selectFromAcsTable(DbDirectoryStore.tableName)}
              where store_id = $storeId
                and template_id = ${directoryCodegen.DirectoryEntry.COMPANION.TEMPLATE_ID}
                and directory_entry_name ^@ $limitedPrefix
              order by directory_entry_name
              limit $pageSize
          """.as[AcsStoreRowTemplate],
          "listEntries",
        )
    } yield rows.map(contractFromRow(directoryCodegen.DirectoryEntry.COMPANION)(_))
  }

  override def listExpiredDirectorySubscriptions(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[DirectoryStore.IdleDirectorySubscription]] = waitUntilAcsIngested {
    for {
      joinedRows <- storage
        .query(
          sql"""
              select
                       idle.store_id,
                       idle.event_number,
                       idle.contract_id,
                       idle.template_id,
                       idle.create_arguments,
                       idle.contract_metadata_created_at,
                       idle.contract_metadata_contract_key_hash,
                       idle.contract_metadata_driver_internal,
                       idle.contract_expires_at,
                       ctx.store_id,
                       ctx.event_number,
                       ctx.contract_id,
                       ctx.template_id,
                       ctx.create_arguments,
                       ctx.contract_metadata_created_at,
                       ctx.contract_metadata_contract_key_hash,
                       ctx.contract_metadata_driver_internal,
                       ctx.contract_expires_at
              from     directory_acs_store idle
              join     directory_acs_store ctx
              on       idle.subscription_context_contract_id = ctx.contract_id
                and      ctx.store_id = idle.store_id
              where    idle.store_id = $storeId
                and      idle.template_id = ${subsCodegen.SubscriptionIdleState.COMPANION.TEMPLATE_ID}
                and      ctx.template_id = ${directoryCodegen.DirectoryEntryContext.COMPANION.TEMPLATE_ID}
                and      idle.subscription_next_payment_due_at < $now
              order by idle.subscription_next_payment_due_at
              limit    $limit
          """.as[(AcsStoreRowTemplate, AcsStoreRowTemplate)],
          "listExpiredDirectorySubscriptions",
        )
    } yield joinedRows.map { case (idleRow, ctxRow) =>
      val idleContract = contractFromRow(subsCodegen.SubscriptionIdleState.COMPANION)(idleRow)
      val ctxContract = contractFromRow(directoryCodegen.DirectoryEntryContext.COMPANION)(ctxRow)
      DirectoryStore.IdleDirectorySubscription(idleContract, ctxContract)
    }
  }

}

object DbDirectoryStore {
  val tableName: String = DirectoryTables.DirectoryAcsStore.baseTableRow.tableName
}
