package com.daml.network.wallet.store.memory

import com.daml.network.codegen.java.cc.types.Round
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cc.round.IssuingMiningRound
import com.daml.network.environment.RetryProvider
import com.daml.network.store.{InMemoryCNNodeAppStore, Limit, LimitHelpers, PageLimit}
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.util.Contract
import com.daml.network.wallet.store.{TxLogEntry, UserWalletStore}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*

import scala.concurrent.*

class InMemoryUserWalletStore(
    override val key: UserWalletStore.Key,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
)(implicit
    ec: ExecutionContext
) extends InMemoryCNNodeAppStore[TxLogEntry]
    with UserWalletStore
    with LimitHelpers {

  override def toString: String = show"InMemoryUserWalletStore(endUserParty=${key.endUserParty})"

  override protected def acsContractFilter = UserWalletStore.contractFilter(key)

  /** Returns the validator reward coupon sorted by their round in ascending order. Optionally limited by `maxNumInputs`
    * and optionally filtered by a set of issuing rounds.
    */
  override def listSortedValidatorRewards(
      activeIssuingRoundsO: Option[Set[Long]],
      limit: Limit = Limit.DefaultLimit,
  )(implicit tc: TraceContext): Future[Seq[
    Contract[coinCodegen.ValidatorRewardCoupon.ContractId, coinCodegen.ValidatorRewardCoupon]
  ]] = {
    def filterActiveRounds(round: Long) = activeIssuingRoundsO match {
      case Some(rounds) => rounds.contains(round)
      case None => true
    }
    for {
      rewards <- multiDomainAcsStore.listContracts(
        coinCodegen.ValidatorRewardCoupon.COMPANION
      )
    } yield applyLimit(
      "listSortedValidatorRewards",
      limit,
      rewards.view
        .filter(rw => filterActiveRounds(rw.payload.round.number))
        .map(_.contract)
        .toSeq
        .sortBy(_.payload.round.number),
    )
  }

  /** Returns the validator reward coupon sorted by their round in ascending order and their value in descending order.
    * Only up to `maxNumInputs` rewards are returned and all rewards are from the given `activeIssuingRounds`.
    */
  override def listSortedAppRewards(
      issuingRoundsMap: Map[Round, IssuingMiningRound],
      limit: Limit = Limit.DefaultLimit,
  )(implicit tc: TraceContext): Future[Seq[
    (Contract[coinCodegen.AppRewardCoupon.ContractId, coinCodegen.AppRewardCoupon], BigDecimal)
  ]] = for {
    rewards <- multiDomainAcsStore.listContracts(
      coinCodegen.AppRewardCoupon.COMPANION
    )
  } yield applyLimit(
    "listSortedAppRewards",
    limit,
    rewards
      .flatMap { rw =>
        val issuingO = issuingRoundsMap.get(rw.payload.round)
        issuingO
          .map { i =>
            val quantity = rw.payload.amount.multiply(
              if (rw.payload.featured)
                i.issuancePerFeaturedAppRewardCoupon
              else
                i.issuancePerUnfeaturedAppRewardCoupon
            )
            (rw.contract, BigDecimal(quantity))
          }
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
      beginAfterEventId: Option[String],
      limit: PageLimit,
  )(implicit
      lc: TraceContext
  ): Future[Seq[TxLogEntry.TransactionHistoryTxLogEntry]] =
    Future.successful {
      beginAfterEventId.fold(
        multiDomainAcsStore.filterTxLogEntriesByOffset[TxLogEntry.TransactionHistoryTxLogEntry](
          limit
        )
      )(
        multiDomainAcsStore.filterTxLogEntriesAfterEventId[TxLogEntry.TransactionHistoryTxLogEntry](
          _,
          limit,
        )(_.eventId)
      )
    }

  override def getLatestTransferOfferEventByTrackingId(
      trackingId: String
  )(implicit
      tc: TraceContext
  ): Future[QueryResult[Option[TxLogEntry.TransferOffer]]] = {
    for {
      (offset, entryOpt) <- multiDomainAcsStore.collectLatestTxLogEntryWithOffset {
        case to: TxLogEntry.TransferOffer if to.trackingId == trackingId => to
      }
    } yield entryOpt match {
      case Some(offer: TxLogEntry.TransferOffer) =>
        QueryResult(offset, Some(offer))
      case None =>
        QueryResult(offset, None)
      case _ => throw txLogIsOfWrongType()
    }
  }

  override def getLatestBuyTrafficRequestEventByTrackingId(
      trackingId: String
  )(implicit
      tc: TraceContext
  ): Future[QueryResult[Option[TxLogEntry.BuyTrafficRequest]]] = {
    for {
      (offset, entryOpt) <- multiDomainAcsStore.collectLatestTxLogEntryWithOffset {
        case btr: TxLogEntry.BuyTrafficRequest if btr.trackingId == trackingId => btr
      }
    } yield entryOpt match {
      case Some(request: TxLogEntry.BuyTrafficRequest) =>
        QueryResult(offset, Some(request))
      case None =>
        QueryResult(offset, None)
      case _ => throw txLogIsOfWrongType()
    }
  }
}
