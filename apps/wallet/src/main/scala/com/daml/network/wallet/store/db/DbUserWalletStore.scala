package com.daml.network.wallet.store.db

import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.lf.data.Time.Timestamp
import com.daml.network.codegen.java.cc.api.v1.round.Round
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cc.round.IssuingMiningRound
import com.daml.network.environment.RetryProvider
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.store.TxLogStore.TransactionTreeSource
import com.daml.network.store.db.{AcsQueries, AcsTables, DbCNNodeAppStoreWithHistory}
import com.daml.network.util.{Contract, TemplateJsonDecoder}
import com.daml.network.wallet.store.UserWalletStore.TxLogIndexRecord
import com.daml.network.wallet.store.db.WalletTables.{
  UserWalletAcsStoreRowData,
  UserWalletTxLogStoreRowData,
}
import com.daml.network.wallet.store.{UserWalletStore, UserWalletTxLogParser}
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.circe.Json
import slick.dbio
import slick.dbio.DBIO
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import scala.concurrent.*

class DbUserWalletStore(
    override val key: UserWalletStore.Key,
    override val defaultAcsDomain: DomainAlias,
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
      acsTableName = DbUserWalletStore.acsTableName,
      txLogTableName = DbUserWalletStore.txLogTableName,
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
    with AcsQueries {

  import multiDomainAcsStore.waitUntilAcsIngested
  import storage.DbStorageConverters.setParameterByteArray

  def storeId: Int = multiDomainAcsStore.storeId

  override def ingestionAcsInsert(
      createdEvent: CreatedEvent
  )(implicit tc: TraceContext): Either[String, dbio.DBIO[_]] = {
    UserWalletAcsStoreRowData
      .fromCreatedEvent(createdEvent, acsContractFilter)
      .map {
        case UserWalletAcsStoreRowData(
              contract,
              contractExpiresAt,
            ) =>
          val contractId = contract.contractId.asInstanceOf[ContractId[Any]]
          val templateId = contract.identifier
          val createArguments = contract.toJson.payload
          val contractMetadataCreatedAt = Timestamp.assertFromInstant(contract.metadata.createdAt)
          val contractMetadataContractKeyHash =
            lengthLimited(contract.metadata.contractKeyHash.toStringUtf8)
          val contractMetadataDriverInternal = contract.metadata.driverMetadata.toByteArray
          sqlu"""
              insert into user_wallet_acs_store(store_id, contract_id, template_id, create_arguments, contract_metadata_created_at,
                                        contract_metadata_contract_key_hash, contract_metadata_driver_internal,
                                        contract_expires_at)
              values ($storeId, $contractId, $templateId, $createArguments, $contractMetadataCreatedAt,
                      $contractMetadataContractKeyHash, $contractMetadataDriverInternal,
                      $contractExpiresAt)
              on conflict do nothing
            """
      }
  }

  override def ingestionTxLogInsert(record: TxLogIndexRecord)(implicit
      tc: TraceContext
  ): Either[String, DBIO[_]] = UserWalletTxLogStoreRowData
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
      maxNumInputs: Option[Int],
      activeIssuingRoundsO: Option[Set[Long]],
  )(implicit tc: TraceContext): Future[Seq[
    Contract[coinCodegen.ValidatorRewardCoupon.ContractId, coinCodegen.ValidatorRewardCoupon]
  ]] = for {
    _ <- waitUntilAcsIngested()
    domainId <- defaultAcsDomainIdF
    rewards <- multiDomainAcsStore.listContractsOnDomain(
      coinCodegen.ValidatorRewardCoupon.COMPANION,
      domainId,
    )
  } yield rewards
    // TODO(#6119) Perform filter, sort, and limit in the database query
    .filter(rw =>
      activeIssuingRoundsO match {
        case Some(rounds) => rounds.contains(rw.payload.round.number)
        case None => true
      }
    )
    .sortBy(_.payload.round.number)
    // TODO(#6176): limits should not be optional
    .take(maxNumInputs.getOrElse(Int.MaxValue))

  /** Returns the validator reward coupon sorted by their round in ascending order and their value in descending order.
    * Only up to `maxNumInputs` rewards are returned and all rewards are from the given `issuingRoundsMap`.
    */
  override def listSortedAppRewards(
      maxNumInputs: Int,
      issuingRoundsMap: Map[Round, IssuingMiningRound],
  )(implicit tc: TraceContext): Future[Seq[
    (Contract[coinCodegen.AppRewardCoupon.ContractId, coinCodegen.AppRewardCoupon], BigDecimal)
  ]] = for {
    _ <- waitUntilAcsIngested()
    domainId <- defaultAcsDomainIdF
    rewards <- multiDomainAcsStore.listContractsOnDomain(
      coinCodegen.AppRewardCoupon.COMPANION,
      domainId,
    )
  } yield rewards
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
          (rw, BigDecimal(quantity))
        })
    }
    .sorted(
      Ordering[(Long, BigDecimal)].on(
        (x: (
            Contract[coinCodegen.AppRewardCoupon.ContractId, coinCodegen.AppRewardCoupon],
            BigDecimal,
        )) => (x._1.payload.round.number, -x._2)
      )
    )
    .take(maxNumInputs)

  override def listTransactions(
      beginAfterEventIdO: Option[String],
      limit: Int,
  )(implicit
      lc: TraceContext
  ): Future[Seq[UserWalletTxLogParser.TransactionHistoryTxLogEntry]] = {
    waitUntilAcsIngested {
      for {
        _ <- defaultAcsDomainIdF
        events <- storage
          .query(
            beginAfterEventIdO.fold(
              sql"""
                    select event_id, domain_id, acs_contract_id
                    from #${DbUserWalletStore.txLogTableName}
                    where store_id = $storeId
                      and tx_log_id = ${UserWalletTxLogParser.TransactionHistoryTxLogIndexRecord.txLogId}
                    order by entry_number desc
                    limit $limit
                  """.as[(String, DomainId, Option[ContractId[Any]])]
            )(beginAfterEventId => sql"""
                    select event_id, domain_id, acs_contract_id
                    from #${DbUserWalletStore.txLogTableName}
                    where store_id = $storeId
                      and tx_log_id = ${UserWalletTxLogParser.TransactionHistoryTxLogIndexRecord.txLogId}
                      and entry_number < (
                          select entry_number
                          from #${DbUserWalletStore.txLogTableName}
                          where store_id = $storeId
                          and event_id = ${lengthLimited(beginAfterEventId)}
                      )
                    order by entry_number desc
                    limit $limit
                  """.as[(String, DomainId, Option[ContractId[Any]])]),
            "listTransactions",
          )
        entries <- Future.traverse(events) { case (eventId, domainId, acsContractId) =>
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
              DbUserWalletStore.txLogTableName,
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

object DbUserWalletStore {
  val acsTableName: String = WalletTables.UserWalletAcsStore.baseTableRow.tableName
  val txLogTableName: String = WalletTables.UserWalletTxLogStore.baseTableRow.tableName
}
