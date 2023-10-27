package com.daml.network.directory.store.db

import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.lf.data.Time.Timestamp
import com.daml.network.codegen.java.cn.directory.{DirectoryEntry, DirectoryInstall}
import com.daml.network.directory.store.DirectoryStore
import com.daml.network.environment.RetryProvider
import com.daml.network.store.MultiDomainAcsStore
import com.daml.network.store.db.{AcsQueries, AcsTables, DbCNNodeAppStoreWithoutHistory}
import AcsQueries.{SelectFromAcsTableResult, SelectFromAcsTableWithStateResult}
import com.daml.network.util.{Contract, ContractWithState, QualifiedName, TemplateJsonDecoder}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}
import com.daml.network.codegen.java.cn.directory as directoryCodegen
import com.daml.network.codegen.java.cn.wallet.subscriptions as subsCodegen
import com.daml.network.directory.store.db.DirectoryTables.DirectoryAcsStoreRowData
import com.daml.network.store.db.AcsTables.ContractStateRowData
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.toSQLActionBuilderChain
import io.circe.Json
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

class DbDirectoryStore(
    override val providerParty: PartyId,
    override val svcParty: PartyId,
    storage: DbStorage,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
)(implicit
    override protected val ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends DbCNNodeAppStoreWithoutHistory(
      storage,
      DirectoryTables.acsTableName,
      // TODO (#5544): change this to something better
      storeDescriptor = Json.obj(
        "version" -> Json.fromInt(1),
        "store" -> Json.fromString("DbDirectoryStore"),
        "providerParty" -> Json.fromString(providerParty.toProtoPrimitive),
        "svcParty" -> Json.fromString(svcParty.toProtoPrimitive),
      ),
    )
    with DirectoryStore
    with AcsTables
    with AcsQueries
    with NamedLogging {

  import storage.DbStorageConverters.setParameterByteArray
  import multiDomainAcsStore.waitUntilAcsIngested

  private def storeId: Int = multiDomainAcsStore.storeId

  override def ingestionAcsInsert(
      createdEvent: CreatedEvent,
      contractState: ContractStateRowData,
  )(implicit tc: TraceContext) = {
    DirectoryAcsStoreRowData.fromCreatedEvent(createdEvent).map {
      case DirectoryAcsStoreRowData(
            contract,
            contractExpiresAt,
            directoryInstallUser,
            directoryEntryName,
            directoryEntryOwner,
            subscriptionReferenceContractId,
            subscriptionNextPaymentDueAt,
          ) =>
        val safeDirectoryName = directoryEntryName.map(lengthLimited)
        val contractId = contract.contractId.asInstanceOf[ContractId[Any]]
        val templateId = contract.identifier
        val templateIdPackageId = lengthLimited(contract.identifier.getPackageId)
        val createArguments = payloadJsonFromContract(contract.payload)
        val createArgumentsValue = payloadValueJsonStringFromRecord(contract.mandatoryPayloadValue)
        val contractMetadataCreatedAt = Timestamp.assertFromInstant(contract.metadata.createdAt)
        val contractMetadataContractKeyHash =
          lengthLimited(contract.metadata.contractKeyHash.toStringUtf8)
        val contractMetadataDriverInternal = contract.metadata.driverMetadata.toByteArray
        sqlu"""
              insert into directory_acs_store(store_id, contract_id, template_id_package_id, template_id_qualified_name, create_arguments, create_arguments_value, contract_metadata_created_at,
                                        contract_metadata_contract_key_hash, contract_metadata_driver_internal, contract_expires_at,
                                        assigned_domain, reassignment_counter, reassignment_target_domain,
                                        reassignment_source_domain, reassignment_submitter, reassignment_unassign_id,
                                        directory_install_user, directory_entry_name,
                                        directory_entry_owner, subscription_reference_contract_id,
                                        subscription_next_payment_due_at)
              values ($storeId, $contractId, $templateIdPackageId, ${QualifiedName(
            templateId
          )}, $createArguments, $createArgumentsValue, $contractMetadataCreatedAt,
                      $contractMetadataContractKeyHash, $contractMetadataDriverInternal, $contractExpiresAt,
                      ${contractState.assignedDomain}, ${contractState.reassignmentCounter}, ${contractState.reassignmentTargetDomain},
                      ${contractState.reassignmentSourceDomain}, ${contractState.reassignmentSubmitter}, ${contractState.reassignmentUnassignId},
                      $directoryInstallUser, $safeDirectoryName,
                      $directoryEntryOwner, $subscriptionReferenceContractId,
                      $subscriptionNextPaymentDueAt)
              on conflict do nothing
            """
    }
  }

  override def lookupInstallByUserWithOffset(user: PartyId)(implicit tc: TraceContext): Future[
    MultiDomainAcsStore.QueryResult[Option[Contract[DirectoryInstall.ContractId, DirectoryInstall]]]
  ] = waitUntilAcsIngested {
    for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            DirectoryTables.acsTableName,
            storeId,
            sql"""
              template_id_qualified_name = ${QualifiedName(
                directoryCodegen.DirectoryInstall.COMPANION.TEMPLATE_ID
              )}
                and directory_install_user = $user
            """,
            sql"limit 1",
          ).headOption,
          "lookupInstallByUserWithOffset",
        )
        .getOrElse(throw offsetExpectedError())
    } yield MultiDomainAcsStore.QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map(contractFromRow(directoryCodegen.DirectoryInstall.COMPANION)(_)),
    )
  }

  override def lookupEntryByNameWithOffset(name: String)(implicit tc: TraceContext): Future[
    MultiDomainAcsStore.QueryResult[Option[Contract[DirectoryEntry.ContractId, DirectoryEntry]]]
  ] = waitUntilAcsIngested {
    for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            DirectoryTables.acsTableName,
            storeId,
            sql"""
            template_id_qualified_name = ${QualifiedName(
                directoryCodegen.DirectoryEntry.COMPANION.TEMPLATE_ID
              )}
              and directory_entry_name = ${lengthLimited(name)}
            """,
            sql"limit 1",
          ).headOption,
          "lookupEntryByNameWithOffset",
        )
        .getOrElse(throw offsetExpectedError())
    } yield MultiDomainAcsStore.QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map(contractFromRow(directoryCodegen.DirectoryEntry.COMPANION)(_)),
    )
  }

  override def lookupEntryByParty(partyId: PartyId)(implicit
      tc: TraceContext
  ): Future[Option[Contract[DirectoryEntry.ContractId, DirectoryEntry]]] =
    waitUntilAcsIngested {
      for {
        row <- storage
          .querySingle(
            (selectFromAcsTable(DirectoryTables.acsTableName) ++
              sql"""
              where store_id = $storeId
                and template_id_qualified_name = ${QualifiedName(
                  directoryCodegen.DirectoryEntry.COMPANION.TEMPLATE_ID
                )}
                and directory_entry_owner = $partyId
                and directory_entry_name >= ''
              order by directory_entry_name
              limit 1
          """).toActionBuilder.as[SelectFromAcsTableResult].headOption,
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
          (selectFromAcsTable(DirectoryTables.acsTableName) ++
            sql"""
              where store_id = $storeId
                and template_id_qualified_name = ${QualifiedName(
                directoryCodegen.DirectoryEntry.COMPANION.TEMPLATE_ID
              )}
                and directory_entry_name ^@ $limitedPrefix
              order by directory_entry_name
              limit $pageSize
          """).toActionBuilder.as[SelectFromAcsTableResult],
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
                       #${SelectFromAcsTableWithStateResult.sqlColumnsCommaSeparated("idle.")},
                       #${SelectFromAcsTableResult.sqlColumnsCommaSeparated("ctx.")}
              from     directory_acs_store idle
              join     directory_acs_store ctx
              on       idle.subscription_reference_contract_id = ctx.subscription_reference_contract_id
                and      ctx.store_id = idle.store_id
                and      ctx.assigned_domain = idle.assigned_domain
              where    idle.store_id = $storeId
                and      idle.template_id_qualified_name = ${QualifiedName(
              subsCodegen.SubscriptionIdleState.COMPANION.TEMPLATE_ID
            )}
                and      ctx.template_id_qualified_name = ${QualifiedName(
              directoryCodegen.DirectoryEntryContext.COMPANION.TEMPLATE_ID
            )}
                and      idle.subscription_next_payment_due_at < $now
                and      idle.assigned_domain is not null
              order by idle.subscription_next_payment_due_at
              limit    $limit
          """.as[(SelectFromAcsTableWithStateResult, SelectFromAcsTableResult)],
          "listExpiredDirectorySubscriptions",
        )
    } yield joinedRows.map { case (idleRow, ctxRow) =>
      val idleContract =
        assignedContractFromRow(subsCodegen.SubscriptionIdleState.COMPANION)(idleRow)
      val ctxContract = contractFromRow(directoryCodegen.DirectoryEntryContext.COMPANION)(ctxRow)
      DirectoryStore.IdleDirectorySubscription(idleContract, ctxContract)
    }
  }

  override def lookupDirectoryEntryContext(
      reference: subsCodegen.SubscriptionRequest.ContractId
  )(implicit tc: TraceContext): Future[Option[ContractWithState[
    directoryCodegen.DirectoryEntryContext.ContractId,
    directoryCodegen.DirectoryEntryContext,
  ]]] =
    waitUntilAcsIngested {
      for {
        row <- storage
          .querySingle(
            selectFromAcsTableWithState(
              DirectoryTables.acsTableName,
              storeId,
              where = sql"""
               template_id_qualified_name = ${QualifiedName(
                  directoryCodegen.DirectoryEntryContext.COMPANION.TEMPLATE_ID
                )}
           and subscription_reference_contract_id = $reference""",
              orderLimit = sql"""limit 1""",
            ).headOption,
            "lookupDirectoryEntryContext",
          )
          .value
      } yield row.map(contractWithStateFromRow(directoryCodegen.DirectoryEntryContext.COMPANION)(_))
    }
}
