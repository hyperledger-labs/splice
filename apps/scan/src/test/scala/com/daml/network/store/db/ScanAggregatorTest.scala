package com.daml.network.store.db

import scala.concurrent.Future
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import com.daml.network.environment.DarResources
import com.daml.network.scan.store.TxLogEntry
import com.daml.network.scan.store.db.ScanAggregator
import com.daml.network.scan.store.db.ScanAggregator.*
import com.daml.network.scan.store.db.DbScanStore
import com.daml.network.store.StoreTest
import com.daml.network.store.StoreErrors
import com.daml.network.store.TxLogStore
import com.daml.network.store.db.CNPostgresTest
import com.daml.network.util.ResourceTemplateDecoder
import com.daml.network.util.TemplateJsonDecoder

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.ledger.offset.Offset
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.config.CantonRequireTypes
import com.daml.network.environment.RetryProvider
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.metrics.MetricHandle
import com.digitalasset.canton.DomainAlias
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient

class ScanAggregatorTest
    extends StoreTest
    with HasExecutionContext
    with StoreErrors
    with CNPostgresTest {

  "ScanAggregator" should {
    "do nothing when there is no closed round" in {
      val (aggr, _) = mkAggregator(user1, svcParty).futureValue
      val lastClosedRound = aggr.getLastCompletelyClosedRoundAfter(None).futureValue
      lastClosedRound shouldBe None
      val previousRoundTotals = aggr.getLastAggregatedRoundTotals().futureValue
      previousRoundTotals shouldBe None
      val roundTotals = aggr.aggregate().futureValue
      roundTotals shouldBe None
    }

    "do nothing when last closed round <= last round aggregated" in {
      val (aggr, _) = mkAggregator(user1, svcParty).futureValue

      val lastClosedRound = 1L
      val previousRoundTotals = Some(RoundTotals(storeId = aggr.storeId, closedRound = 2L))

      val _ = aggr.appendRoundTotals(previousRoundTotals, lastClosedRound).futureValue
      val roundTotals = aggr.getLastAggregatedRoundTotals().futureValue
      roundTotals shouldBe None
    }

    "append round totals from round zero to last closed round (inclusive)" in {
      val (aggr, store) = mkAggregator(user1, svcParty).futureValue

      val roundsEffectiveAt = (0 to 10).map { i =>
        val round = i.toLong
        val closedRoundEffectiveAt = CantonTimestamp.now()
        appendAppReward(
          storage,
          aggr.storeId,
          s"app-event-$i",
          round,
          BigDecimal(i),
          "party1",
        ).futureValue
        appendValidatorReward(
          storage,
          aggr.storeId,
          s"val-event-$i",
          round,
          BigDecimal(i + 1),
          "party1",
        ).futureValue
        appendClosedRound(
          storage,
          aggr.storeId,
          s"closed-round-event-$i",
          round,
          closedRoundEffectiveAt,
        ).futureValue
        (round, closedRoundEffectiveAt)
      }.toMap

      val lastClosedRound = 2L
      val previousRoundTotals = aggr.getLastAggregatedRoundTotals().futureValue

      aggr.appendRoundTotals(previousRoundTotals, lastClosedRound).futureValue
      val roundTotals0 = aggr.getRoundTotals(0L).futureValue.value
      roundTotals0 shouldBe
        RoundTotals(
          storeId = aggr.storeId,
          closedRound = 0L,
          closedRoundEffectiveAt = roundsEffectiveAt(0L),
          appRewards = BigDecimal(0),
          validatorRewards = BigDecimal(1),
          cumulativeAppRewards = BigDecimal(0),
          cumulativeValidatorRewards = BigDecimal(1),
        )

      val roundTotals1 = aggr.getRoundTotals(1L).futureValue.value
      roundTotals1 shouldBe
        RoundTotals(
          storeId = aggr.storeId,
          closedRound = 1L,
          closedRoundEffectiveAt = roundsEffectiveAt(1L),
          appRewards = BigDecimal(1),
          validatorRewards = BigDecimal(2),
          cumulativeAppRewards = roundTotals0.appRewards + BigDecimal(1),
          cumulativeValidatorRewards = roundTotals0.validatorRewards + BigDecimal(2),
        )

      val prevTotals = aggr.getLastAggregatedRoundTotals().futureValue.value

      prevTotals shouldBe
        RoundTotals(
          storeId = aggr.storeId,
          closedRound = 2L,
          closedRoundEffectiveAt = roundsEffectiveAt(2L),
          appRewards = BigDecimal(2),
          validatorRewards = BigDecimal(3),
          cumulativeAppRewards = BigDecimal(3),
          cumulativeValidatorRewards = BigDecimal(6),
        )
      store
        .getRewardsCollectedInRound(2L)
        .futureValue shouldBe prevTotals.appRewards + prevTotals.validatorRewards
      val (round, effectiveAt) = store.getRoundOfLatestData().futureValue
      round shouldBe prevTotals.closedRound
      effectiveAt shouldBe prevTotals.closedRoundEffectiveAt.toInstant
    }

    "append round totals for coin balance" in {
      val (aggr, store) = mkAggregator(user1, svcParty).futureValue
      val lastRound = 10
      val holdingFee = 0.05
      val balanceChangeRoundZero = 10
      val roundsEffectiveAt = (0 to lastRound).map { i =>
        val effectiveAt = CantonTimestamp.now()
        val round = i.toLong
        appendBalanceChange(
          storage,
          aggr.storeId,
          s"app-event-$i",
          round,
          BigDecimal(balanceChangeRoundZero),
          BigDecimal(holdingFee),
        ).futureValue
        appendClosedRound(
          storage,
          aggr.storeId,
          s"closed-round-event-$i",
          round,
          effectiveAt,
        ).futureValue
        (round, effectiveAt)
      }.toMap

      val closedRound = 1L
      val lastClosedRound = closedRound
      val previousRoundTotals = aggr.getLastAggregatedRoundTotals().futureValue

      aggr.appendRoundTotals(previousRoundTotals, lastClosedRound).futureValue
      val prevTotals = aggr.getLastAggregatedRoundTotals().futureValue.value

      val expectedRound1CumulativeChangeToInitialAmountAsOfRoundZero =
        (1 + closedRound) * BigDecimal(balanceChangeRoundZero)
      val expectedRound1CumulativeChangeToHoldingFeesRate =
        (1 + closedRound) * BigDecimal(holdingFee)
      prevTotals shouldBe
        RoundTotals(
          storeId = aggr.storeId,
          closedRound = 1L,
          closedRoundEffectiveAt = roundsEffectiveAt(1L),
          changeToInitialAmountAsOfRoundZero = BigDecimal(10),
          changeToHoldingFeesRate = BigDecimal(holdingFee),
          cumulativeChangeToInitialAmountAsOfRoundZero =
            expectedRound1CumulativeChangeToInitialAmountAsOfRoundZero,
          cumulativeChangeToHoldingFeesRate = expectedRound1CumulativeChangeToHoldingFeesRate,
          totalCoinBalance =
            expectedRound1CumulativeChangeToInitialAmountAsOfRoundZero - expectedRound1CumulativeChangeToHoldingFeesRate * (1 + closedRound),
        )

      getTotalCoinBalanceFromTxLog(
        closedRound,
        store.storeId,
      ).futureValue shouldBe prevTotals.totalCoinBalance

      val _ = aggr.appendRoundTotals(Some(prevTotals), lastRound.toLong).futureValue
      val lastTotals = aggr.getLastAggregatedRoundTotals().futureValue.value
      val expectedRound10CumulativeChangeToInitialAmountAsOfRoundZero =
        BigDecimal((1 + lastRound) * balanceChangeRoundZero)
      val expectedRound10CumulativeChangeToHoldingFeesRate =
        (lastRound + 1) * BigDecimal(holdingFee)
      lastTotals shouldBe
        RoundTotals(
          storeId = aggr.storeId,
          closedRound = lastRound.toLong,
          closedRoundEffectiveAt = roundsEffectiveAt(lastRound.toLong),
          changeToInitialAmountAsOfRoundZero = BigDecimal(10),
          changeToHoldingFeesRate = BigDecimal(holdingFee),
          cumulativeChangeToInitialAmountAsOfRoundZero =
            expectedRound10CumulativeChangeToInitialAmountAsOfRoundZero,
          cumulativeChangeToHoldingFeesRate = expectedRound10CumulativeChangeToHoldingFeesRate,
          totalCoinBalance =
            expectedRound10CumulativeChangeToInitialAmountAsOfRoundZero - expectedRound10CumulativeChangeToHoldingFeesRate * (1 + lastRound),
        )

      getTotalCoinBalanceFromTxLog(
        lastRound.toLong,
        store.storeId,
      ).futureValue shouldBe lastTotals.totalCoinBalance

      val (round, effectiveAt) = store.getRoundOfLatestData().futureValue
      round shouldBe lastTotals.closedRound
      effectiveAt shouldBe lastTotals.closedRoundEffectiveAt.toInstant
      roundsEffectiveAt(round) shouldBe lastTotals.closedRoundEffectiveAt
    }

    "append round party totals from round zero to last closed round (inclusive)" in {
      val (aggr, store) = mkAggregator(user1, svcParty).futureValue
      val lastRound = 10L

      val expectedRoundPartyRewardTotals: Map[Long, List[RoundPartyTotals]] = (0 to lastRound.toInt)
        .map { i =>
          val round = i.toLong
          val effectiveAt = CantonTimestamp.now()

          val partyToAmount = (0 to 10).map { j =>
            val party = mkPartyId(s"party-$j").toProtoPrimitive
            val appAmount = BigDecimal(j)
            val validatorAmount = BigDecimal(j + 1)
            val trafficPurchasedCcSpent = BigDecimal(j + 2)
            val trafficPurchased = j.toLong + 100
            appendAppReward(
              storage,
              aggr.storeId,
              s"app-event-$i-$j",
              round,
              appAmount,
              party,
            ).futureValue

            appendValidatorReward(
              storage,
              aggr.storeId,
              s"val-event-$i-$j",
              round,
              validatorAmount,
              party,
            ).futureValue

            appendTrafficPurchase(
              storage,
              aggr.storeId,
              s"traffic-event-$i-$j",
              round,
              trafficPurchased,
              trafficPurchasedCcSpent,
              party,
            ).futureValue

            party -> (appAmount, validatorAmount, trafficPurchased, trafficPurchasedCcSpent)
          }.toMap

          appendClosedRound(
            storage,
            aggr.storeId,
            s"closed-round-event-$i",
            round,
            effectiveAt,
          ).futureValue

          partyToAmount.map {
            case (party, (appAmount, validatorAmount, trafficPurchased, trafficPurchasedCcSpent)) =>
              RoundPartyTotals(
                storeId = aggr.storeId,
                closedRound = round,
                party = party,
                appRewards = appAmount,
                validatorRewards = validatorAmount,
                trafficPurchased = trafficPurchased,
                trafficPurchasedCcSpent = trafficPurchasedCcSpent,
                trafficNumPurchases = 1,
              )
          }.toList
        }
        .flatten
        .groupBy(_.party)
        .map { case (party, totals) =>
          totals.foldLeft(List.empty[RoundPartyTotals]) { (acc, t) =>
            val prev =
              acc.lastOption.getOrElse(RoundPartyTotals(storeId = aggr.storeId, party = party))
            val rpt = RoundPartyTotals(
              storeId = aggr.storeId,
              closedRound = t.closedRound,
              party = t.party,
              appRewards = t.appRewards,
              validatorRewards = t.validatorRewards,
              trafficPurchased = t.trafficPurchased,
              trafficPurchasedCcSpent = t.trafficPurchasedCcSpent,
              trafficNumPurchases = t.trafficNumPurchases,
              cumulativeAppRewards = prev.cumulativeAppRewards + t.appRewards,
              cumulativeValidatorRewards = prev.cumulativeValidatorRewards + t.validatorRewards,
              cumulativeTrafficPurchased = prev.cumulativeTrafficPurchased + t.trafficPurchased,
              cumulativeTrafficPurchasedCcSpent =
                prev.cumulativeTrafficPurchasedCcSpent + t.trafficPurchasedCcSpent,
              cumulativeTrafficNumPurchases =
                prev.cumulativeTrafficNumPurchases + t.trafficNumPurchases,
            )
            acc :+ rpt
          }
        }
        .flatten
        .groupBy(_.closedRound)
        .map { case (k, v) => k -> v.toList.sortBy(_.party) }
        .toMap

      for (i <- 0 to lastRound.toInt) {
        val _ = aggr.appendRoundPartyTotals(i.toLong).futureValue
      }
      val limit = 10
      for (i <- 0 to lastRound.toInt) {
        val round = i.toLong
        val roundPartyTotals = aggr.getRoundPartyTotals(round).futureValue
        roundPartyTotals should contain theSameElementsAs expectedRoundPartyRewardTotals(round)
        val topProviders =
          getTopProvidersByAppRewardsFromTxLog(round, limit, aggr.storeId).futureValue
        topProviders should not be empty
        store.getTopProvidersByAppRewards(round, limit).futureValue shouldBe topProviders
        val topValidatorsByValidatorRewards =
          getTopValidatorsByValidatorRewardsFromTxLog(
            round,
            limit,
            aggr.storeId,
          ).futureValue
        store
          .getTopValidatorsByValidatorRewards(round, limit)
          .futureValue shouldBe topValidatorsByValidatorRewards
        val topValidatorsByPurchasedTraffic =
          getTopValidatorsByPurchasedTrafficFromTxLog(
            round,
            limit,
            aggr.storeId,
          ).futureValue
        store
          .getTopValidatorsByPurchasedTraffic(round, limit)
          .futureValue shouldBe topValidatorsByPurchasedTraffic
      }

      val topProviders =
        getTopProvidersByAppRewardsFromTxLog(lastRound, limit, aggr.storeId).futureValue
      store.getTopProvidersByAppRewards(lastRound, limit).futureValue shouldBe topProviders

      val topValidatorsByPurchasedTraffic =
        getTopValidatorsByPurchasedTrafficFromTxLog(
          lastRound,
          limit,
          aggr.storeId,
        ).futureValue
      store
        .getTopValidatorsByPurchasedTraffic(lastRound, limit)
        .futureValue shouldBe topValidatorsByPurchasedTraffic
    }
  }

  override protected def cleanDb(storage: DbStorage) =
    for {
      _ <- resetAllCnAppTables(storage)
    } yield ()

  private lazy val user1 = userParty(1)
  lazy val offset = Offset.fromByteArray(Array(1, 2, 3).map(_.toByte))
  lazy val domain = dummyDomain.toProtoPrimitive
  lazy val transactionTreeSource = TxLogStore.TransactionTreeSource.ForTesting()

  def mkAggregator(
      serviceUserPrimaryParty: PartyId,
      svcParty: PartyId,
  ): Future[(ScanAggregator, DbScanStore)] = {
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResources(
        DarResources.cantonCoin.all ++
          DarResources.cantonNameService.all ++
          DarResources.svcGovernance.all
      )
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)

    val store = new DbScanStore(
      serviceUserPrimaryParty = serviceUserPrimaryParty,
      svcParty = svcParty,
      storage,
      loggerFactory,
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, MetricHandle.NoOpMetricsFactory),
    )(parallelExecutionContext, implicitly, implicitly)
    for {
      _ <- store.multiDomainAcsStore.ingestionSink.initialize()
      _ <- store.multiDomainAcsStore.ingestionSink
        .ingestAcs(offset.toHexString, Seq.empty, Seq.empty, Seq.empty)
      _ <- store.domains.ingestionSink.ingestConnectedDomains(
        Map(DomainAlias.tryCreate(domain) -> dummyDomain)
      )
      aggr <- store.aggregator
    } yield (aggr, store)
  }
  def appendBalanceChange(
      storage: DbStorage,
      storeId: Int,
      eventId: String,
      round: Long,
      balanceChangeRoundZero: BigDecimal,
      balanceChangeHoldingFees: BigDecimal,
  ): Future[Int] = {
    val q = sql"""
      insert into scan_txlog_store 
        (
          store_id, 
          event_id, 
          domain_id,
          entry_type, 
          round,
          balance_change_change_to_initial_amount_as_of_round_zero,
          balance_change_change_to_holding_fees_rate,
          transaction_offset,
          entry_data
        )  
        select
          $storeId, 
          ${lengthLimited(eventId)},
          ${lengthLimited(domain)},
          ${TxLogEntry.BalanceChangeLogEntry.dbType},
          $round,
          $balanceChangeRoundZero,
          $balanceChangeHoldingFees,
          ${lengthLimited(s"offset-$eventId")},
          '{}'::jsonb
        where not exists (
          select 1 from scan_txlog_store
          where store_id = $storeId
            and event_id = ${lengthLimited(eventId)}
            and domain_id = ${lengthLimited(domain)}
            and entry_type = ${TxLogEntry.BalanceChangeLogEntry.dbType}
            and round = $round
        )
        on conflict do nothing
      """.asUpdate
    storage.update(q, "appendAppReward")
  }
  def appendClosedRound(
      storage: DbStorage,
      storeId: Int,
      eventId: String,
      round: Long,
      closedRoundEffectiveAt: CantonTimestamp,
  ): Future[Int] = {
    val q = sql"""
      insert into scan_txlog_store 
        (
          store_id, 
          event_id, 
          domain_id,
          entry_type, 
          round,
          closed_round_effective_at,
          transaction_offset,
          entry_data
        )  
      select 
          $storeId, 
          ${lengthLimited(eventId)},
          ${lengthLimited(domain)},
          ${TxLogEntry.ClosedMiningRoundLogEntry.dbType}, 
          $round,
          $closedRoundEffectiveAt,
          ${lengthLimited(s"offset-$eventId")},
          '{}'::jsonb
      where not exists (
        select 1 from scan_txlog_store
        where store_id = $storeId
          and event_id = ${lengthLimited(eventId)}
          and domain_id = ${lengthLimited(domain)}
          and entry_type = ${TxLogEntry.ClosedMiningRoundLogEntry.dbType}
          and round = $round
      )
      """.asUpdate
    storage.update(q, "appendAppReward")
  }

  def appendReward(
      storage: DbStorage,
      storeId: Int,
      eventId: String,
      round: Long,
      rewardAmount: BigDecimal,
      rewardedParty: String,
      dbType: CantonRequireTypes.String3,
  ): Future[Int] = {
    val q = sql"""
      insert into scan_txlog_store 
        (
          store_id, 
          event_id, 
          domain_id,
          entry_type, 
          round,
          reward_amount,
          rewarded_party,
          transaction_offset,
          entry_data
        )  
      select 
          $storeId, 
          ${lengthLimited(eventId)},
          ${lengthLimited(domain)},
          ${dbType}, 
          $round,
          $rewardAmount,
          ${lengthLimited(rewardedParty)},
          ${lengthLimited(s"offset-$eventId")},
          '{}'::jsonb
      where not exists (
        select 1 from scan_txlog_store
        where store_id = $storeId
          and event_id = ${lengthLimited(eventId)}
          and domain_id = ${lengthLimited(domain)}
          and entry_type = ${dbType}
          and round = $round
      )     
      """.asUpdate
    storage.update(q, "appendAppReward")
  }

  def appendAppReward(
      storage: DbStorage,
      storeId: Int,
      eventId: String,
      round: Long,
      rewardAmount: BigDecimal,
      rewardedParty: String,
  ): Future[Int] = appendReward(
    storage,
    storeId,
    eventId,
    round,
    rewardAmount,
    rewardedParty,
    TxLogEntry.AppRewardLogEntry.dbType,
  )

  def appendValidatorReward(
      storage: DbStorage,
      storeId: Int,
      eventId: String,
      round: Long,
      rewardAmount: BigDecimal,
      rewardedParty: String,
  ): Future[Int] = appendReward(
    storage,
    storeId,
    eventId,
    round,
    rewardAmount,
    rewardedParty,
    TxLogEntry.ValidatorRewardLogEntry.dbType,
  )

  def appendTrafficPurchase(
      storage: DbStorage,
      storeId: Int,
      eventId: String,
      round: Long,
      purchase: Long,
      spent: BigDecimal,
      party: String,
  ): Future[Int] = {
    val q = sql"""
      insert into scan_txlog_store 
        (
          store_id, 
          event_id, 
          domain_id,
          entry_type, 
          round,
          extra_traffic_validator,
          extra_traffic_purchase_traffic_purchased,
          extra_traffic_purchase_cc_spent,
          transaction_offset,
          entry_data
        )  
      select 
          $storeId, 
          ${lengthLimited(eventId)},
          ${lengthLimited(domain)},
          ${TxLogEntry.ExtraTrafficPurchaseLogEntry.dbType},
          $round,
          ${lengthLimited(party)},
          $purchase,
          $spent,
          ${lengthLimited(s"offset-$eventId")},
          '{}'::jsonb
      where not exists (
        select 1 from scan_txlog_store
        where store_id = $storeId
          and event_id = ${lengthLimited(eventId)}
          and domain_id = ${lengthLimited(domain)}
          and entry_type = ${TxLogEntry.ExtraTrafficPurchaseLogEntry.dbType}
          and round = $round
      )     
      """.asUpdate
    storage.update(q, "appendTrafficPurchase")
  }

  def lengthLimited(s: String): CantonRequireTypes.String2066 =
    CantonRequireTypes.String2066.tryCreate(s)

  def getTotalCoinBalanceFromTxLog(asOfEndOfRound: Long, storeId: Int): Future[BigDecimal] =
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
        "getTotalCoinBalanceFromTxLog",
      )
    } yield result.flatten.getOrElse(0)

  def getTopProvidersByAppRewardsFromTxLog(asOfEndOfRound: Long, limit: Int, storeId: Int) = {
    val q = sql"""
        select   rewarded_party, sum(reward_amount) as total_app_rewards
        from     scan_txlog_store
        where    store_id = $storeId
        and      entry_type = ${TxLogEntry.AppRewardLogEntry.dbType}
        and      round <= $asOfEndOfRound
        group by rewarded_party
        order by total_app_rewards desc
        limit $limit;
      """.as[(PartyId, BigDecimal)]
    storage.query(q, "getTopProvidersByAppRewardsFromTxLog")
  }

  def getTopValidatorsByValidatorRewardsFromTxLog(
      asOfEndOfRound: Long,
      limit: Int,
      storeId: Int,
  ) = {
    val q = sql"""
        select rewarded_party, sum(reward_amount) as total_validator_rewards
        from   scan_txlog_store
        where  store_id = $storeId
        and    entry_type = ${TxLogEntry.ValidatorRewardLogEntry.dbType}
        and    round <= $asOfEndOfRound
        group by rewarded_party
        order by total_validator_rewards desc
        limit $limit;
        """.as[(PartyId, BigDecimal)]
    storage.query(q, "getTopValidatorsByValidatorRewardsFromTxLog")
  }

  def getTopValidatorsByPurchasedTrafficFromTxLog(
      asOfEndOfRound: Long,
      limit: Int,
      storeId: Int,
  ): Future[Seq[HttpScanAppClient.ValidatorPurchasedTraffic]] = for {
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
      "getTopValidatorsByPurchasedTrafficFromTxLog",
    )
  } yield rows.map((HttpScanAppClient.ValidatorPurchasedTraffic.apply _).tupled)
}
