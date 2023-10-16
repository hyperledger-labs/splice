package com.daml.network.scan.store.db

import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.lf.data.Time.Timestamp
import com.daml.network.codegen.java.cc.coin.{CoinRules, FeaturedAppRight}
import com.daml.network.codegen.java.cc.coinimport.ImportCrate
import com.daml.network.codegen.java.cn.cns.CnsRules
import com.daml.network.environment.RetryProvider
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient
import com.daml.network.scan.store.db.ScanTables.{ScanAcsStoreRowData, ScanTxLogRowData}
import com.daml.network.scan.store.{ScanStore, SortOrder, TxLogEntry, TxLogIndexRecord}
import com.daml.network.store.{Limit, LimitHelpers}
import com.daml.network.store.TxLogStore.TransactionTreeSource
import com.daml.network.store.db.{AcsQueries, AcsTables, DbCNNodeAppStoreWithHistory}
import com.daml.network.util.{ContractWithState, QualifiedName, TemplateJsonDecoder}
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import io.circe.Json
import cats.implicits.*
import com.daml.network.codegen.java.cn.svcrules.SvcRules
import com.daml.network.store.db.AcsTables.ContractStateRowData

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.toSQLActionBuilderChain
import com.daml.network.scan.store.SortOrder.Ascending
import com.daml.network.scan.store.SortOrder.Descending

