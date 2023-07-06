package com.daml.network.scan.store.db

import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.lf.data.Time.Timestamp
import com.daml.network.codegen.java.cc.coin.{CoinRules, FeaturedAppRight}
import com.daml.network.codegen.java.cc.coinimport.ImportCrate
import com.daml.network.codegen.java.cc.globaldomain.ValidatorTraffic
import com.daml.network.codegen.java.cc.v1test.coin.CoinRulesV1Test
import com.daml.network.environment.RetryProvider
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient
import com.daml.network.scan.config.ScanAppBackendConfig
import com.daml.network.scan.store.db.ScanTables.ScanAcsStoreRowData
import com.daml.network.scan.store.{ScanStore, ScanTxLogParser}
import com.daml.network.store.{Limit, LimitHelpers, MultiDomainAcsStore}
import MultiDomainAcsStore.ContractWithState
import com.daml.network.store.TxLogStore.TransactionTreeSource
import com.daml.network.store.db.AcsTables.AcsStoreRowTemplate
import com.daml.network.store.db.{AcsQueries, AcsTables, DbCNNodeAppStoreWithHistory}
import com.daml.network.util.{Contract, TemplateJsonDecoder}
import com.digitalasset.canton.config.CantonRequireTypes.String3
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import io.circe.Json
import cats.implicits.*
import slick.dbio.DBIO

import java.time.Instant
import scala.annotation.unused
import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.toSQLActionBuilderChain

