package com.daml.network.scan.store.db

import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.network.codegen.java.cc.coin.{CoinRules, FeaturedAppRight}
import com.daml.network.codegen.java.cc.coinimport.ImportCrate
import com.daml.network.codegen.java.cc.globaldomain.ValidatorTraffic
import com.daml.network.codegen.java.cc.v1test.coin.CoinRulesV1Test
import com.daml.network.environment.{CNLedgerConnection, RetryProvider}
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient
import com.daml.network.scan.config.ScanAppBackendConfig
import com.daml.network.scan.store.{ScanStore, ScanTxLogParser}
import com.daml.network.store.MultiDomainAcsStore
import com.daml.network.store.db.DbCNNodeAppStoreWithHistory
import com.daml.network.util.{Contract, TemplateJsonDecoder}
import com.digitalasset.canton.config.CantonRequireTypes.String3
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import io.circe.Json
import slick.dbio.DBIO

import java.time.Instant
import scala.annotation.unused
import scala.concurrent.{ExecutionContext, Future}

class DbScanStore(
    override val svcParty: PartyId,
    storage: DbStorage,
    override protected[this] val scanConfig: ScanAppBackendConfig,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val connection: CNLedgerConnection,
    override protected val retryProvider: RetryProvider,
)(implicit
    override protected val ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends DbCNNodeAppStoreWithHistory[ScanTxLogParser.TxLogIndexRecord, ScanTxLogParser.TxLogEntry](
      storage,
      DbScanStore.tableName,
      // TODO (#5544): change this to something better
      storeDescriptor = Json.obj(
        "version" -> Json.fromInt(1),
        "svc_party" -> Json.fromString(svcParty.toProtoPrimitive),
      ),
    )
    with ScanStore {
  override def ingestionInsert(createdEvent: CreatedEvent)(implicit
      tc: TraceContext
  ): Either[String, DBIO[_]] = ???

  override def lookupCoinRules()(implicit
      tc: TraceContext
  ): Future[Option[MultiDomainAcsStore.ReadyContract[CoinRules.ContractId, CoinRules]]] = ???

  override def lookupCoinRulesV1Test()(implicit tc: TraceContext): Future[
    Option[MultiDomainAcsStore.ReadyContract[CoinRulesV1Test.ContractId, CoinRulesV1Test]]
  ] = ???

  override def lookupValidatorTraffic(validatorParty: PartyId)(implicit
      tc: TraceContext
  ): Future[Option[Contract[ValidatorTraffic.ContractId, ValidatorTraffic]]] = ???

  override def listImportCrates(receiver: String)(implicit
      tc: TraceContext
  ): Future[Seq[MultiDomainAcsStore.ContractWithState[ImportCrate.ContractId, ImportCrate]]] = ???

  override def getTotalCoinBalance()(implicit tc: TraceContext): Future[(BigDecimal, BigDecimal)] =
    ???

  override def getTotalRewardsCollectedEver()(implicit tc: TraceContext): Future[BigDecimal] = ???

  override def getRewardsCollectedInRound(round: Long)(implicit
      tc: TraceContext
  ): Future[BigDecimal] = ???

  override def getCoinConfigForRound(round: Long)(implicit
      tc: TraceContext
  ): Future[ScanTxLogParser.TxLogEntry.OpenMiningRoundLogEntry] = ???

  override def getRoundOfLatestData()(implicit tc: TraceContext): Future[(Long, Instant)] = ???

  override def verifyDataExistsForEndOfRound(asOfEndOfRound: Long)(implicit
      tc: TraceContext
  ): Future[Unit] = ???

  override def getTopProvidersByAppRewards(asOfEndOfRound: Long, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[(PartyId, BigDecimal)]] = ???

  override def getTopValidatorsByValidatorRewards(asOfEndOfRound: Long, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[(PartyId, BigDecimal)]] = ???

  override def getTopValidatorsByPurchasedTraffic(asOfEndOfRound: Long, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[HttpScanAppClient.ValidatorPurchasedTraffic]] = ???

  override def getTotalPaidValidatorTraffic(validatorParty: PartyId)(implicit
      tc: TraceContext
  ): Future[Long] = ???

  override def findFeaturedAppRight(
      domainId: DomainId,
      providerPartyId: PartyId,
  ): Future[Option[Contract[FeaturedAppRight.ContractId, FeaturedAppRight]]] = ???
}

object DbScanStore {

  val tableName = "scan_acs_store"

  @unused
  def txLogIndexRecordDbType(record: ScanTxLogParser.TxLogIndexRecord): String3 = {
    val s = record match {
      case _: ScanTxLogParser.TxLogIndexRecord.ErrorIndexRecord => "err"
      case _: ScanTxLogParser.TxLogIndexRecord.OpenMiningRoundIndexRecord => "omr"
      case _: ScanTxLogParser.TxLogIndexRecord.ClosedMiningRoundIndexRecord => "cmr"
      case _: ScanTxLogParser.TxLogIndexRecord.AppRewardIndexRecord => "are"
      case _: ScanTxLogParser.TxLogIndexRecord.ValidatorRewardIndexRecord => "vre"
      case _: ScanTxLogParser.TxLogIndexRecord.ExtraTrafficPurchaseIndexRecord => "etp"
    }
    String3.tryCreate(s)
  }

}
