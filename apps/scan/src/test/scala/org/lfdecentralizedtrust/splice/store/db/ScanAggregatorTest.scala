package org.lfdecentralizedtrust.splice.store.db

import scala.concurrent.Future
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import org.lfdecentralizedtrust.splice.environment.{DarResources, RetryProvider}
import org.lfdecentralizedtrust.splice.history.Transfer
import org.lfdecentralizedtrust.splice.scan.store.db.{
  DbScanStore,
  DbScanStoreMetrics,
  ScanAggregatesReader,
  ScanAggregator,
}
import org.lfdecentralizedtrust.splice.scan.store.db.ScanAggregator.*
import org.lfdecentralizedtrust.splice.store.StoreTest
import org.lfdecentralizedtrust.splice.store.StoreErrors
import org.lfdecentralizedtrust.splice.store.db.SplicePostgresTest
import org.lfdecentralizedtrust.splice.util.ResourceTemplateDecoder
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.data.CantonTimestamp
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.DomainAlias
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient
import org.lfdecentralizedtrust.splice.scan.store.ScanStore
import org.lfdecentralizedtrust.splice.scan.store.TxLogEntry.EntryType

import scala.concurrent.ExecutionContext
import com.digitalasset.canton.util.MonadUtil
import com.digitalasset.canton.topology.ParticipantId
import org.lfdecentralizedtrust.splice.store.db.TxLogQueries.TxLogStoreId
import slick.jdbc.JdbcProfile

