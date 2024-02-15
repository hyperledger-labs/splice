package com.daml.network.wallet.store.db

import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cc.validatorlicense as validatorCodegen
import com.daml.network.codegen.java.cc.round.IssuingMiningRound
import com.daml.network.codegen.java.cc.types.Round
import com.daml.network.environment.RetryProvider
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.store.db.AcsQueries.SelectFromAcsTableResult
import com.daml.network.store.db.{AcsQueries, AcsTables, DbCNNodeAppStore, TxLogQueries}
import com.daml.network.store.{Limit, LimitHelpers, PageLimit}
import com.daml.network.util.{Contract, QualifiedName, TemplateJsonDecoder}
import com.daml.network.wallet.store
import com.daml.network.wallet.store.{
  BuyTrafficRequestTxLogEntry,
  TransferOfferTxLogEntry,
  TxLogEntry,
  UserWalletStore,
}
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.circe.Json
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.toSQLActionBuilderChain

import scala.concurrent.*
import scala.jdk.OptionConverters.*

class DbUserWalletStore(
    override val key: UserWalletStore.Key,
    storage: DbStorage,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
    // TODO(#9731): get migration id from sponsor sv / scan instead of configuring here
    domainMigrationId: Long,
)(implicit
    ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends DbCNNodeAppStore[TxLogEntry](
      storage = storage,
      acsTableName = WalletTables.acsTableName,
      txLogTableName = WalletTables.txLogTableName,
      // TODO (#5544): change this to something better
      storeDescriptor = Json.obj(
        "version" -> Json.fromInt(1),
        "store" -> Json.fromString("DbUserWalletStore"),
        "endUserName" -> Json.fromString(key.endUserName),
        "endUserParty" -> Json.fromString(key.endUserParty.toProtoPrimitive),
        "validatorParty" -> Json.fromString(key.validatorParty.toProtoPrimitive),
        "svcParty" -> Json.fromString(key.svcParty.toProtoPrimitive),
      ),
      domainMigrationId,
    )
    with UserWalletStore
    with AcsTables
    with AcsQueries
    with TxLogQueries[TxLogEntry]
    with LimitHelpers {

  import multiDomainAcsStore.waitUntilAcsIngested

  def storeId: Int = multiDomainAcsStore.storeId

  override def toString: String = show"DbUserWalletStore(endUserParty=${key.endUserParty})"

  override protected def acsContractFilter = UserWalletStore.contractFilter(key)

  /** Returns the validator reward coupon sorted by their round in ascending order. Optionally limited by `maxNumInputs`
    * and optionally filtered by a set of issuing rounds.
    */
  override def listSortedValidatorRewards(
      activeIssuingRoundsO: Option[Set[Long]],
      limit: Limit = Limit.DefaultLimit,
  )(implicit tc: TraceContext): Future[Seq[
    Contract[coinCodegen.ValidatorRewardCoupon.ContractId, coinCodegen.ValidatorRewardCoupon]
  ]] = for {
    _ <- waitUntilAcsIngested()
    rewards <- multiDomainAcsStore.listContracts(
      coinCodegen.ValidatorRewardCoupon.COMPANION
    )
  } yield applyLimit(
    "listSortedValidatorRewards",
    limit,
    // TODO(#6119) Perform filter, sort, and limit in the database query
    rewards.view
      .filter(rw =>
        activeIssuingRoundsO match {
          case Some(rounds) => rounds.contains(rw.payload.round.number)
          case None => true
        }
      )
      .map(_.contract)
      .toSeq
      .sortBy(_.payload.round.number),
  )

  /** Returns the validator reward coupon sorted by their round in ascending order and their value in descending order.
    * Only up to `maxNumInputs` rewards are returned and all rewards are from the given `issuingRoundsMap`.
    */
  override def listSortedAppRewards(
      issuingRoundsMap: Map[Round, IssuingMiningRound],
      limit: Limit = Limit.DefaultLimit,
  )(implicit tc: TraceContext): Future[Seq[
    (Contract[coinCodegen.AppRewardCoupon.ContractId, coinCodegen.AppRewardCoupon], BigDecimal)
  ]] = for {
    _ <- waitUntilAcsIngested()
    rewards <- multiDomainAcsStore.listContracts(
      coinCodegen.AppRewardCoupon.COMPANION
    )
  } yield applyLimit(
    "listSortedAppRewards",
    limit,
    rewards
      // TODO(#6119) Perform filter, sort, and limit in the database query
      .flatMap { rw =>
        val issuingO = issuingRoundsMap.get(rw.payload.round)
        issuingO
          .map(i => {
            val quantity =
              if (rw.payload.featured)
                rw.payload.amount.multiply(i.issuancePerFeaturedAppRewardCoupon)
              else
                rw.payload.amount.multiply(i.issuancePerUnfeaturedAppRewardCoupon)
            (rw.contract, BigDecimal(quantity))
          })
      }
      .sorted(
        Ordering[(Long, BigDecimal)].on(
          (x: (
              Contract.Has[coinCodegen.AppRewardCoupon.ContractId, coinCodegen.AppRewardCoupon],
              BigDecimal,
          )) => (x._1.payload.round.number, -x._2)
        )
      ),
  )

  def listSortedValidatorFaucets(
      issuingRoundsMap: Map[Round, IssuingMiningRound],
      limit: Limit = Limit.DefaultLimit,
  )(implicit tc: TraceContext): Future[Seq[
    (
        Contract[
          validatorCodegen.ValidatorFaucetCoupon.ContractId,
          validatorCodegen.ValidatorFaucetCoupon,
        ],
        BigDecimal,
    )
  ]] = {
    issuingRoundsMap
      .flatMap { case (round, contract) =>
        contract.optIssuancePerValidatorFaucetCoupon
          .map(round.number.longValue() -> BigDecimal(_))
          .toScala
      }
      .map { case (round, issuance) =>
        sql"($round, $issuance)"
      }
      .reduceOption { (acc, next) =>
        (acc ++ sql"," ++ next).toActionBuilder
      } match {
      case None => Future.successful(Seq.empty) // no rounds = no results
      case Some(roundToIssuance) =>
        for {
          result <- storage.query(
            (sql"""
                 with round_to_issuance(round, issuance) as (values """ ++ roundToIssuance ++ sql""")
                 select #${SelectFromAcsTableResult.sqlColumnsCommaSeparated()}, rti.issuance
                 from #${WalletTables.acsTableName} acs join round_to_issuance rti on acs.reward_coupon_round = rti.round
                 where acs.store_id = $storeId
                   and migration_id = $domainMigrationId
                   and acs.template_id_qualified_name = ${QualifiedName(
                validatorCodegen.ValidatorFaucetCoupon.TEMPLATE_ID
              )}
                 order by (acs.reward_coupon_round, -rti.issuance)
                 limit ${sqlLimit(limit)}""").toActionBuilder
              .as[(SelectFromAcsTableResult, BigDecimal)],
            "listSortedValidatorFaucets",
          )
        } yield applyLimit("listSortedValidatorFaucets", limit, result).map {
          case (row, issuance) =>
            val contract = contractFromRow(validatorCodegen.ValidatorFaucetCoupon.COMPANION)(row)
            contract -> issuance
        }
    }
  }

  override def listTransactions(
      beginAfterEventIdO: Option[String],
      limit: PageLimit,
  )(implicit
      lc: TraceContext
  ): Future[Seq[store.TxLogEntry.TransactionHistoryTxLogEntry]] = {
    waitUntilAcsIngested {
      for {
        rows <- storage
          .query(
            beginAfterEventIdO.fold(
              selectFromTxLogTable(
                WalletTables.txLogTableName,
                storeId,
                where = sql"tx_log_id = ${TxLogEntry.LogId.TransactionHistoryTxLog}",
                orderLimit = sql"order by entry_number desc limit ${sqlLimit(limit)}",
              )
            )(beginAfterEventId =>
              selectFromTxLogTable(
                WalletTables.txLogTableName,
                storeId,
                where = sql"""tx_log_id = ${TxLogEntry.LogId.TransactionHistoryTxLog}
                  and entry_number < (
                      select entry_number
                      from #${WalletTables.txLogTableName}
                      where store_id = $storeId
                      and migration_id = $domainMigrationId
                      and tx_log_id = ${TxLogEntry.LogId.TransactionHistoryTxLog}
                      and event_id = ${lengthLimited(beginAfterEventId)}
                  )""",
                orderLimit = sql"order by entry_number desc limit ${sqlLimit(limit)}",
              )
            ),
            "listTransactions",
          )
        entries = rows.map(txLogEntryFromRow[TxLogEntry.TransactionHistoryTxLogEntry](txLogConfig))
      } yield entries
    }
  }

  override def getLatestTransferOfferEventByTrackingId(trackingId: String)(implicit
      tc: TraceContext
  ): Future[QueryResult[Option[TransferOfferTxLogEntry]]] =
    waitUntilAcsIngested {
      import cats.implicits.*
      for {
        resultWithOffset <- storage
          .querySingle(
            selectFromTxLogTableWithOffset(
              WalletTables.txLogTableName,
              storeId,
              domainMigrationId,
              sql"entry_type = ${TxLogEntry.EntryType.TransferOfferTxLogEntry} and tracking_id = ${lengthLimited(trackingId)}",
              sql"order by entry_number desc limit 1",
            ).headOption,
            "getLatestTransferOfferEventByTrackingId",
          )
          .getOrElse(throw offsetExpectedError())
        entry = resultWithOffset.row.map(
          txLogEntryFromRow[TransferOfferTxLogEntry](txLogConfig)
        )
      } yield QueryResult[Option[TransferOfferTxLogEntry]](
        resultWithOffset.offset,
        entry,
      )
    }

  override def getLatestBuyTrafficRequestEventByTrackingId(trackingId: String)(implicit
      tc: TraceContext
  ): Future[QueryResult[Option[BuyTrafficRequestTxLogEntry]]] =
    waitUntilAcsIngested {
      import cats.implicits.*
      for {
        resultWithOffset <- storage
          .querySingle(
            selectFromTxLogTableWithOffset(
              WalletTables.txLogTableName,
              storeId,
              domainMigrationId,
              sql"entry_type = ${TxLogEntry.EntryType.BuyTrafficRequestTxLogEntry} and tracking_id = ${lengthLimited(trackingId)}",
              sql"order by entry_number desc limit 1",
            ).headOption,
            "getLatestBuyTrafficRequestEventByTrackingId",
          )
          .getOrElse(throw offsetExpectedError())
        entry = resultWithOffset.row.map(
          txLogEntryFromRow[BuyTrafficRequestTxLogEntry](txLogConfig)
        )
      } yield QueryResult[Option[BuyTrafficRequestTxLogEntry]](
        resultWithOffset.offset,
        entry,
      )
    }
}
