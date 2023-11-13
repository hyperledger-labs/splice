package com.daml.network.wallet.store.db

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cc.round.IssuingMiningRound
import com.daml.network.codegen.java.cc.round.types.Round
import com.daml.network.environment.RetryProvider
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.store.TxLogStore.TransactionTreeSource
import com.daml.network.store.db.{AcsQueries, AcsTables, DbCNNodeAppStoreWithHistory}
import com.daml.network.store.{Limit, LimitHelpers, PageLimit}
import com.daml.network.util.{Contract, TemplateJsonDecoder}
import com.daml.network.wallet.store.UserWalletStore.TxLogIndexRecord
import com.daml.network.wallet.store.db.WalletTables.UserWalletTxLogStoreRowData
import com.daml.network.wallet.store.{UserWalletStore, UserWalletTxLogParser}
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.circe.Json
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import scala.concurrent.*

class DbUserWalletStore(
    override val key: UserWalletStore.Key,
    storage: DbStorage,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val transactionTreeSource: TransactionTreeSource,
    override protected val retryProvider: RetryProvider,
)(implicit
    ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends DbCNNodeAppStoreWithHistory[
      UserWalletStore.TxLogIndexRecord,
      UserWalletStore.TxLogEntry,
    ](
      storage = storage,
      acsTableName = WalletTables.acsTableName,
      txLogTableName = WalletTables.txLogTableName,
      // TODO (#5544): change this to something better
      storeDescriptor = Json.obj(
        "version" -> Json.fromInt(1),
        "store" -> Json.fromString("DbUserWalletStore"),
        "endUserParty" -> Json.fromString(key.endUserParty.toProtoPrimitive),
        "validatorParty" -> Json.fromString(key.validatorParty.toProtoPrimitive),
        "svcParty" -> Json.fromString(key.svcParty.toProtoPrimitive),
      ),
    )
    with UserWalletStore
    with AcsTables
    with AcsQueries
    with LimitHelpers {

  import multiDomainAcsStore.waitUntilAcsIngested

  def storeId: Int = multiDomainAcsStore.storeId

  override def ingestionTxLogInsert(record: TxLogIndexRecord)(implicit
      tc: TraceContext
  ) = UserWalletTxLogStoreRowData
    .fromIndexRecord(record)
    .map {
      case UserWalletTxLogStoreRowData(
            eventId,
            optOffset,
            domainId,
            acsContractId,
            txLogId,
            optTrackingId,
          ) =>
        val safeEventId = lengthLimited(eventId)
        val safeOffset = optOffset.map(lengthLimited)
        val safeTrackingId = optTrackingId.map(lengthLimited)
        sqlu"""
              insert into user_wallet_txlog_store(store_id, event_id, "offset", domain_id, acs_contract_id, tx_log_id, transfer_offer_tracking_id)
              values ($storeId, $safeEventId, $safeOffset, $domainId, $acsContractId, $txLogId, $safeTrackingId)
              on conflict do nothing
            """
    }

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

  override def listTransactions(
      beginAfterEventIdO: Option[String],
      limit: PageLimit,
  )(implicit
      lc: TraceContext
  ): Future[Seq[UserWalletTxLogParser.TransactionHistoryTxLogEntry]] = {
    waitUntilAcsIngested {
      for {
        events <- storage
          .query(
            beginAfterEventIdO.fold(
              sql"""
                    select event_id, domain_id, acs_contract_id
                    from #${WalletTables.txLogTableName}
                    where store_id = $storeId
                      and tx_log_id = ${UserWalletTxLogParser.TransactionHistoryTxLogIndexRecord.txLogId}
                    order by entry_number desc
                    limit ${sqlLimit(limit)}
                  """.as[(String, DomainId, Option[ContractId[Any]])]
            )(beginAfterEventId => sql"""
                    select event_id, domain_id, acs_contract_id
                    from #${WalletTables.txLogTableName}
                    where store_id = $storeId
                      and tx_log_id = ${UserWalletTxLogParser.TransactionHistoryTxLogIndexRecord.txLogId}
                      and entry_number < (
                          select entry_number
                          from #${WalletTables.txLogTableName}
                          where store_id = $storeId
                          and event_id = ${lengthLimited(beginAfterEventId)}
                      )
                    order by entry_number desc
                    limit ${sqlLimit(limit)}
                  """.as[(String, DomainId, Option[ContractId[Any]])]),
            "listTransactions",
          )
        entries <- Future.traverse(applyLimit(limit, events)) {
          case (eventId, domainId, acsContractId) =>
            txLogReader.loadTxLogEntry(eventId, domainId, acsContractId)
        }
      } yield entries.map {
        case entry: UserWalletTxLogParser.TransactionHistoryTxLogEntry => entry
        case _: UserWalletTxLogParser.TransferOfferTxLogEntry => throw txLogIsOfWrongType()
      }
    }
  }

  override def getLatestTransferOfferEventByTrackingId(trackingId: String)(implicit
      tc: TraceContext
  ): Future[QueryResult[Option[UserWalletTxLogParser.TxLogEntry.TransferOffer]]] =
    waitUntilAcsIngested {
      import cats.implicits.*
      for {
        resultWithOffset <- storage
          .querySingle(
            selectFromTxLogTableWithOffset(
              WalletTables.txLogTableName,
              storeId,
              sql"transfer_offer_tracking_id = ${lengthLimited(trackingId)}",
              sql"order by entry_number desc limit 1",
            )
              .as[TxLogStoreRowTemplateWithOffset]
              .headOption,
            "getLatestTransferOfferEventByTrackingId",
          )
          .getOrElse(throw offsetExpectedError())
        entry <- resultWithOffset.row.traverse(row =>
          txLogReader.loadTxLogEntry(row.eventId, row.domainId, row.acsContractId)
        )
        result <- entry match {
          case None =>
            Future.successful(
              QueryResult[Option[UserWalletTxLogParser.TxLogEntry.TransferOffer]](
                resultWithOffset.offset,
                None,
              )
            )
          case Some(entry: UserWalletTxLogParser.TxLogEntry.TransferOffer) =>
            Future.successful(
              QueryResult[Option[UserWalletTxLogParser.TxLogEntry.TransferOffer]](
                resultWithOffset.offset,
                Some(entry),
              )
            )
          case Some(_) =>
            Future.failed(txLogIsOfWrongType())
        }
      } yield result
    }
}