class DbScanStore(
    override val svcParty: PartyId,
    storage: DbStorage,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val transactionTreeSource: TransactionTreeSource,
    override protected val retryProvider: RetryProvider,
)(implicit
    override protected val ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends DbCNNodeAppStoreWithHistory[TxLogIndexRecord, TxLogEntry](
      storage,
      ScanTables.acsTableName,
      ScanTables.txLogTableName,
      // TODO (#5544): change this to something better
      storeDescriptor = Json.obj(
        "version" -> Json.fromInt(1),
        "svc_party" -> Json.fromString(svcParty.toProtoPrimitive),
      ),
    )
    with ScanStore
    with AcsTables
    with AcsQueries
    with NamedLogging
    with LimitHelpers {

  import storage.DbStorageConverters.setParameterByteArray
  import multiDomainAcsStore.waitUntilAcsIngested

  def storeId: Int = multiDomainAcsStore.storeId

  override def ingestionAcsInsert(createdEvent: CreatedEvent, contractState: ContractStateRowData)(
      implicit tc: TraceContext
  ) = {
    ScanAcsStoreRowData.fromCreatedEvent(createdEvent).map {
      case ScanAcsStoreRowData(
            contract,
            contractExpiresAt,
            round,
            validator,
            amount,
            importCrateReceiverName,
            featuredAppRightProvider,
          ) =>
        val contractId = contract.contractId.asInstanceOf[ContractId[Any]]
        val templateId = contract.identifier
        val templateIdPackageId = lengthLimited(contract.identifier.getPackageId)
        val createArguments = payloadJsonFromContract(contract.payload)
        val createArgumentsValue = payloadValueJsonStringFromRecord(contract.mandatoryPayloadValue)
        val contractMetadataCreatedAt = Timestamp.assertFromInstant(contract.metadata.createdAt)
        val contractMetadataContractKeyHash =
          lengthLimited(contract.metadata.contractKeyHash.toStringUtf8)
        val contractMetadataDriverInternal = contract.metadata.driverMetadata.toByteArray
        val safeImportCrateReceiverName = importCrateReceiverName.map(lengthLimited)
        sqlu"""
              insert into scan_acs_store(store_id, contract_id, template_id_package_id, template_id_qualified_name, create_arguments, create_arguments_value, contract_metadata_created_at,
              contract_metadata_contract_key_hash, contract_metadata_driver_internal, contract_expires_at,
              assigned_domain, reassignment_counter, reassignment_target_domain,
              reassignment_source_domain, reassignment_submitter, reassignment_unassign_id,
              round, validator, amount, import_crate_receiver, featured_app_right_provider)
              values ($storeId, $contractId, $templateIdPackageId, ${QualifiedName(
            templateId
          )}, $createArguments, $createArgumentsValue, $contractMetadataCreatedAt,
                      $contractMetadataContractKeyHash, $contractMetadataDriverInternal, $contractExpiresAt,
                      ${contractState.assignedDomain}, ${contractState.reassignmentCounter}, ${contractState.reassignmentTargetDomain},
                      ${contractState.reassignmentSourceDomain}, ${contractState.reassignmentSubmitter}, ${contractState.reassignmentUnassignId},
                      $round, $validator, $amount, $safeImportCrateReceiverName, $featuredAppRightProvider)
              on conflict do nothing
              """
    }
  }

  override def ingestionTxLogInsert(record: TxLogIndexRecord)(implicit
      tc: TraceContext
  ) = {
    val ScanTxLogRowData(
      eventId,
      offset,
      domainId,
      acsContractId,
      indexRecordType,
      round,
      rewardAmount,
      rewardedParty,
      balanceChangeToInitialAmountAsOfRoundZero,
      balanceChangeChangeToHoldingFeesRate,
      extraTrafficValidator,
      extraTrafficPurchaseTrafficPurchase,
      extraTrafficPurchaseCcSpent,
    ) = ScanTxLogRowData.fromTxLogIndexRecord(record)
    val safeEventId = lengthLimited(eventId)
    val safeOffset = offset.map(lengthLimited)
    Right(sqlu"""
          insert into scan_txlog_store(store_id, event_id, index_record_type, "offset", domain_id, acs_contract_id,
          round, reward_amount, rewarded_party,
          balance_change_change_to_initial_amount_as_of_round_zero, balance_change_change_to_holding_fees_rate,
          extra_traffic_validator, extra_traffic_purchase_traffic_purchased, extra_traffic_purchase_cc_spent)
          values ($storeId, $safeEventId, $indexRecordType, $safeOffset, $domainId, $acsContractId,
                  $round, $rewardAmount, $rewardedParty,
                  $balanceChangeToInitialAmountAsOfRoundZero, $balanceChangeChangeToHoldingFeesRate,
                  $extraTrafficValidator, $extraTrafficPurchaseTrafficPurchase, $extraTrafficPurchaseCcSpent)
          on conflict do nothing
        """)
  }

  override def lookupCoinRules()(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[CoinRules.ContractId, CoinRules]]] =
    waitUntilAcsIngested {
      for {
        row <- storage
          .querySingle(
            selectFromAcsTableWithState(
              ScanTables.acsTableName,
              storeId,
              where = sql"""template_id_qualified_name = ${QualifiedName(CoinRules.TEMPLATE_ID)}""",
              orderLimit = sql"""order by event_number desc limit 1""",
            ).headOption,
            "lookupCoinRules",
          )
          .value
        contractWithState = row.map(
          contractWithStateFromRow(CoinRules.COMPANION)(_)
        )
      } yield contractWithState
    }

  override def lookupCnsRules()(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[CnsRules.ContractId, CnsRules]]] =
    waitUntilAcsIngested {
      for {
        row <- storage
          .querySingle(
            selectFromAcsTableWithState(
              ScanTables.acsTableName,
              storeId,
              where = sql"""template_id_qualified_name = ${QualifiedName(CnsRules.TEMPLATE_ID)}""",
              orderLimit = sql"""order by event_number desc limit 1""",
            ).headOption,
            "lookupCnsRules",
          )
          .value
        contractWithState = row.map(
          contractWithStateFromRow(CnsRules.COMPANION)(_)
        )
      } yield contractWithState
    }

  override def lookupSvcRules()(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[SvcRules.ContractId, SvcRules]]] =
    waitUntilAcsIngested {
      for {
        row <- storage
          .querySingle(
            selectFromAcsTableWithState(
              ScanTables.acsTableName,
              storeId,
              where = sql"""template_id_qualified_name = ${QualifiedName(SvcRules.TEMPLATE_ID)}""",
              orderLimit = sql"""order by event_number desc limit 1""",
            ).headOption,
            "lookupSvcRules",
          )
          .value
        contractWithState = row.map(
          contractWithStateFromRow(SvcRules.COMPANION)(_)
        )
      } yield contractWithState
    }

  override def getTotalCoinBalance(asOfEndOfRound: Long)(implicit
      tc: TraceContext
  ): Future[BigDecimal] =
    waitUntilAcsIngested {
      for {
        result <- storage.query(
          sql"""
               select sum(balance_change_change_to_initial_amount_as_of_round_zero) -
                     ($asOfEndOfRound + 1) * sum(balance_change_change_to_holding_fees_rate)
               from scan_txlog_store
               where store_id = $storeId
                 and index_record_type = ${TxLogIndexRecord.BalanceChangeIndexRecord.dbType}
                 and round <= $asOfEndOfRound;
             """.as[Option[BigDecimal]].headOption,
          "getTotalCoinBalance",
        )
      } yield result.flatten.getOrElse(0)
    }

  override def getTotalRewardsCollectedEver()(implicit tc: TraceContext): Future[BigDecimal] =
    waitUntilAcsIngested {
      for {
        result <- storage.query(
          sql"""
                  select sum(reward_amount)
                  from scan_txlog_store
                  where store_id = $storeId
                    and index_record_type in (
                      ${TxLogIndexRecord.ValidatorRewardIndexRecord.dbType},
                      ${TxLogIndexRecord.AppRewardIndexRecord.dbType}
                    );
               """.as[BigDecimal].headOption,
          "getRewardsCollectedInRound",
        )
      } yield result.getOrElse(0)
    }

  override def listTransactions(
      pageEndEventId: Option[String],
      sortOrder: SortOrder,
      limit: Int,
  )(implicit
      tc: TraceContext
  ): Future[Seq[TxLogEntry.TransactionLogEntry]] =
    waitUntilAcsIngested {
      val dbType = TxLogIndexRecord.TransactionIndexRecord.dbType
      // Literal sort order since Postgres complains when trying to bind it to a parameter
      val (compareEntryNumber, orderLimit) = sortOrder match {
        case Ascending =>
          (sql" > ", sql""" order by entry_number asc limit $limit;""")
        case Descending =>
          (sql" < ", sql""" order by entry_number desc limit $limit;""")
      }

      for {
        rows <- storage.query(
          pageEndEventId.fold(
            (sql"""
                select event_id, domain_id
                from scan_txlog_store
                where store_id = $storeId
                  and index_record_type = ${dbType}""" ++ orderLimit).toActionBuilder
              .as[(String, String)]
          )(pageEndEventId =>
            (sql"""
                select event_id, domain_id
                from scan_txlog_store
                where store_id = $storeId
                  and index_record_type = ${dbType}
                   and entry_number """ ++ compareEntryNumber ++
              sql"""(
                select entry_number
                from scan_txlog_store
                where store_id = $storeId
                and event_id = ${lengthLimited(pageEndEventId)}
                and index_record_type = ${dbType}
            )""" ++ orderLimit).toActionBuilder
              .as[(String, String)]
          ),
          "listTransactions",
        )
        entries <- rows.traverse { case (eventId, domainId) =>
          loadTxLogEntry(txLogReader, eventId, DomainId.tryFromString(domainId), None, dbType)
        }: Future[Seq[TxLogEntry]]
        activityEntries = entries.collect { case e: TxLogEntry.TransactionLogEntry =>
          e
        }
      } yield activityEntries

    }

  override def getRewardsCollectedInRound(round: Long)(implicit
      tc: TraceContext
  ): Future[BigDecimal] = waitUntilAcsIngested {
    for {
      result <- storage.query(
        sql"""
              select sum(reward_amount)
              from scan_txlog_store
              where store_id = $storeId
                and index_record_type in (
                  ${TxLogIndexRecord.ValidatorRewardIndexRecord.dbType},
                  ${TxLogIndexRecord.AppRewardIndexRecord.dbType}
                )
                and round = $round;
           """.as[BigDecimal].headOption,
        "getRewardsCollectedInRound",
      )
    } yield result.getOrElse(0)
  }

  override def getCoinConfigForRound(round: Long)(implicit
      tc: TraceContext
  ): Future[TxLogEntry.OpenMiningRoundLogEntry] = waitUntilAcsIngested {
    for {
      row <- storage
        .querySingle(
          sql"""
                select event_id, domain_id, acs_contract_id
                from scan_txlog_store
                where store_id = $storeId
                  and index_record_type = ${TxLogIndexRecord.OpenMiningRoundIndexRecord.dbType}
                  and round = $round
                order by entry_number desc
                limit 1;
               """.as[(String, DomainId, Option[ContractId[Any]])].headOption,
          "getCoinConfigForRound",
        )
        .value
      entry <- row.traverse { case (eventId, domainId, acsContractId) =>
        loadTxLogEntry(
          txLogReader,
          eventId,
          domainId,
          acsContractId,
          TxLogIndexRecord.OpenMiningRoundIndexRecord.dbType,
        )
      }
      result <- entry match {
        case Some(omr: TxLogEntry.OpenMiningRoundLogEntry) =>
          Future.successful(omr)
        case Some(_) =>
          Future.failed(txLogIsOfWrongType())
        case None =>
          Future.failed(txLogNotFound())
      }
    } yield result
  }

  override def getRoundOfLatestData()(implicit tc: TraceContext): Future[(Long, Instant)] =
    waitUntilAcsIngested {
      for {
        latestClosedRound <- storage
          .querySingle(
            sql"""
               select closed.event_id, closed.acs_contract_id, closed.domain_id, closed.round
               from scan_txlog_store closed
               where closed.store_id = $storeId
                and closed.index_record_type = ${TxLogIndexRecord.ClosedMiningRoundIndexRecord.dbType}
               order by closed.round desc
               limit 1;
             """.as[(String, Option[ContractId[Any]], DomainId, Long)].headOption,
            "getRoundOfLatestData.latestClosedRound",
          )
          .value
        earliestOpenRound <- storage
          .querySingle(
            sql"""
               select min(open.round)
               from scan_txlog_store open
               where open.store_id = $storeId
                and open.index_record_type = ${TxLogIndexRecord.OpenMiningRoundIndexRecord.dbType};
             """.as[Long].headOption,
            "getRoundOfLatestData.earliestOpenRound",
          )
          .value
        entry <- (latestClosedRound, earliestOpenRound) match {
          case (
                Some((closedEventId, closedAcsContractId, closedDomainId, closedRound)),
                Some(openRound),
              ) if openRound <= closedRound =>
            loadTxLogEntry(
              txLogReader,
              closedEventId,
              closedDomainId,
              closedAcsContractId,
              TxLogIndexRecord.ClosedMiningRoundIndexRecord.dbType,
            )
          case _ =>
            Future.failed(txLogNotFound())
        }
        result <- entry.indexRecord match {
          case cmr: TxLogIndexRecord.ClosedMiningRoundIndexRecord =>
            Future.successful(cmr)
          case _ =>
            Future.failed(txLogIsOfWrongType())
        }
      } yield result.round -> result.effectiveAt
    }

  override def getTopProvidersByAppRewards(asOfEndOfRound: Long, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[(PartyId, BigDecimal)]] = waitUntilAcsIngested {
    for {
      rows <- storage.query(
        sql"""
              select rewarded_party, sum(reward_amount) as total_app_rewards
              from scan_txlog_store
              where store_id = $storeId
                and index_record_type = ${TxLogIndexRecord.AppRewardIndexRecord.dbType}
                and round <= $asOfEndOfRound
              group by rewarded_party
              order by total_app_rewards desc
              limit $limit;
           """.as[(PartyId, BigDecimal)],
        "getTopProvidersByAppRewards",
      )
    } yield rows
  }

  override def getTopValidatorsByValidatorRewards(asOfEndOfRound: Long, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[(PartyId, BigDecimal)]] = waitUntilAcsIngested {
    for {
      rows <- storage.query(
        sql"""
              select rewarded_party, sum(reward_amount) as total_app_rewards
              from scan_txlog_store
              where store_id = $storeId
                and index_record_type = ${TxLogIndexRecord.ValidatorRewardIndexRecord.dbType}
                and round <= $asOfEndOfRound
              group by rewarded_party
              order by total_app_rewards desc
              limit $limit;
           """.as[(PartyId, BigDecimal)],
        "getTopValidatorsByValidatorRewards",
      )
    } yield rows
  }

  override def getTopValidatorsByPurchasedTraffic(asOfEndOfRound: Long, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[HttpScanAppClient.ValidatorPurchasedTraffic]] = waitUntilAcsIngested {
    for {
      rows <- storage.query(
        sql"""
              select extra_traffic_validator                       as validator,
                     count(*)                                      as num_purchases,
                     sum(extra_traffic_purchase_traffic_purchased) as total_traffic_purchased,
                     sum(extra_traffic_purchase_cc_spent)          as total_cc_spent,
                     max(round)                                    as last_purchased_in_round
              from scan_txlog_store
              where store_id = $storeId
                and index_record_type = ${TxLogIndexRecord.ExtraTrafficPurchaseIndexRecord.dbType}
                and round <= $asOfEndOfRound
              group by extra_traffic_validator
              order by total_traffic_purchased desc
              limit $limit;
           """.as[(PartyId, Long, Long, BigDecimal, Long)],
        "getTopValidatorsByPurchasedTraffic",
      )
    } yield rows.map((HttpScanAppClient.ValidatorPurchasedTraffic.apply _).tupled)
  }

  override def listImportCrates(receiverParty: PartyId)(implicit
      tc: TraceContext
  ): Future[Seq[ContractWithState[ImportCrate.ContractId, ImportCrate]]] =
    waitUntilAcsIngested {
      for {
        rows <- storage
          .query(
            selectFromAcsTableWithState(
              ScanTables.acsTableName,
              storeId,
              where = sql"""template_id_qualified_name = ${QualifiedName(
                  ImportCrate.TEMPLATE_ID
                )} and acs.import_crate_receiver = $receiverParty""",
              orderLimit = sql"""limit ${sqlLimit(Limit.DefaultLimit)}""",
            ),
            "listImportCrates",
          )
        limited = applyLimit(Limit.DefaultLimit, rows)
        withState = limited.map(
          contractWithStateFromRow(ImportCrate.COMPANION)(_)
        )
      } yield withState
    }

  override def findFeaturedAppRight(
      providerPartyId: PartyId
  )(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[FeaturedAppRight.ContractId, FeaturedAppRight]]] =
    waitUntilAcsIngested {
      (for {
        row <- storage
          .querySingle(
            selectFromAcsTableWithState(
              ScanTables.acsTableName,
              storeId,
              where = sql"""
                  template_id_qualified_name = ${QualifiedName(FeaturedAppRight.TEMPLATE_ID)}
                    and featured_app_right_provider = $providerPartyId
                 """,
              orderLimit = sql"limit 1",
            ).headOption,
            "findFeaturedAppRight",
          )
      } yield contractWithStateFromRow(FeaturedAppRight.COMPANION)(row)).value
    }
}
