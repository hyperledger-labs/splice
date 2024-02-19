package com.daml.network.store.db

import scala.concurrent.Future
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import com.daml.network.environment.DarResources
import com.daml.network.history.Transfer
import com.daml.network.scan.store.db.{ScanAggregator, ScanAggregatesReader, DbScanStore}
import com.daml.network.scan.store.db.ScanAggregator.*
import com.daml.network.store.StoreTest
import com.daml.network.store.StoreErrors
import com.daml.network.store.db.CNPostgresTest
import com.daml.network.util.ResourceTemplateDecoder
import com.daml.network.util.TemplateJsonDecoder
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.CantonRequireTypes
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.metrics.CantonLabeledMetricsFactory.NoOpMetricsFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.DomainAlias
import com.daml.network.codegen.java.cc
import com.daml.network.environment.RetryProvider
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient
import com.daml.network.scan.store.TxLogEntry.EntryType
import scala.concurrent.ExecutionContext
import com.digitalasset.canton.util.MonadUtil
import com.digitalasset.canton.topology.ParticipantId

class ScanAggregatorTest
    extends StoreTest
    with HasExecutionContext
    with StoreErrors
    with CNPostgresTest
    with CoinTransferUtil {

  val coinPrice = 1.0
  def createReader(store: DbScanStore) = new ScanAggregatesReader() {
    def readRoundAggregateFromSvc(
        round: Long
    )(implicit ec: ExecutionContext, traceContext: TraceContext): Future[Option[RoundAggregate]] = {
      val _ = traceContext
      val _ = store
      val _ = round
      Future.successful(None)
    }
  }

  "ScanAggregator" should {
    "do nothing when there is no closed round" in {
      for {
        (aggr, store) <- mkAggregator(user1, svcParty)
        _ <- addOpenRound(store, 0L)
        _ <- store.aggregate()
        lastClosedRound <- aggr.getLastCompletelyClosedRoundAfter(None)
        previousRoundTotals <- aggr.getLastAggregatedRoundTotals()
        roundTotals <- aggr.aggregate()
      } yield {
        lastClosedRound shouldBe None
        previousRoundTotals shouldBe None
        roundTotals shouldBe None
      }
    }

    "get aggregates from SVC when no previous round totals exist and not founder" in {
      val firstOpenMiningRound = 4L
      val svcAggregatedRound = firstOpenMiningRound - 1
      val svcRoundTotals = RoundTotals(
        closedRound = svcAggregatedRound,
        closedRoundEffectiveAt = CantonTimestamp.now(),
      )
      val svcRoundPartyTotalsParty1 =
        RoundPartyTotals(
          closedRound = svcAggregatedRound,
          party = "party1",
        )
      val svcRoundPartyTotalsParty2 =
        RoundPartyTotals(
          closedRound = svcAggregatedRound,
          party = "party2",
        )

      def createScanAggregateReader(store: DbScanStore) = {
        val _ = store
        new ScanAggregatesReader() {
          def readRoundAggregateFromSvc(
              round: Long
          )(implicit
              ec: ExecutionContext,
              traceContext: TraceContext,
          ): Future[Option[RoundAggregate]] = {
            val _ = traceContext
            round shouldBe svcAggregatedRound
            Future.successful(
              Some(
                RoundAggregate(
                  svcRoundTotals,
                  Vector(svcRoundPartyTotalsParty1, svcRoundPartyTotalsParty2),
                )
              )
            )
          }
        }
      }
      for {
        (aggr, store) <- mkAggregator(
          user1,
          svcParty,
          ingestFromParticipantBegin = false,
          createScanAggregateReader,
        )
        _ <- addOpenRound(store, firstOpenMiningRound)
        last <- aggr.getLastAggregatedRoundTotals()
        firstOpen <- aggr.findFirstOpenMiningRound()
        rt <- aggr.ensureConsecutiveAggregation()
        lastAggregatedRoundTotals <- aggr.getLastAggregatedRoundTotals()
      } yield {
        val expectedRoundTotals = svcRoundTotals
        last shouldBe None
        firstOpen.value shouldBe firstOpenMiningRound
        rt.value shouldBe expectedRoundTotals
        lastAggregatedRoundTotals.value shouldBe expectedRoundTotals
      }
    }

    "start from round zero when no previous round totals exist and founder, not read from SVC" in {
      for {
        (aggr, store) <- mkAggregator(user1, svcParty, ingestFromParticipantBegin = true)
        _ <- addOpenRound(store, 0L)
        last <- aggr.getLastAggregatedRoundTotals()
        firstOpen <- aggr.findFirstOpenMiningRound()
        afterEnsure <- aggr.ensureConsecutiveAggregation()
        totalsAfterEnsure <- aggr.getLastAggregatedRoundTotals()
      } yield {
        last shouldBe None
        firstOpen.value shouldBe 0L
        afterEnsure shouldBe None
        totalsAfterEnsure shouldBe None
      }
    }

    "Not start from round zero when round zero closes, no first open mining round is found, not founder and no previous aggregates exist" in {
      val closedRound = closedMiningRound(svcParty, round = 0L)
      for {
        (aggr, store) <- mkAggregator(user1, svcParty, ingestFromParticipantBegin = false)
        _ <- dummyDomain.create(closedRound)(store.multiDomainAcsStore)
        last <- aggr.getLastAggregatedRoundTotals()
        firstOpen <- aggr.findFirstOpenMiningRound()
        afterEnsure <- aggr.ensureConsecutiveAggregation()
        totalsAfterEnsure <- aggr.getLastAggregatedRoundTotals()
        lastClosed <- aggr.getLastCompletelyClosedRoundAfter(None)
      } yield {
        last shouldBe None
        firstOpen shouldBe None
        afterEnsure shouldBe None
        totalsAfterEnsure shouldBe None
        lastClosed shouldBe None
      }
    }

    "do nothing when last closed round <= last round aggregated" in {
      val (aggr, _) = mkAggregator(user1, svcParty).futureValue

      val lastClosedRound = 1L
      val previousRoundTotals = Some(RoundTotals(closedRound = 2L))

      val _ = aggr.appendRoundTotals(previousRoundTotals, lastClosedRound).futureValue
      val roundTotals = aggr.getLastAggregatedRoundTotals().futureValue
      roundTotals shouldBe None
    }

    "append round totals from round zero to last closed round (inclusive)" in {
      val (aggr, store) =
        mkAggregator(user1, svcParty).futureValue
      val range = (0 to 3)

      val party1 = mkPartyId("party1")
      for {
        _ <- MonadUtil
          .sequentialTraverse(range) { i =>
            addAppReward(
              store,
              i.toLong,
              i.toDouble,
              party1,
            )
          }
        _ <- MonadUtil
          .sequentialTraverse(range) { i =>
            addValidatorReward(
              store,
              i.toLong,
              (i + 1).toDouble,
              party1,
            )
          }
        _ <- MonadUtil
          .sequentialTraverse(range) { i =>
            addClosedRound(
              store,
              i.toLong,
            )
          }
      } yield {
        val lastClosedRound = 2L
        val previousRoundTotals = aggr.getLastAggregatedRoundTotals().futureValue
        aggr.appendRoundTotals(previousRoundTotals, lastClosedRound).futureValue
        val roundTotals0 = aggr.getRoundTotals(0L).futureValue.value
        roundTotals0.copy(closedRoundEffectiveAt = CantonTimestamp.MinValue) shouldBe
          RoundTotals(
            closedRound = 0L,
            closedRoundEffectiveAt = CantonTimestamp.MinValue,
            appRewards = BigDecimal(0),
            validatorRewards = BigDecimal(1),
            cumulativeAppRewards = BigDecimal(0),
            cumulativeValidatorRewards = BigDecimal(1),
          )

        val roundTotals1 = aggr.getRoundTotals(1L).futureValue.value
        roundTotals1.copy(closedRoundEffectiveAt = CantonTimestamp.MinValue) shouldBe
          RoundTotals(
            closedRound = 1L,
            closedRoundEffectiveAt = CantonTimestamp.MinValue,
            appRewards = BigDecimal(1),
            validatorRewards = BigDecimal(2),
            cumulativeAppRewards = roundTotals0.appRewards + BigDecimal(1),
            cumulativeValidatorRewards = roundTotals0.validatorRewards + BigDecimal(2),
          )

        val prevTotals = aggr.getLastAggregatedRoundTotals().futureValue.value

        prevTotals.copy(closedRoundEffectiveAt = CantonTimestamp.MinValue) shouldBe
          RoundTotals(
            closedRound = 2L,
            closedRoundEffectiveAt = CantonTimestamp.MinValue,
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
        store.getAggregatedRounds().futureValue.value shouldBe ScanAggregator.RoundRange(
          0L,
          prevTotals.closedRound,
        )
        store
          .getRoundTotals(prevTotals.closedRound, prevTotals.closedRound)
          .futureValue
          .loneElement shouldBe prevTotals
        store
          .getRoundTotals(0L, prevTotals.closedRound)
          .futureValue should contain theSameElementsAs Seq(roundTotals0, roundTotals1, prevTotals)
      }
    }

    "append round totals for coin balance" in {
      val (aggr, store) = mkAggregator(user1, svcParty).futureValue
      val lastRound = 10
      val holdingFee = 0.05
      val balanceChangeRoundZero = 10
      val party1 = mkPartyId("party1")

      for {
        _ <- MonadUtil.sequentialTraverse((0 to lastRound)) { i =>
          val round = i.toLong
          addBalanceChange(
            store,
            round,
            party1,
            BigDecimal(balanceChangeRoundZero),
            BigDecimal(holdingFee),
          )
        }
        _ <- MonadUtil.sequentialTraverse((0 to lastRound)) { i =>
          val round = i.toLong
          addClosedRound(
            store,
            round,
          )
        }
      } yield {
        val closedRound = 1L
        val lastClosedRound = closedRound
        val previousRoundTotals = aggr.getLastAggregatedRoundTotals().futureValue

        aggr.appendRoundTotals(previousRoundTotals, lastClosedRound).futureValue
        val prevTotals = aggr.getLastAggregatedRoundTotals().futureValue.value

        val expectedRound1CumulativeChangeToInitialAmountAsOfRoundZero =
          (1 + closedRound) * BigDecimal(balanceChangeRoundZero)
        val expectedRound1CumulativeChangeToHoldingFeesRate =
          (1 + closedRound) * BigDecimal(holdingFee)
        prevTotals.copy(closedRoundEffectiveAt = CantonTimestamp.MinValue) shouldBe
          RoundTotals(
            closedRound = 1L,
            closedRoundEffectiveAt = CantonTimestamp.MinValue,
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
        lastTotals.copy(closedRoundEffectiveAt = CantonTimestamp.MinValue) shouldBe
          RoundTotals(
            closedRound = lastRound.toLong,
            closedRoundEffectiveAt = CantonTimestamp.MinValue,
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

        store.getAggregatedRounds().futureValue.value shouldBe ScanAggregator.RoundRange(
          0L,
          lastTotals.closedRound,
        )
      }
    }

    "append round party totals from round zero to last closed round (inclusive)" in {
      val (aggr, store) = mkAggregator(user1, svcParty).futureValue
      val lastRound = 10L

      for {
        expectedRoundPartyRewardTotals <- MonadUtil
          .sequentialTraverse((0 to lastRound.toInt)) { i =>
            val round = i.toLong
            MonadUtil
              .sequentialTraverse((0 to 10)) { j =>
                val party = mkPartyId(s"party-$j")
                val member = mkParticipantId(s"party-$j")
                val appAmount = j.toDouble
                val validatorAmount = (j + 1).toDouble
                val trafficPurchasedCcSpent = BigDecimal(j + 2)
                val trafficPurchased = j.toLong + 100
                for {
                  _ <- addAppReward(
                    store,
                    round,
                    appAmount,
                    party,
                  )
                  _ <- addValidatorReward(
                    store,
                    round,
                    validatorAmount,
                    party,
                  )
                  _ <- addTrafficPurchase(
                    store,
                    round,
                    party,
                    member,
                    trafficPurchased,
                    trafficPurchasedCcSpent,
                  )
                  _ <- addClosedRound(
                    store,
                    round,
                  )
                } yield {
                  RoundPartyTotals(
                    closedRound = round,
                    party = party.toProtoPrimitive,
                    appRewards = appAmount,
                    validatorRewards = validatorAmount,
                    trafficPurchased = trafficPurchased,
                    trafficPurchasedCcSpent = trafficPurchasedCcSpent,
                    trafficNumPurchases = 1,
                  )
                }
              }
          }
          .map(_.flatten)
          .map(sumRoundPartyTotalsPerRound)
      } yield {
        for (i <- 0 to lastRound.toInt) {
          val _ = aggr.appendRoundTotals(None, i.toLong).futureValue
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
        store
          .getRoundPartyTotals(0L, lastRound)
          .futureValue should contain theSameElementsAs expectedRoundPartyRewardTotals.values.flatten
        store.getAggregatedRounds().futureValue.value shouldBe ScanAggregator.RoundRange(
          0L,
          lastRound,
        )
      }
    }
  }
  private def sumRoundPartyTotalsPerRound(
      rpts: Seq[RoundPartyTotals]
  ): Map[Long, List[RoundPartyTotals]] = {
    rpts
      .groupBy(_.party)
      .map { case (party, totals) =>
        totals.foldLeft(List.empty[RoundPartyTotals]) { (acc, t) =>
          val prev =
            acc.lastOption.getOrElse(RoundPartyTotals(party = party))
          val rpt = RoundPartyTotals(
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
  }

  override protected def cleanDb(storage: DbStorage) =
    for {
      _ <- resetAllCnAppTables(storage)
    } yield ()

  private lazy val user1 = userParty(1)

  def mkAggregator(
      serviceUserPrimaryParty: PartyId,
      svcParty: PartyId,
      ingestFromParticipantBegin: Boolean = false,
      createReader: DbScanStore => ScanAggregatesReader = createReader,
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
      storage = storage,
      ingestFromParticipantBegin = ingestFromParticipantBegin,
      loggerFactory,
      RetryProvider(
        loggerFactory,
        timeouts,
        FutureSupervisor.Noop,
        NoOpMetricsFactory,
      ),
      createReader,
      domainMigrationId = 0,
    )(parallelExecutionContext, implicitly, implicitly)
    for {
      _ <- store.multiDomainAcsStore.ingestionSink.initialize()
      _ <- store.multiDomainAcsStore.ingestionSink
        .ingestAcs(nextOffset(), Seq.empty, Seq.empty, Seq.empty)
      _ <- store.domains.ingestionSink.ingestConnectedDomains(
        Map(DomainAlias.tryCreate(domain) -> dummyDomain)
      )
      aggr <- store.aggregator
    } yield (aggr, store)
  }
  def addBalanceChange(
      store: DbScanStore,
      round: Long,
      party: PartyId,
      balanceChangeRoundZero: BigDecimal,
      balanceChangeHoldingFees: BigDecimal,
  ): Future[Unit] = {
    val coinRulesContract = coinRules()
    for {
      _ <- dummyDomain.exercise(
        coinRulesContract,
        interfaceId = Some(cc.coinrules.CoinRules.TEMPLATE_ID),
        Transfer.choice.name,
        mkCoinRulesTransfer(party, 0),
        mkTransferResult(
          round = round,
          inputAppRewardAmount = 0,
          inputCoinAmount = 0,
          inputValidatorRewardAmount = 0,
          balanceChanges = Map(
            party.toProtoPrimitive -> new cc.coinrules.BalanceChange(
              balanceChangeRoundZero.bigDecimal,
              balanceChangeHoldingFees.bigDecimal,
            )
          ),
          coinPrice = coinPrice,
        ),
        nextOffset(),
      )(
        store.multiDomainAcsStore
      )
    } yield ()
  }

  def addOpenRound(
      store: DbScanStore,
      round: Long,
  ): Future[Unit] = {
    val openRound = openMiningRound(svcParty, round = round, coinPrice = coinPrice)
    dummyDomain.create(openRound)(store.multiDomainAcsStore).map(_ => ())
  }

  def addClosedRound(
      store: DbScanStore,
      round: Long,
  ): Future[Unit] = {
    val closedRound = closedMiningRound(svcParty, round = round)
    dummyDomain.create(closedRound)(store.multiDomainAcsStore).map(_ => ())
  }

  def addAppReward(
      store: DbScanStore,
      round: Long,
      rewardAmount: Double,
      rewardedParty: PartyId,
  ): Future[Unit] = {
    val coinRulesContract = coinRules()
    for {
      _ <- dummyDomain.exercise(
        coinRulesContract,
        interfaceId = Some(cc.coinrules.CoinRules.TEMPLATE_ID),
        Transfer.choice.name,
        mkCoinRulesTransfer(rewardedParty, 0),
        mkTransferResult(
          round = round,
          inputAppRewardAmount = rewardAmount,
          inputCoinAmount = 0,
          inputValidatorRewardAmount = 0,
          balanceChanges = Map(),
          coinPrice = coinPrice,
        ),
        nextOffset(),
      )(
        store.multiDomainAcsStore
      )
    } yield ()
  }

  def addValidatorReward(
      store: DbScanStore,
      round: Long,
      rewardAmount: Double,
      rewardedParty: PartyId,
  ): Future[Unit] = {
    val coinRulesContract = coinRules()
    for {
      _ <- dummyDomain.exercise(
        coinRulesContract,
        interfaceId = Some(cc.coinrules.CoinRules.TEMPLATE_ID),
        Transfer.choice.name,
        mkCoinRulesTransfer(rewardedParty, 0),
        mkTransferResult(
          round = round,
          inputAppRewardAmount = 0,
          inputCoinAmount = 0,
          inputValidatorRewardAmount = rewardAmount,
          balanceChanges = Map(),
          coinPrice = coinPrice,
        ),
        nextOffset(),
      )(
        store.multiDomainAcsStore
      )
    } yield ()
  }

  def addTrafficPurchase(
      store: DbScanStore,
      round: Long,
      party: PartyId,
      member: ParticipantId,
      purchase: Long,
      spent: BigDecimal,
  ): Future[Unit] = {
    dummyDomain
      .ingest(coinRulesBuyMemberTrafficTransaction(party, member, round, purchase, spent.toDouble))(
        store.multiDomainAcsStore
      )
      .map(_ => ())
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
                 and entry_type = ${EntryType.BalanceChangeTxLogEntry}
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
        and      entry_type = ${EntryType.AppRewardTxLogEntry}
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
        and    entry_type = ${EntryType.ValidatorRewardTxLogEntry}
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
                and entry_type = ${EntryType.ExtraTrafficPurchaseTxLogEntry}
                and round <= $asOfEndOfRound
              group by extra_traffic_validator
              order by total_traffic_purchased desc
              limit $limit;
           """.as[(PartyId, Long, Long, BigDecimal, Long)],
      "getTopValidatorsByPurchasedTrafficFromTxLog",
    )
  } yield rows.map((HttpScanAppClient.ValidatorPurchasedTraffic.apply _).tupled)
}