class DbScanStore(
    override val svcParty: PartyId,
    storage: DbStorage,
    override protected[this] val scanConfig: ScanAppBackendConfig,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val transactionTreeSource: TransactionTreeSource,
    override protected val retryProvider: RetryProvider,
)(implicit
    override protected val ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends DbCNNodeAppStoreWithHistory[ScanTxLogParser.TxLogIndexRecord, ScanTxLogParser.TxLogEntry](
      storage,
      DbScanStore.acsTableName,
      DbScanStore.txLogTableName,
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

  override def ingestionAcsInsert(createdEvent: CreatedEvent)(implicit
      tc: TraceContext
  ): Either[String, DBIO[_]] = {
    ScanAcsStoreRowData.fromCreatedEvent(createdEvent, scanConfig).map {
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
        val createArguments = payloadJsonFromContract(contract.payload)
        val contractMetadataCreatedAt = Timestamp.assertFromInstant(contract.metadata.createdAt)
        val contractMetadataContractKeyHash =
          lengthLimited(contract.metadata.contractKeyHash.toStringUtf8)
        val contractMetadataDriverInternal = contract.metadata.driverMetadata.toByteArray
        val safeImportCrateReceiverName = importCrateReceiverName.map(lengthLimited)
        sqlu"""
              insert into scan_acs_store(store_id, contract_id, template_id, create_arguments, contract_metadata_created_at,
              contract_metadata_contract_key_hash, contract_metadata_driver_internal, contract_expires_at,
              round, validator, amount, import_crate_receiver_name, featured_app_right_provider)
              values ($storeId, $contractId, $templateId, $createArguments, $contractMetadataCreatedAt,
                      $contractMetadataContractKeyHash, $contractMetadataDriverInternal, $contractExpiresAt,
                      $round, $validator, $amount, $safeImportCrateReceiverName, $featuredAppRightProvider)
              on conflict do nothing
              """
    }
  }

  // TODO (#6218): implement this
  override def ingestionTxLogInsert(record: ScanTxLogParser.TxLogIndexRecord)(implicit
      tc: TraceContext
  ): Either[String, DBIO[_]] = Right(DBIO.successful(())) // avoid blowing up until implemented

  // TODO (#5314): most queries do not properly handle multi-domain

  override def lookupCoinRules()(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[CoinRules.ContractId, CoinRules]]] =
    waitUntilAcsIngested {
      for {
        row <- storage
          .querySingle(
            (selectFromAcsTable(DbScanStore.acsTableName) ++
              sql"""
              where store_id = $storeId
                and template_id = ${CoinRules.TEMPLATE_ID}
              order by event_number desc
              limit 1;
             """).toActionBuilder.as[AcsStoreRowTemplate].headOption,
            "lookupCoinRules",
          )
          .value
        contractWithState <- row.traverse(
          multiDomainAcsStore.contractWithStateFromRow(CoinRules.COMPANION)(_)
        )
      } yield contractWithState
    }

  override def lookupCoinRulesV1Test()(implicit tc: TraceContext): Future[
    Option[ContractWithState[CoinRulesV1Test.ContractId, CoinRulesV1Test]]
  ] = waitUntilAcsIngested {
    for {
      row <- storage
        .querySingle(
          (selectFromAcsTable(DbScanStore.acsTableName) ++
            sql"""
                where store_id = $storeId
                  and template_id = ${CoinRulesV1Test.TEMPLATE_ID}
                order by event_number desc
                limit 1;
               """).toActionBuilder.as[AcsStoreRowTemplate].headOption,
          "lookupCoinRulesV1Test",
        )
        .value
      contractWithState <- row.traverse(
        multiDomainAcsStore.contractWithStateFromRow(CoinRulesV1Test.COMPANION)(_)
      )
    } yield contractWithState
  }

  override def lookupValidatorTraffic(validatorParty: PartyId)(implicit
      tc: TraceContext
  ): Future[Option[Contract[ValidatorTraffic.ContractId, ValidatorTraffic]]] =
    waitUntilAcsIngested {
      for {
        row <- storage
          .querySingle(
            (selectFromAcsTable(DbScanStore.acsTableName) ++
              sql"""
                  where store_id = $storeId
                    and template_id = ${ValidatorTraffic.TEMPLATE_ID}
                    and validator = $validatorParty
                  order by event_number desc
                  limit 1;
                 """).toActionBuilder.as[AcsStoreRowTemplate].headOption,
            "lookupValidatorTraffic",
          )
          .value
      } yield row.map(contractFromRow(ValidatorTraffic.COMPANION)(_))
    }

  override def getTotalCoinBalance(asOfEndOfRound: Long)(implicit
      tc: TraceContext
  ): Future[BigDecimal] =
    ??? // TODO (#6194): this requires scan_txlog_store

  override def getTotalRewardsCollectedEver()(implicit tc: TraceContext): Future[BigDecimal] =
    ??? // TODO (#6194): this requires scan_txlog_store

  override def getRewardsCollectedInRound(round: Long)(implicit
      tc: TraceContext
  ): Future[BigDecimal] = ??? // TODO (#6194): this requires scan_txlog_store

  override def getCoinConfigForRound(round: Long)(implicit
      tc: TraceContext
  ): Future[ScanTxLogParser.TxLogEntry.OpenMiningRoundLogEntry] =
    ??? // TODO (#6194): this requires scan_txlog_store

  override def getRoundOfLatestData()(implicit tc: TraceContext): Future[(Long, Instant)] =
    ??? // TODO (#6194): this requires scan_txlog_store

  override def getTopProvidersByAppRewards(asOfEndOfRound: Long, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[(PartyId, BigDecimal)]] = ??? // TODO (#6194): this requires scan_txlog_store

  override def getTopValidatorsByValidatorRewards(asOfEndOfRound: Long, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[(PartyId, BigDecimal)]] = ??? // TODO (#6194): this requires scan_txlog_store

  override def getTopValidatorsByPurchasedTraffic(asOfEndOfRound: Long, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[HttpScanAppClient.ValidatorPurchasedTraffic]] =
    ??? // TODO (#6194): this requires scan_txlog_store

  override def listImportCrates(receiver: String)(implicit
      tc: TraceContext
  ): Future[Seq[MultiDomainAcsStore.ContractWithState[ImportCrate.ContractId, ImportCrate]]] =
    waitUntilAcsIngested {
      for {
        rows <- storage
          .query(
            (selectFromAcsTable(DbScanStore.acsTableName) ++
              sql"""
                  where store_id = $storeId
                    and template_id = ${ImportCrate.TEMPLATE_ID}
                    and import_crate_receiver_name = ${lengthLimited(receiver)}
                  limit ${sqlLimit(Limit.DefaultLimit)};
                 """).toActionBuilder.as[AcsStoreRowTemplate],
            "listImportCrates",
          )
        limited = applyLimit(Limit.DefaultLimit, rows)
        withState <- limited.traverse(
          multiDomainAcsStore.contractWithStateFromRow(ImportCrate.COMPANION)(_)
        )
      } yield withState
    }

  override def findFeaturedAppRight(
      domainId: DomainId,
      providerPartyId: PartyId,
  )(implicit
      tc: TraceContext
  ): Future[Option[Contract[FeaturedAppRight.ContractId, FeaturedAppRight]]] =
    waitUntilAcsIngested {
      (for {
        row <- storage
          .querySingle(
            (selectFromAcsTable(DbScanStore.acsTableName) ++
              sql"""
                  where store_id = $storeId
                    and template_id = ${FeaturedAppRight.TEMPLATE_ID}
                    and featured_app_right_provider = $providerPartyId
                  limit 1;
                 """).toActionBuilder.as[AcsStoreRowTemplate].headOption,
            "findFeaturedAppRight",
          )
      } yield contractFromRow(FeaturedAppRight.COMPANION)(row)).value
    }
}

object DbScanStore {

  val acsTableName = "scan_acs_store"
  val txLogTableName = "scan_txlog_store"

  @unused
  def txLogIndexRecordDbType(record: ScanTxLogParser.TxLogIndexRecord): String3 = {
    val s = record match {
      case _: ScanTxLogParser.TxLogIndexRecord.ErrorIndexRecord => "err"
      case _: ScanTxLogParser.TxLogIndexRecord.OpenMiningRoundIndexRecord => "omr"
      case _: ScanTxLogParser.TxLogIndexRecord.ClosedMiningRoundIndexRecord => "cmr"
      case _: ScanTxLogParser.TxLogIndexRecord.AppRewardIndexRecord => "are"
      case _: ScanTxLogParser.TxLogIndexRecord.ValidatorRewardIndexRecord => "vre"
      case _: ScanTxLogParser.TxLogIndexRecord.ExtraTrafficPurchaseIndexRecord => "etp"
      case _: ScanTxLogParser.TxLogIndexRecord.BalanceChangeIndexRecord => "bc"
    }
    String3.tryCreate(s)
  }

}
