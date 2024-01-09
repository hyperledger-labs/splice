package com.daml.network.scan.store.db

import cats.implicits.*
import com.daml.network.codegen.java.cc.coin.FeaturedAppRight
import com.daml.network.codegen.java.cc.coinimport.ImportCrate
import com.daml.network.codegen.java.cc.coinrules.CoinRules
import com.daml.network.codegen.java.cn.cns.{CnsEntry, CnsRules}
import com.daml.network.codegen.java.cc.globaldomain.MemberTraffic
import com.daml.network.codegen.java.cn.svcrules.SvcRules
import com.daml.network.environment.RetryProvider
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient
import com.daml.network.scan.store.SortOrder.{Ascending, Descending}
import com.daml.network.scan.store.db.ScanTables.txLogTableName
import com.daml.network.scan.store.{ScanStore, SortOrder, TxLogEntry}
import com.daml.network.store.db.{
  AcsQueries,
  AcsTables,
  DbCNNodeAppStoreWithNewHistory,
  TxLogQueries,
}
import com.daml.network.store.{Limit, LimitHelpers, PageLimit}
import com.daml.network.util.{ContractWithState, QualifiedName, TemplateJsonDecoder}
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.toSQLActionBuilderChain
import com.digitalasset.canton.topology.{DomainId, Member, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import io.circe.Json
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

class DbScanStore(
    override val serviceUserPrimaryParty: PartyId,
    override val svcParty: PartyId,
    storage: DbStorage,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
)(implicit
    override protected val ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends DbCNNodeAppStoreWithNewHistory[TxLogEntry](
      storage,
      ScanTables.acsTableName,
      ScanTables.txLogTableName,
      // TODO (#5544): change this to something better
      storeDescriptor = Json.obj(
        "version" -> Json.fromInt(1),
        "service_user_primary_party" -> Json.fromString(serviceUserPrimaryParty.toProtoPrimitive),
        "svc_party" -> Json.fromString(svcParty.toProtoPrimitive),
      ),
    )
    with ScanStore
    with AcsTables
    with AcsQueries
    with TxLogQueries[TxLogEntry]
    with NamedLogging
    with LimitHelpers {

  import multiDomainAcsStore.waitUntilAcsIngested

  def storeId: Int = multiDomainAcsStore.storeId

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
                 and entry_type = ${TxLogEntry.BalanceChangeLogEntry.dbType}
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
                    and entry_type in (
                      ${TxLogEntry.ValidatorRewardLogEntry.dbType},
                      ${TxLogEntry.AppRewardLogEntry.dbType}
                    );
               """.as[BigDecimal].headOption,
          "getRewardsCollectedInRound",
        )
      } yield result.getOrElse(0)
    }

  override def listEntries(namePrefix: String, limit: Limit = Limit.DefaultLimit)(implicit
      tc: TraceContext
  ): Future[
    Seq[ContractWithState[CnsEntry.ContractId, CnsEntry]]
  ] = waitUntilAcsIngested {
    val limitedPrefix = lengthLimited(namePrefix)
    for {
      rows <- storage
        .query(
          selectFromAcsTableWithState(
            ScanTables.acsTableName,
            storeId,
            where = sql"""
                template_id_qualified_name = ${QualifiedName(
                CnsEntry.COMPANION.TEMPLATE_ID
              )} and cns_entry_name ^@ $limitedPrefix
            """,
            orderLimit = sql"""
                order by cns_entry_name
                limit ${sqlLimit(limit)}
            """,
          ),
          "listEntries",
        )
    } yield applyLimit(limit, rows).map(
      contractWithStateFromRow(CnsEntry.COMPANION)(_)
    )
  }

  override def lookupEntryByParty(
      partyId: PartyId
  )(implicit tc: TraceContext): Future[
    Option[ContractWithState[CnsEntry.ContractId, CnsEntry]]
  ] = waitUntilAcsIngested {
    (for {
      row <- storage
        .querySingle(
          selectFromAcsTableWithState(
            ScanTables.acsTableName,
            storeId,
            where = sql"""
                template_id_qualified_name = ${QualifiedName(
                CnsEntry.COMPANION.TEMPLATE_ID
              )}
                and cns_entry_owner = $partyId
                and cns_entry_name >= ''
            """,
            orderLimit = sql"""
                order by cns_entry_name
                limit 1
            """,
          ).headOption,
          "lookupEntryByParty",
        )
    } yield contractWithStateFromRow(CnsEntry.COMPANION)(row)).value
  }

  override def lookupEntryByName(name: String)(implicit tc: TraceContext): Future[
    Option[ContractWithState[CnsEntry.ContractId, CnsEntry]]
  ] = waitUntilAcsIngested {
    (for {
      row <- storage
        .querySingle(
          selectFromAcsTableWithState(
            ScanTables.acsTableName,
            storeId,
            where = sql"""
              template_id_qualified_name = ${QualifiedName(
                CnsEntry.COMPANION.TEMPLATE_ID
              )}
              and cns_entry_name = ${lengthLimited(name)}
                 """,
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupEntryByName",
        )
    } yield contractWithStateFromRow(CnsEntry.COMPANION)(row)).value
  }

  override def listTransactions(
      pageEndEventId: Option[String],
      sortOrder: SortOrder,
      limit: PageLimit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[TxLogEntry.TransactionLogEntry]] =
    waitUntilAcsIngested {
      val entryTypeCondition = sql"""entry_type in (
                  ${TxLogEntry.TransferLogEntry.dbType},
                  ${TxLogEntry.TapLogEntry.dbType},
                  ${TxLogEntry.MintLogEntry.dbType},
                  ${TxLogEntry.SvRewardCollectedLogEntry.dbType}
                )"""
      // Literal sort order since Postgres complains when trying to bind it to a parameter
      val (compareEntryNumber, orderLimit) = sortOrder match {
        case Ascending =>
          (sql" > ", sql""" order by entry_number asc limit ${sqlLimit(limit)};""")
        case Descending =>
          (sql" < ", sql""" order by entry_number desc limit ${sqlLimit(limit)};""")
      }

      for {
        rows <- storage.query(
          pageEndEventId.fold(
            selectFromTxLogTable(
              txLogTableName,
              storeId,
              where = entryTypeCondition,
              orderLimit = orderLimit,
            )
          )(pageEndEventId =>
            selectFromTxLogTable(
              txLogTableName,
              storeId,
              where = (entryTypeCondition ++ sql" and entry_number " ++ compareEntryNumber ++
                sql"""(
                  select entry_number
                  from scan_txlog_store
                  where store_id = $storeId
                  and event_id = ${lengthLimited(pageEndEventId)}
                  and """ ++ entryTypeCondition ++ sql"""
              )""").toActionBuilder,
              orderLimit = orderLimit,
            )
          ),
          "listTransactions",
        )
        entries = rows.map(txLogEntryFromRow[TxLogEntry.TransactionLogEntry](txLogConfig))
      } yield entries

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
                and entry_type in (
                  ${TxLogEntry.ValidatorRewardLogEntry.dbType},
                  ${TxLogEntry.AppRewardLogEntry.dbType}
                )
                and round = $round;
           """.as[BigDecimal].headOption,
        "getRewardsCollectedInRound",
      )
    } yield result.getOrElse(0)
  }

  override def getWalletBalance(partyId: PartyId, asOfEndOfRound: Long)(implicit
      tc: TraceContext
  ): Future[BigDecimal] = waitUntilAcsIngested {
    for {
      result <- storage.query(
        sql"""
             select sum((entry_data -> 'partyBalanceChanges' -> $partyId
                         ->> 'changeToInitialAmountAsOfRoundZero')::numeric)
                    - ($asOfEndOfRound + 1)
                      * sum((entry_data -> 'partyBalanceChanges' -> $partyId
                             ->> 'changeToHoldingFeesRate')::numeric)
             from scan_txlog_store
             where store_id = $storeId
               and entry_type = ${TxLogEntry.BalanceChangeLogEntry.dbType}
               and round <= $asOfEndOfRound
               and (entry_data -> 'partyBalanceChanges') ?? $partyId;
           """.as[Option[BigDecimal]].headOption,
        "getWalletBalance",
      )
    } yield result.flatten.getOrElse(0)
  }

  override def getCoinConfigForRound(round: Long)(implicit
      tc: TraceContext
  ): Future[TxLogEntry.OpenMiningRoundLogEntry] = waitUntilAcsIngested {
    for {
      row <- storage
        .querySingle(
          selectFromTxLogTable(
            txLogTableName,
            storeId,
            where = sql"""
                   entry_type = ${TxLogEntry.OpenMiningRoundLogEntry.dbType} and
                   round = $round
              """,
            orderLimit = sql"order by entry_number desc limit 1",
          ).headOption,
          "getCoinConfigForRound",
        )
        .value
      entry = row.map(txLogEntryFromRow[TxLogEntry.OpenMiningRoundLogEntry](txLogConfig))
      result <- entry match {
        case Some(omr: TxLogEntry.OpenMiningRoundLogEntry) =>
          Future.successful(omr)
        case None =>
          Future.failed(txLogNotFound())
      }
    } yield result
  }

  override def getRoundOfLatestData()(implicit tc: TraceContext): Future[(Long, Instant)] =
    waitUntilAcsIngested {
      for {
        latestClosedRoundRow <- storage
          .querySingle(
            selectFromTxLogTable(
              txLogTableName,
              storeId,
              where = sql"""entry_type = ${TxLogEntry.ClosedMiningRoundLogEntry.dbType}""",
              orderLimit = sql"order by round desc limit 1",
            ).headOption,
            "getRoundOfLatestData.latestClosedRound",
          )
          .value
        latestClosedRound = latestClosedRoundRow.map(
          txLogEntryFromRow[TxLogEntry.ClosedMiningRoundLogEntry](txLogConfig)
        )
        earliestOpenRound <- storage
          .querySingle(
            sql"""
               select min(open.round)
               from scan_txlog_store open
               where open.store_id = $storeId
                and open.entry_type = ${TxLogEntry.OpenMiningRoundLogEntry.dbType};
             """.as[Long].headOption,
            "getRoundOfLatestData.earliestOpenRound",
          )
          .value
        result <- (latestClosedRound, earliestOpenRound) match {
          case (
                Some(closedRound),
                Some(openRound),
              ) if openRound <= closedRound.round =>
            Future.successful(closedRound)
          case _ =>
            Future.failed(txLogNotFound())
        }
      } yield result.round -> result.effectiveAt
    }

  override def getTopProvidersByAppRewards(asOfEndOfRound: Long, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[(PartyId, BigDecimal)]] = waitUntilAcsIngested {
    for {
      _ <- verifyDataExistsForEndOfRound(asOfEndOfRound)
      rows <- storage.query(
        sql"""
              select rewarded_party, sum(reward_amount) as total_app_rewards
              from scan_txlog_store
              where store_id = $storeId
                and entry_type = ${TxLogEntry.AppRewardLogEntry.dbType}
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
      _ <- verifyDataExistsForEndOfRound(asOfEndOfRound)
      rows <- storage.query(
        sql"""
              select rewarded_party, sum(reward_amount) as total_app_rewards
              from scan_txlog_store
              where store_id = $storeId
                and entry_type = ${TxLogEntry.ValidatorRewardLogEntry.dbType}
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
                and entry_type = ${TxLogEntry.ExtraTrafficPurchaseLogEntry.dbType}
                and round <= $asOfEndOfRound
              group by extra_traffic_validator
              order by total_traffic_purchased desc
              limit $limit;
           """.as[(PartyId, Long, Long, BigDecimal, Long)],
        "getTopValidatorsByPurchasedTraffic",
      )
    } yield rows.map((HttpScanAppClient.ValidatorPurchasedTraffic.apply _).tupled)
  }

  override def getTotalPurchasedMemberTraffic(memberId: Member, domainId: DomainId)(implicit
      tc: TraceContext
  ): Future[Long] = waitUntilAcsIngested {
    for {
      sum <- storage
        .querySingle(
          sql"""
               select sum(total_traffic_purchased)
               from #${ScanTables.acsTableName}
               where store_id = $storeId
                and template_id_qualified_name = ${QualifiedName(MemberTraffic.TEMPLATE_ID)}
                and member_traffic_member = ${lengthLimited(memberId.toProtoPrimitive)}
             """.as[Long].headOption,
          "getTotalPurchasedMemberTraffic",
        )
        .value
    } yield sum.getOrElse(0L)
  }

  override def listImportCrates(receiverParty: PartyId, limit: Limit = Limit.DefaultLimit)(implicit
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
              orderLimit = sql"""order by event_number limit ${sqlLimit(limit)}""",
            ),
            "listImportCrates",
          )
        limited = applyLimit(limit, rows)
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