class ScanAggregatorTest
    extends StoreTest
    with HasExecutionContext
    with StoreErrors
    with SplicePostgresTest
    with AmuletTransferUtil
    with AcsJdbcTypes {

  override lazy val profile: JdbcProfile = storage.api.jdbcProfile

  val amuletPrice = 1.0

  def createReader(store: DbScanStore) = new ScanAggregatesReader() {
    def readRoundAggregateFromDso(
        round: Long
    )(implicit ec: ExecutionContext, traceContext: TraceContext): Future[Option[RoundAggregate]] = {
      val _ = traceContext
      val _ = store
      val _ = round
      Future.successful(None)
    }
    def close = ()
  }

  "ScanAggregator" should {
    "do nothing when there is no closed round" in {
      for {
        (aggr, store) <- mkAggregator(dsoParty)
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

    "get aggregates from DSO when no previous round totals exist and not sv1" in {
      val firstOpenMiningRound = 4L
      val dsoAggregatedRound = firstOpenMiningRound - 1
      val dsoRoundTotals = RoundTotals(
        closedRound = dsoAggregatedRound,
        closedRoundEffectiveAt = CantonTimestamp.now(),
      )
      val dsoRoundPartyTotalsParty1 =
        RoundPartyTotals(
          closedRound = dsoAggregatedRound,
          party = "party1",
        )
      val dsoRoundPartyTotalsParty2 =
        RoundPartyTotals(
          closedRound = dsoAggregatedRound,
          party = "party2",
        )

      def createScanAggregateReader(store: DbScanStore) = {
        val _ = store
        new ScanAggregatesReader() {
          def readRoundAggregateFromDso(
              round: Long
          )(implicit
              ec: ExecutionContext,
              traceContext: TraceContext,
          ): Future[Option[RoundAggregate]] = {
            val _ = traceContext
            round shouldBe dsoAggregatedRound
            Future.successful(
              Some(
                RoundAggregate(
                  dsoRoundTotals,
                  Vector(dsoRoundPartyTotalsParty1, dsoRoundPartyTotalsParty2),
                )
              )
            )
          }
          def close = ()
        }
      }
      for {
        (aggr, store) <- mkAggregator(
          dsoParty,
          ingestFromParticipantBegin = false,
          createScanAggregateReader,
        )
        _ <- addOpenRound(store, firstOpenMiningRound)
        last <- aggr.getLastAggregatedRoundTotals()
        firstOpen <- aggr.findFirstOpenMiningRound()
        rt <- aggr.ensureConsecutiveAggregation()
        lastAggregatedRoundTotals <- aggr.getLastAggregatedRoundTotals()
      } yield {
        val expectedRoundTotals = dsoRoundTotals
        last shouldBe None
        firstOpen.value shouldBe firstOpenMiningRound
        rt.value shouldBe expectedRoundTotals
        lastAggregatedRoundTotals.value shouldBe expectedRoundTotals
      }
    }

    "start from round zero when no previous round totals exist and sv1, not read from DSO" in {
      for {
        (aggr, store) <- mkAggregator(dsoParty, ingestFromParticipantBegin = true)
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

    "Not start from round zero when round zero closes, no first open mining round is found, not sv1 and no previous aggregates exist" in {
      val closedRound = closedMiningRound(dsoParty, round = 0L)
      for {
        (aggr, store) <- mkAggregator(dsoParty, ingestFromParticipantBegin = false)
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
      val (aggr, _) = mkAggregator(dsoParty).futureValue

      val lastClosedRound = 1L
      val previousRoundTotals = Some(RoundTotals(closedRound = 2L))

      val _ =
        storage
          .update_(
            aggr.aggregateRoundTotals(previousRoundTotals, lastClosedRound),
            "aggregate round totals",
          )
          .futureValue
      val roundTotals = aggr.getLastAggregatedRoundTotals().futureValue
      roundTotals shouldBe None
    }

    "append round totals from round zero to last closed round (inclusive)" in {
      val (aggr, store) =
        mkAggregator(dsoParty).futureValue
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
        val _ = storage
          .update_(
            aggr.aggregateRoundTotals(previousRoundTotals, lastClosedRound),
            "aggregate round totals",
          )
          .futureValue

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

    "append round totals for amulet balance" in {
      val (aggr, store) = mkAggregator(dsoParty).futureValue
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
        _ <- aggr
          .getLastCompletelyClosedRoundAfter(Some(0), 2)
          .map(
            _.value shouldBe lastRound withClue "aggregate counts rounds over multiple query pages"
          )
      } yield {
        val closedRound = 1L
        val lastClosedRound = closedRound
        val previousRoundTotals = aggr.getLastAggregatedRoundTotals().futureValue
        val _ = storage
          .update_(
            aggr.aggregateRoundTotals(previousRoundTotals, lastClosedRound),
            "aggregate round totals",
          )
          .futureValue
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
            totalAmuletBalance =
              expectedRound1CumulativeChangeToInitialAmountAsOfRoundZero - expectedRound1CumulativeChangeToHoldingFeesRate * (1 + closedRound),
          )

        getTotalAmuletBalanceFromTxLog(
          closedRound,
          store.txLogStoreId,
        ).futureValue shouldBe prevTotals.totalAmuletBalance

        val _ = storage
          .update_(
            aggr.aggregateRoundTotals(Some(prevTotals), lastRound.toLong),
            "aggregate round totals",
          )
          .futureValue
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
            totalAmuletBalance =
              expectedRound10CumulativeChangeToInitialAmountAsOfRoundZero - expectedRound10CumulativeChangeToHoldingFeesRate * (1 + lastRound),
          )

        getTotalAmuletBalanceFromTxLog(
          lastRound.toLong,
          store.txLogStoreId,
        ).futureValue shouldBe lastTotals.totalAmuletBalance

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
      val (aggr, store) = mkAggregator(dsoParty).futureValue
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
        val _ = MonadUtil
          .sequentialTraverse(0 to lastRound.toInt) { i =>
            storage
              .update(
                aggr
                  .aggregateRoundTotals(None, i.toLong)
                  .andThen(aggr.aggregateRoundPartyTotals(i.toLong)),
                "aggregate",
              )
          }
          .futureValue
        val limit = 10
        for (i <- 0 to lastRound.toInt) {
          val round = i.toLong
          val roundPartyTotals = aggr.getRoundPartyTotals(round).futureValue
          roundPartyTotals should contain theSameElementsAs expectedRoundPartyRewardTotals(round)
          val topProviders =
            getTopProvidersByAppRewardsFromTxLog(round, limit, aggr.txLogStoreId).futureValue
          topProviders should not be empty
          store.getTopProvidersByAppRewards(round, limit).futureValue shouldBe topProviders
          val topValidatorsByValidatorRewards =
            getTopValidatorsByValidatorRewardsFromTxLog(
              round,
              limit,
              aggr.txLogStoreId,
            ).futureValue
          store
            .getTopValidatorsByValidatorRewards(round, limit)
            .futureValue shouldBe topValidatorsByValidatorRewards
          val topValidatorsByPurchasedTraffic =
            getTopValidatorsByPurchasedTrafficFromTxLog(
              round,
              limit,
              aggr.txLogStoreId,
            ).futureValue
          store
            .getTopValidatorsByPurchasedTraffic(round, limit)
            .futureValue shouldBe topValidatorsByPurchasedTraffic
        }

        val topProviders =
          getTopProvidersByAppRewardsFromTxLog(lastRound, limit, aggr.txLogStoreId).futureValue
        store.getTopProvidersByAppRewards(lastRound, limit).futureValue shouldBe topProviders

        val topValidatorsByPurchasedTraffic =
          getTopValidatorsByPurchasedTrafficFromTxLog(
            lastRound,
            limit,
            aggr.txLogStoreId,
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

    "Fail to backfill aggregates if it cannot read aggregates before round" in {
      val (aggr, store) = mkAggregator(dsoParty).futureValue
      val now = CantonTimestamp.now()
      val lastRound = 10L
      (for {
        _ <- storage
          .update_(aggr.insertRoundTotals(RoundTotals(lastRound, now)), "insert round total")
        res <- store.backFillAggregates()
      } yield {
        res
      }).futureValue shouldBe None
    }

    "backfill aggregates before existing rounds total, up to round zero" in {
      val lastRound = 10L
      val now = CantonTimestamp.now()

      val previousRoundAggregates = mkRoundAggregates(0, lastRound - 1)

      def createDsoReader(store: DbScanStore) = {
        val _ = store
        new TestScanAggregatesReader(previousRoundAggregates)
      }

      val (aggr, store) =
        mkAggregator(
          dsoParty,
          ingestFromParticipantBegin = false,
          createDsoReader,
        ).futureValue

      val prevRoundTotals = RoundTotals(lastRound, now)
      for {
        _ <- storage
          .update_(aggr.insertRoundTotals(prevRoundTotals), "insert round total")
        res <- MonadUtil
          .sequentialTraverse(0 to lastRound.toInt - 1) { _ =>
            store.backFillAggregates()
          }
          .map(_.forall(_.exists(_ >= 0)))
        backFilledRoundTotals <- store.getRoundTotals(0L, lastRound - 1)
        backFilledRoundPartyTotals <- store.getRoundPartyTotals(0L, lastRound - 1)
      } yield {
        res shouldBe true
        backFilledRoundTotals should not be empty
        backFilledRoundPartyTotals should not be empty
        backFilledRoundTotals shouldBe previousRoundAggregates
          .map(
            _.roundTotals
          )
        backFilledRoundPartyTotals shouldBe previousRoundAggregates
          .flatMap(
            _.roundPartyTotals
          )
      }
    }

    "not backfill if no closed rounds can be found in round totals" in {
      val (_, store) =
        mkAggregator(
          dsoParty,
          ingestFromParticipantBegin = false,
          createReader,
        ).futureValue

      for {
        res <- store.backFillAggregates()
      } yield {
        res shouldBe None
      }
    }
  }

  private def mkRoundAggregates(firstRound: Long, lastRound: Long) = {
    val now = CantonTimestamp.now()

    (firstRound to lastRound).map { i =>
      val round = i.toLong
      RoundAggregate(
        RoundTotals(
          closedRound = round,
          closedRoundEffectiveAt = now,
          appRewards = BigDecimal(i + 1),
          validatorRewards = BigDecimal(i + 2),
          changeToInitialAmountAsOfRoundZero = BigDecimal(i + 3),
          changeToHoldingFeesRate = BigDecimal(i + 4),
          cumulativeAppRewards = BigDecimal(i + 5),
          cumulativeValidatorRewards = BigDecimal(i + 6),
          cumulativeChangeToInitialAmountAsOfRoundZero = BigDecimal(i + 7),
          cumulativeChangeToHoldingFeesRate = BigDecimal(i + 8),
          totalAmuletBalance = BigDecimal(i + 9),
        ),
        Vector(
          RoundPartyTotals(
            closedRound = round,
            party = s"party$round",
            appRewards = BigDecimal(i + 1),
            validatorRewards = BigDecimal(i + 2),
            trafficPurchased = round + 1,
            trafficPurchasedCcSpent = BigDecimal(i + 3),
            trafficNumPurchases = round + 2,
            cumulativeAppRewards = BigDecimal(i + 4),
            cumulativeValidatorRewards = BigDecimal(i + 5),
            cumulativeChangeToInitialAmountAsOfRoundZero = BigDecimal(i + 6),
            cumulativeChangeToHoldingFeesRate = BigDecimal(i + 7),
            cumulativeTrafficPurchased = round + 3,
            cumulativeTrafficPurchasedCcSpent = BigDecimal(i + 8),
            cumulativeTrafficNumPurchases = round + 4,
          )
        ),
      )
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

  override protected def cleanDb(storage: DbStorage)(implicit traceContext: TraceContext) =
    for {
      _ <- resetAllAppTables(storage)
    } yield ()

  def mkAggregator(
      dsoParty: PartyId,
      ingestFromParticipantBegin: Boolean = false,
      createReader: DbScanStore => ScanAggregatesReader = createReader,
  ): Future[(ScanAggregator, DbScanStore)] = {
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResources(
        DarResources.amulet.all ++
          DarResources.amuletNameService.all ++
          DarResources.dsoGovernance.all
      )
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)

    val store = new DbScanStore(
      key = ScanStore.Key(dsoParty),
      storage = storage,
      isFirstSv = ingestFromParticipantBegin,
      loggerFactory,
      RetryProvider(
        loggerFactory,
        timeouts,
        FutureSupervisor.Noop,
        NoOpMetricsFactory,
      ),
      createReader,
      DomainMigrationInfo(
        0,
        None,
      ),
      participantId = mkParticipantId("ScanAggregatorTest"),
      new DbScanStoreMetrics(new NoOpMetricsFactory()),
    )(parallelExecutionContext, implicitly, implicitly)
    for {
      _ <- store.multiDomainAcsStore.testIngestionSink.initialize()
      _ <- store.multiDomainAcsStore.testIngestionSink
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
    val amuletRulesContract = amuletRules()
    for {
      _ <- dummyDomain.exercise(
        amuletRulesContract,
        interfaceId = Some(splice.amuletrules.AmuletRules.TEMPLATE_ID_WITH_PACKAGE_ID),
        Transfer.choice.name,
        mkAmuletRulesTransfer(party, 0),
        mkTransferResultRecord(
          round = round,
          inputAppRewardAmount = 0,
          inputAmuletAmount = 0,
          inputValidatorRewardAmount = 0,
          inputSvRewardAmount = 0,
          balanceChanges = Map(
            party.toProtoPrimitive -> new splice.amuletrules.BalanceChange(
              balanceChangeRoundZero.bigDecimal,
              balanceChangeHoldingFees.bigDecimal,
            )
          ),
          amuletPrice = amuletPrice,
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
    val openRound = openMiningRound(dsoParty, round = round, amuletPrice = amuletPrice)
    dummyDomain.create(openRound)(store.multiDomainAcsStore).map(_ => ())
  }

  def addClosedRound(
      store: DbScanStore,
      round: Long,
  ): Future[Unit] = {
    val closedRound = closedMiningRound(dsoParty, round = round)
    dummyDomain.create(closedRound)(store.multiDomainAcsStore).map(_ => ())
  }

  def addAppReward(
      store: DbScanStore,
      round: Long,
      rewardAmount: Double,
      rewardedParty: PartyId,
  ): Future[Unit] = {
    val amuletRulesContract = amuletRules()
    for {
      _ <- dummyDomain.exercise(
        amuletRulesContract,
        interfaceId = Some(splice.amuletrules.AmuletRules.TEMPLATE_ID_WITH_PACKAGE_ID),
        Transfer.choice.name,
        mkAmuletRulesTransfer(rewardedParty, 0),
        mkTransferResultRecord(
          round = round,
          inputAppRewardAmount = rewardAmount,
          inputAmuletAmount = 0,
          inputValidatorRewardAmount = 0,
          inputSvRewardAmount = 0,
          balanceChanges = Map(),
          amuletPrice = amuletPrice,
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
    val amuletRulesContract = amuletRules()
    for {
      _ <- dummyDomain.exercise(
        amuletRulesContract,
        interfaceId = Some(splice.amuletrules.AmuletRules.TEMPLATE_ID_WITH_PACKAGE_ID),
        Transfer.choice.name,
        mkAmuletRulesTransfer(rewardedParty, 0),
        mkTransferResultRecord(
          round = round,
          inputAppRewardAmount = 0,
          inputAmuletAmount = 0,
          inputValidatorRewardAmount = rewardAmount,
          inputSvRewardAmount = 0,
          balanceChanges = Map(),
          amuletPrice = amuletPrice,
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
      .ingest(
        amuletRulesBuyMemberTrafficTransaction(party, member, round, purchase, spent.toDouble)
      )(
        store.multiDomainAcsStore
      )
      .map(_ => ())
  }

  def getTotalAmuletBalanceFromTxLog(
      asOfEndOfRound: Long,
      txLogStoreId: TxLogStoreId,
  ): Future[BigDecimal] =
    for {
      result <- storage.query(
        sql"""
               select sum(balance_change_change_to_initial_amount_as_of_round_zero) -
                     ($asOfEndOfRound + 1) * sum(balance_change_change_to_holding_fees_rate)
               from scan_txlog_store
               where store_id = $txLogStoreId
                 and entry_type = ${EntryType.BalanceChangeTxLogEntry}
                 and round <= $asOfEndOfRound;
             """.as[Option[BigDecimal]].headOption,
        "getTotalAmuletBalanceFromTxLog",
      )
    } yield result.flatten.getOrElse(0)

  def getTopProvidersByAppRewardsFromTxLog(
      asOfEndOfRound: Long,
      limit: Int,
      txLogStoreId: TxLogStoreId,
  ) = {
    val q = sql"""
        select   rewarded_party, sum(reward_amount) as total_app_rewards
        from     scan_txlog_store
        where    store_id = $txLogStoreId
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
      txLogStoreId: TxLogStoreId,
  ) = {
    val q = sql"""
        select rewarded_party, sum(reward_amount) as total_validator_rewards
        from   scan_txlog_store
        where  store_id = $txLogStoreId
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
      txLogStoreId: TxLogStoreId,
  ): Future[Seq[HttpScanAppClient.ValidatorPurchasedTraffic]] = for {
    rows <- storage.query(
      sql"""
              select extra_traffic_validator                       as validator,
                     count(*)                                      as num_purchases,
                     sum(extra_traffic_purchase_traffic_purchased) as total_traffic_purchased,
                     sum(extra_traffic_purchase_cc_spent)          as total_cc_spent,
                     max(round)                                    as last_purchased_in_round
              from scan_txlog_store
              where store_id = $txLogStoreId
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

class TestScanAggregatesReader(aggregates: Iterable[RoundAggregate]) extends ScanAggregatesReader {
  val roundAggregates = aggregates.map { a =>
    a.roundTotals.closedRound -> a
  }.toMap

  def readRoundAggregateFromDso(
      round: Long
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[Option[RoundAggregate]] = {
    val _ = traceContext
    val _ = round
    Future.successful(roundAggregates.get(round))
  }
  def close = ()
}
