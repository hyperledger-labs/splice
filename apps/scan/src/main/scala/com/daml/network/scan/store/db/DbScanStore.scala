package com.daml.network.scan.store.db

import cats.implicits.*
import com.daml.network.codegen.java.cc.coin.FeaturedAppRight
import com.daml.network.codegen.java.cc.coinimport.ImportCrate
import com.daml.network.codegen.java.cc.coinrules.CoinRules
import com.daml.network.codegen.java.cn.cns.{CnsEntry, CnsRules}
import com.daml.network.codegen.java.cc.globaldomain.MemberTraffic
import com.daml.network.codegen.java.cc.validatorlicense.ValidatorLicense
import com.daml.network.codegen.java.cn.svcrules.SvcRules
import com.daml.network.environment.RetryProvider
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient
import com.daml.network.scan.store.SortOrder.{Ascending, Descending}
import com.daml.network.scan.store.TxLogEntry.EntryType
import com.daml.network.scan.store.db.ScanTables.txLogTableName
import com.daml.network.scan.store.{OpenMiningRoundTxLogEntry, ScanStore, SortOrder, TxLogEntry}
import com.daml.network.store.db.AcsQueries.SelectFromAcsTableResult
import com.daml.network.store.db.{AcsQueries, AcsTables, DbCNNodeAppStore, TxLogQueries}
import com.daml.network.store.{Limit, LimitHelpers, PageLimit}
import com.daml.network.util.{Contract, ContractWithState, QualifiedName, TemplateJsonDecoder}
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
import com.digitalasset.canton.data.CantonTimestamp

