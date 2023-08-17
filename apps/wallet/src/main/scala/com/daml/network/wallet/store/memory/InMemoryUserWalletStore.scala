package com.daml.network.wallet.store.memory

import com.daml.network.codegen.java.cc.api.v1.round.Round
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cc.round.IssuingMiningRound
import com.daml.network.environment.RetryProvider
import com.daml.network.store.InMemoryCNNodeAppStore
import com.daml.network.store.TxLogStore.TransactionTreeSource
import com.daml.network.util.Contract
import com.daml.network.wallet.store.UserWalletTxLogParser.TxLogEntry
import com.daml.network.wallet.store.{UserWalletStore, UserWalletTxLogParser}
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext
import cats.syntax.traverse.*
import com.digitalasset.canton.util.ShowUtil.*

import scala.concurrent.*

class InMemoryUserWalletStore(
    override val key: UserWalletStore.Key,
    override val defaultAcsDomain: DomainAlias,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val transactionTreeSource: TransactionTreeSource,
    override protected val retryProvider: RetryProvider,
)(implicit
    ec: ExecutionContext
) extends InMemoryCNNodeAppStore[
      UserWalletStore.TxLogIndexRecord,
      UserWalletStore.TxLogEntry,
    ]
    with UserWalletStore {

  override def toString: String = show"InMemoryUserWalletStore(endUserParty=${key.endUserParty})"

  override protected def acsContractFilter = UserWalletStore.contractFilter(key)

  /** Returns the validator reward coupon sorted by their round in ascending order. Optionally limited by `maxNumInputs`
    * and optionally filtered by a set of issuing rounds.
    */
  override def listSortedValidatorRewards(
      maxNumInputs: Option[Int],
      activeIssuingRoundsO: Option[Set[Long]],
  )(implicit tc: TraceContext): Future[Seq[
    Contract[coinCodegen.ValidatorRewardCoupon.ContractId, coinCodegen.ValidatorRewardCoupon]
  ]] = {
    def filterActiveRounds(round: Long) = activeIssuingRoundsO match {
      case Some(rounds) => rounds.contains(round)
      case None => true
    }
    for {
      domainId <- defaultAcsDomainIdF
      rewards <- multiDomainAcsStore.listContractsOnDomain(
        coinCodegen.ValidatorRewardCoupon.COMPANION,
        domainId,
      )
    } yield rewards
      .filter(rw => filterActiveRounds(rw.payload.round.number))
      .sortBy(_.payload.round.number)
      .take(maxNumInputs.getOrElse(Int.MaxValue))
  }

  /** Returns the validator reward coupon sorted by their round in ascending order and their value in descending order.
    * Only up to `maxNumInputs` rewards are returned and all rewards are from the given `activeIssuingRounds`.
    */
  override def listSortedAppRewards(
      maxNumInputs: Int,
      issuingRoundsMap: Map[Round, IssuingMiningRound],
  )(implicit tc: TraceContext): Future[Seq[
    (Contract[coinCodegen.AppRewardCoupon.ContractId, coinCodegen.AppRewardCoupon], BigDecimal)
  ]] = for {
    domainId <- defaultAcsDomainIdF
    rewards <- multiDomainAcsStore.listContractsOnDomain(
      coinCodegen.AppRewardCoupon.COMPANION,
      domainId,
    )
  } yield rewards
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
      beginAfterEventId: Option[String],
      limit: Int,
  )(implicit
      lc: TraceContext
  ): Future[Seq[UserWalletTxLogParser.TransactionHistoryTxLogEntry]] = {
    def filter(txi: UserWalletTxLogParser.WalletTxLogIndexRecord) = txi match {
      case _: UserWalletTxLogParser.TransactionHistoryTxLogIndexRecord =>
        true
      case _: UserWalletTxLogParser.TransferOfferStatusTxLogIndexRecord =>
        false
    }
    for {
      domain <- domains.waitForDomainConnection(defaultAcsDomain)
      indices = beginAfterEventId.fold(
        txLog.filterTxLogIndicesByOffset(0, limit)(filter)
      )(
        txLog.filterTxLogIndicesAfterEventId(domain, _, limit)(filter)
      )
      entries <- Future.traverse(indices)(i =>
        txLogReader.loadTxLogEntry(i.eventId, i.domainId, i.acsContractId)
      )
    } yield entries.map {
      case entry: UserWalletTxLogParser.TransactionHistoryTxLogEntry => entry
      case _: UserWalletTxLogParser.TransferOfferTxLogEntry => throw txLogIsOfWrongType()
    }
  }

  override def getLatestTransferOfferEventByTrackingId(
      trackingId: String
  )(implicit tc: TraceContext): Future[Option[TxLogEntry.TransferOffer]] = {
    for {
      indexOpt <- txLog.collectLatestTxLogIndex {
        case to: UserWalletTxLogParser.TransferOfferStatusTxLogIndexRecord
            if to.trackingId == trackingId =>
          to
      }
      entry <- indexOpt.traverse(index =>
        txLogReader.loadTxLogEntry(index.eventId, index.domainId, index.acsContractId)
      )
    } yield entry.map {
      case entry: UserWalletTxLogParser.TxLogEntry.TransferOffer => entry
      case _ => throw txLogIsOfWrongType()
    }
  }
}
