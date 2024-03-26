package com.daml.network.wallet.store.db

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.codegen.java.splice.amulet as amuletCodegen
import com.daml.network.codegen.java.splice.validatorlicense as validatorCodegen
import com.daml.network.codegen.java.splice.round.IssuingMiningRound
import com.daml.network.codegen.java.splice.types.Round
import com.daml.network.codegen.java.splice.wallet.subscriptions as subsCodegen
import com.daml.network.environment.RetryProvider
import com.daml.network.store.MultiDomainAcsStore.{ContractCompanion, QueryResult}
import com.daml.network.store.db.AcsQueries.SelectFromAcsTableResult
import com.daml.network.store.db.DbMultiDomainAcsStore.StoreDescriptor
import com.daml.network.store.db.{AcsQueries, AcsTables, DbCNNodeAppStore, TxLogQueries}
import com.daml.network.store.{Limit, LimitHelpers, PageLimit}
import com.daml.network.util.{Contract, ContractWithState, QualifiedName, TemplateJsonDecoder}
import com.daml.network.wallet.store
import com.daml.network.wallet.store.{
  BuyTrafficRequestTxLogEntry,
  TransferOfferTxLogEntry,
  TxLogEntry,
  UserWalletStore,
}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.toSQLActionBuilderChain
import com.digitalasset.canton.topology.ParticipantId
import slick.jdbc.canton.SQLActionBuilder

import scala.concurrent.*
import scala.jdk.OptionConverters.*