class DbScanStore(
    override val serviceUserPrimaryParty: PartyId,
    override val svcParty: PartyId,
    storage: DbStorage,
    ingestFromParticipantBegin: Boolean,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
    createScanAggregatesReader: DbScanStore => ScanAggregatesReader,
)(implicit
    override protected val ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends DbCNNodeAppStore[TxLogEntry](
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

  val aggregator: Future[ScanAggregator] =
    waitUntilAcsIngested().map(_ =>
      new ScanAggregator(
        storage,
        storeId,
        ingestFromParticipantBegin,
        createScanAggregatesReader(this),
        loggerFactory,
      )
    )

  def aggregate()(implicit
      tc: TraceContext
  ): Future[Option[ScanAggregator.RoundTotals]] = {
    for {
      a <- aggregator
      r <- a.aggregate()
    } yield r
  }

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
    } yield applyLimit("listEntries", limit, rows).map(
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
  ): Future[Seq[TxLogEntry.TransactionTxLogEntry]] =
    waitUntilAcsIngested {
      val entryTypeCondition = sql"""entry_type in (
                  ${EntryType.TransferTxLogEntry},
                  ${EntryType.TapTxLogEntry},
                  ${EntryType.MintTxLogEntry},
                  ${EntryType.SvRewardCollectedTxLogEntry}
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
        entries = rows.map(txLogEntryFromRow[TxLogEntry.TransactionTxLogEntry](txLogConfig))
      } yield entries

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
        limited = applyLimit("listImportCrates", limit, rows)
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

  override def getCoinConfigForRound(round: Long)(implicit
      tc: TraceContext
  ): Future[OpenMiningRoundTxLogEntry] = waitUntilAcsIngested {
    for {
      row <- storage
        .querySingle(
          selectFromTxLogTable(
            txLogTableName,
            storeId,
            where = sql"""
                   entry_type = ${EntryType.OpenMiningRoundTxLogEntry} and
                   round = $round
              """,
            orderLimit = sql"order by entry_number desc limit 1",
          ).headOption,
          "getCoinConfigForRound",
        )
        .value
      entry = row.map(txLogEntryFromRow[OpenMiningRoundTxLogEntry](txLogConfig))
      result <- entry match {
        case Some(omr: OpenMiningRoundTxLogEntry) =>
          Future.successful(omr)
        case None =>
          Future.failed(txLogNotFound())
      }
    } yield result
  }

  override def getRoundOfLatestData()(implicit tc: TraceContext): Future[(Long, Instant)] =
    waitUntilAcsIngested {
      for {
        row <- storage
          .querySingle(
            sql"""
            select   closed_round,
                     closed_round_effective_at
            from     round_totals
            where    store_id = $storeId
            order by closed_round desc
            limit    1;
            """.as[(Long, Long)].headOption,
            "getRoundOfLatestData",
          )
          .value
        result <- row match {
          case Some((closedRound, effectiveAt)) =>
            Future.successful(
              (closedRound, CantonTimestamp.assertFromLong(micros = effectiveAt).toInstant)
            )
          case None =>
            Future.failed(roundNotAggregated())
        }
      } yield result
    }

  override def getTotalCoinBalance(asOfEndOfRound: Long)(implicit
      tc: TraceContext
  ): Future[BigDecimal] =
    waitUntilAcsIngested {
      for {
        result <- ensureAggregated(asOfEndOfRound) {
          storage.query(
            sql"""
              select total_coin_balance
              from   round_totals
              where  store_id = $storeId
              and    closed_round = $asOfEndOfRound;
              """.as[Option[BigDecimal]].headOption,
            "getTotalCoinBalance",
          )
        }
      } yield result.flatten.getOrElse(0)
    }

  override def getTotalRewardsCollectedEver()(implicit tc: TraceContext): Future[BigDecimal] =
    waitUntilAcsIngested {
      for {
        result <- storage.query(
          sql"""
          select coalesce(cumulative_app_rewards, 0) + coalesce(cumulative_validator_rewards, 0)
          from   round_totals
          where  store_id = $storeId
          and    closed_round = (
                    select max(closed_round)
                    from round_totals
                    where store_id = $storeId
                 );
          """.as[BigDecimal].headOption,
          "getTotalRewardsCollectedEver",
        )
      } yield result.getOrElse(0)
    }

  override def getRewardsCollectedInRound(round: Long)(implicit
      tc: TraceContext
  ): Future[BigDecimal] = waitUntilAcsIngested {
    for {
      result <- ensureAggregated(round) {
        storage.query(
          sql"""
            select coalesce(app_rewards, 0) + coalesce(validator_rewards, 0)
            from   round_totals
            where  store_id = $storeId
            and    closed_round = $round;
            """.as[BigDecimal].headOption,
          "getRewardsCollectedInRound",
        )
      }
    } yield result.getOrElse(0)
  }

  override def getWalletBalance(partyId: PartyId, asOfEndOfRound: Long)(implicit
      tc: TraceContext
  ): Future[BigDecimal] = waitUntilAcsIngested {
    for {
      result <- ensureAggregated(asOfEndOfRound) {
        storage.query(
          // The round_party_totals might not have a row for the given round, (when the party has not been active in that round)
          // in that case just take the most recent round.
          // This is also why the total_coin_balance is calculated from the two cumulative fields.
          sql"""
             select   cumulative_change_to_initial_amount_as_of_round_zero - cumulative_change_to_holding_fees_rate * ($asOfEndOfRound + 1) as total_coin_balance
             from     round_party_totals
             where    store_id = $storeId
             and      closed_round <= $asOfEndOfRound
             and      party = $partyId
             order by closed_round desc
             limit    1;
           """.as[Option[BigDecimal]].headOption,
          "getWalletBalance",
        )
      }
    } yield result.flatten.getOrElse(0)
  }

  override def getTopProvidersByAppRewards(asOfEndOfRound: Long, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[(PartyId, BigDecimal)]] = waitUntilAcsIngested {
    for {
      rows <- ensureAggregated(asOfEndOfRound) {
        storage.query(
          sql"""
              with ranked_providers_by_app_rewards as (
                select   party as provider,
                         max(cumulative_app_rewards) as cumulative_app_rewards,
                         rank() over (order by max(cumulative_app_rewards) desc) as rank_nr
                from     round_party_totals
                where    store_id = $storeId
                and      closed_round <= $asOfEndOfRound
                and      cumulative_app_rewards > 0
                group by party
              )
              select   provider,
                       cumulative_app_rewards
              from     ranked_providers_by_app_rewards
              where    rank_nr <= $limit
              order by rank_nr;
            """.as[(PartyId, BigDecimal)],
          "getTopProvidersByAppRewards",
        )
      }
    } yield rows
  }

  override def getTopValidatorsByValidatorRewards(asOfEndOfRound: Long, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[(PartyId, BigDecimal)]] = waitUntilAcsIngested {
    for {
      rows <- ensureAggregated(asOfEndOfRound) {
        storage.query(
          sql"""
              with ranked_validators_by_validator_rewards as (
                select   party as validator,
                         max(cumulative_validator_rewards) as cumulative_validator_rewards,
                         rank() over (order by max(cumulative_validator_rewards) desc) as rank_nr
                from     round_party_totals
                where    store_id = $storeId
                and      closed_round <= $asOfEndOfRound
                and      cumulative_validator_rewards > 0
                group by party
              )
              select   validator,
                       cumulative_validator_rewards
              from     ranked_validators_by_validator_rewards
              where    rank_nr <= $limit
              order by rank_nr;
           """.as[(PartyId, BigDecimal)],
          "getTopValidatorsByValidatorRewards",
        )
      }
    } yield rows
  }

  override def getTopValidatorsByPurchasedTraffic(asOfEndOfRound: Long, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[HttpScanAppClient.ValidatorPurchasedTraffic]] = waitUntilAcsIngested {
    for {
      rows <- ensureAggregated(asOfEndOfRound) {
        // There might not be a row for a party where closed_round = asOfEndOfRound, so we need to use the
        // max cumulatives for each party up to including asOfEndOfRound
        // and separately get the last purchased round for each party in the leaderboard
        storage.query(
          sql"""
              with ranked_validators_by_purchased_traffic as (
                select   party as validator,
                         max(cumulative_traffic_num_purchases) as cumulative_traffic_num_purchases,
                         max(cumulative_traffic_purchased) as cumulative_traffic_purchased,
                         max(cumulative_traffic_purchased_cc_spent) as cumulative_traffic_purchased_cc_spent,
                         rank() over (order by max(cumulative_traffic_purchased) desc) as rank_nr
                from     round_party_totals
                where    store_id = $storeId
                and      closed_round <= $asOfEndOfRound
                and      cumulative_traffic_purchased > 0
                group by party
              ),
              last_purchases as (
                select   party as validator,
                         max(closed_round) as last_purchased_in_round
                from     round_party_totals
                where    store_id = $storeId
                and      closed_round <= $asOfEndOfRound
                and      traffic_purchased > 0
                group by party
              )
              select    rv.validator,
                        rv.cumulative_traffic_num_purchases,
                        rv.cumulative_traffic_purchased,
                        rv.cumulative_traffic_purchased_cc_spent,
                        coalesce(lp.last_purchased_in_round, 0)
              from      ranked_validators_by_purchased_traffic rv
              left join last_purchases lp
              on        rv.validator = lp.validator
              where     rv.rank_nr <= $limit
              order by  rv.rank_nr;
           """.as[(PartyId, Long, Long, BigDecimal, Long)],
          "getTopValidatorsByPurchasedTraffic",
        )
      }
    } yield rows.map((HttpScanAppClient.ValidatorPurchasedTraffic.apply _).tupled)
  }

  override def getTopValidatorLicenses(limit: Limit)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[ValidatorLicense.ContractId, ValidatorLicense]]] = waitUntilAcsIngested {
    for {
      rows <- storage
        .query(
          (selectFromAcsTable(ScanTables.acsTableName) ++
            sql"""
                where store_id = $storeId
                  and template_id_qualified_name = ${QualifiedName(ValidatorLicense.TEMPLATE_ID)}
                order by validator_license_rounds_collected desc limit ${sqlLimit(limit)}
              """).toActionBuilder.as[SelectFromAcsTableResult],
          "getTopValidatorLicenses",
        )
    } yield applyLimit("getTopValidatorLicenses", limit, rows).map(
      contractFromRow(ValidatorLicense.COMPANION)(_)
    )
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

  override def getAggregatedRounds()(implicit
      tc: TraceContext
  ): Future[Option[ScanAggregator.RoundRange]] =
    waitUntilAcsIngested {
      for {
        minMaxClosedRounds <- storage
          .querySingle(
            sql"""
            select min(closed_round) as min_round,
                   max(closed_round) as max_round
            from   round_totals
            where  store_id = $storeId;
          """.as[(Option[Long], Option[Long])].headOption,
            "getAggregatedRounds",
          )
          .value
      } yield {
        minMaxClosedRounds.flatMap {
          _ match {
            case (Some(start), Some(end)) => Some(ScanAggregator.RoundRange(start, end))
            case _ => None
          }
        }
      }
    }

  override def getRoundTotals(startRound: Long, endRound: Long)(implicit
      tc: TraceContext
  ): Future[Seq[ScanAggregator.RoundTotals]] = {
    val q = sql"""
    select   #${ScanAggregator.roundTotalsColumns}
    from     round_totals
    where    store_id = $storeId
    and      closed_round >= $startRound
    and      closed_round <= $endRound
    order by closed_round
    """
    waitUntilAcsIngested {
      for {
        roundTotals <- storage
          .query(
            q.as[ScanAggregator.RoundTotals],
            "getRoundTotals",
          )
      } yield roundTotals
    }
  }
  override def getRoundPartyTotals(startRound: Long, endRound: Long)(implicit
      tc: TraceContext
  ): Future[Seq[ScanAggregator.RoundPartyTotals]] = {
    val q = sql"""
    select   #${ScanAggregator.roundPartyTotalsColumns}
    from     round_party_totals
    where    store_id = $storeId
    and      closed_round >= $startRound
    and      closed_round <= $endRound
    order by closed_round, party
    """
    waitUntilAcsIngested {
      for {
        roundPartyTotals <- storage
          .query(
            q.as[ScanAggregator.RoundPartyTotals],
            "getRoundPartyTotals",
          )
      } yield roundPartyTotals
    }
  }
}