class DbUserWalletStore(
    override val key: UserWalletStore.Key,
    storage: DbStorage,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
    // TODO(#9731): get migration id from sponsor sv / scan instead of configuring here
    override val domainMigrationId: Long,
    participantId: ParticipantId,
)(implicit
    ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends DbCNNodeAppStore[TxLogEntry](
      storage = storage,
      acsTableName = WalletTables.acsTableName,
      txLogTableName = WalletTables.txLogTableName,
      // Any change in the store descriptor will lead to previously deployed applications
      // forgetting all persisted data once they upgrade to the new version.
      storeDescriptor = StoreDescriptor(
        version = 1,
        name = "DbUserWalletStore",
        party = key.endUserParty,
        participant = participantId,
        key = Map(
          // TODO (#6385): this shouldn't be required anymore, but keep in mind data continuity
          "endUserName" -> key.endUserName,
          "endUserParty" -> key.endUserParty.toProtoPrimitive,
          "validatorParty" -> key.validatorParty.toProtoPrimitive,
          "dsoParty" -> key.dsoParty.toProtoPrimitive,
        ),
      ),
      domainMigrationId,
      participantId,
      // Note: this causes the app to persist the original update stream for each wallet end user.
      // The data in this stream overlaps with (but is not a subset of) the data in the validator operator
      // update stream, which is used in the DbValidatorWalletStore.
      // An alternative would be to use one fused update stream for all local parties, but we don't have that.
      storeUpdateHistory = true,
    )
    with UserWalletStore
    with AcsTables
    with AcsQueries
    with TxLogQueries[TxLogEntry]
    with LimitHelpers {

  import multiDomainAcsStore.waitUntilAcsIngested

  def storeId: Int = multiDomainAcsStore.storeId

  override def toString: String = show"DbUserWalletStore(endUserParty=${key.endUserParty})"

  override protected def acsContractFilter = UserWalletStore.contractFilter(key, domainMigrationId)

  /** Returns the validator reward coupon sorted by their round in ascending order. Optionally limited by `maxNumInputs`
    * and optionally filtered by a set of issuing rounds.
    */
  override def listSortedValidatorRewards(
      activeIssuingRoundsO: Option[Set[Long]],
      limit: Limit = Limit.DefaultLimit,
  )(implicit tc: TraceContext): Future[Seq[
    Contract[amuletCodegen.ValidatorRewardCoupon.ContractId, amuletCodegen.ValidatorRewardCoupon]
  ]] = for {
    _ <- waitUntilAcsIngested()
    rewards <- multiDomainAcsStore.listContracts(
      amuletCodegen.ValidatorRewardCoupon.COMPANION
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
    (Contract[amuletCodegen.AppRewardCoupon.ContractId, amuletCodegen.AppRewardCoupon], BigDecimal)
  ]] = for {
    _ <- waitUntilAcsIngested()
    rewards <- multiDomainAcsStore.listContracts(
      amuletCodegen.AppRewardCoupon.COMPANION
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
              Contract.Has[amuletCodegen.AppRewardCoupon.ContractId, amuletCodegen.AppRewardCoupon],
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
  ]] = listSortedRewardCoupons(
    validatorCodegen.ValidatorFaucetCoupon.COMPANION,
    issuingRoundsMap,
    _.optIssuancePerValidatorFaucetCoupon.toScala.map(BigDecimal(_)),
    limit,
  )

  override def listSortedSvRewardCoupons(
      issuingRoundsMap: Map[Round, IssuingMiningRound],
      limit: Limit,
  )(implicit
      tc: TraceContext
  ): Future[
    Seq[
      (Contract[amuletCodegen.SvRewardCoupon.ContractId, amuletCodegen.SvRewardCoupon], BigDecimal)
    ]
  ] =
    listSortedRewardCoupons(
      amuletCodegen.SvRewardCoupon.COMPANION,
      issuingRoundsMap,
      r => Some(BigDecimal(r.issuancePerSvRewardCoupon)),
      limit,
      ccValue = sql"rti.issuance * acs.reward_coupon_weight",
    )

  private def listSortedRewardCoupons[C, TCid <: ContractId[_], T](
      companion: C,
      issuingRoundsMap: Map[Round, IssuingMiningRound],
      roundToIssuance: IssuingMiningRound => Option[BigDecimal],
      limit: Limit,
      ccValue: SQLActionBuilder = sql"rti.issuance",
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Seq[(Contract[TCid, T], BigDecimal)]] = {
    val templateId = companionClass.typeId(companion)
    issuingRoundsMap
      .flatMap { case (round, contract) =>
        roundToIssuance(contract).map(round.number.longValue() -> _)
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
                 select
                   #${SelectFromAcsTableResult.sqlColumnsCommaSeparated()},""" ++ ccValue ++ sql"""
                 from #${WalletTables.acsTableName} acs join round_to_issuance rti on acs.reward_coupon_round = rti.round
                 where acs.store_id = $storeId
                   and migration_id = $domainMigrationId
                   and acs.template_id_qualified_name = ${QualifiedName(templateId)}
                 order by (acs.reward_coupon_round, -""" ++ ccValue ++ sql""")
                 limit ${sqlLimit(limit)}""").toActionBuilder
              .as[(SelectFromAcsTableResult, BigDecimal)],
            s"listSorted${templateId.getEntityName}",
          )
        } yield applyLimit(s"listSorted${templateId.getEntityName}", limit, result).map {
          case (row, issuance) =>
            val contract = contractFromRow(companion)(row)
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
    // TODO (#11200): don't use the event id for pagination, use the entry number
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

  protected[this] override def listSubscriptionIdleStates(
      unlessExpiredAsOf: CantonTimestamp,
      limit: Limit,
  )(implicit tc: TraceContext): Future[Seq[ContractWithState[
    subsCodegen.SubscriptionIdleState.ContractId,
    subsCodegen.SubscriptionIdleState,
  ]]] = {
    val opName = "listSubscriptionIdleStates"
    for {
      _ <- waitUntilAcsIngested()
      idleStates <- storage.query(
        selectFromAcsTableWithState(
          WalletTables.acsTableName,
          storeId,
          domainMigrationId,
          where = sql"""template_id_qualified_name = ${QualifiedName(
              subsCodegen.SubscriptionIdleState.TEMPLATE_ID
            )}
              and contract_expires_at >= $unlessExpiredAsOf""",
          orderLimit = sql"""order by event_number
                             limit ${sqlLimit(limit)}""",
        ),
        opName,
      )
    } yield applyLimit(opName, limit, idleStates).map(
      contractWithStateFromRow(subsCodegen.SubscriptionIdleState.COMPANION)(_)
    )
  }
}
