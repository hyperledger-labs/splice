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
import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient
import org.lfdecentralizedtrust.splice.scan.store.ScanStore
import org.lfdecentralizedtrust.splice.scan.store.TxLogEntry.EntryType

import scala.concurrent.ExecutionContext
import com.digitalasset.canton.util.MonadUtil
import com.digitalasset.canton.topology.ParticipantId
import org.lfdecentralizedtrust.splice.config.IngestionConfig
import org.lfdecentralizedtrust.splice.store.db.TxLogQueries.TxLogStoreId
import org.scalatest.Assertion
import slick.jdbc.{GetResult, JdbcProfile}

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
          .futureValueUS
      val roundTotals = aggr.getLastAggregatedRoundTotals().futureValue
      roundTotals shouldBe None
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
          .futureValueUS
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
          .futureValueUS
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

    "Continue to aggregate round_party_totals on an empty active_parties table" in {
      cleanup()
      val (aggr, store) = mkAggregator(dsoParty).futureValue
      val lastRound = 2L
      val restartRound = 1L
      val nrParties = 2L
      for {
        expectedRoundPartyRewardTotals <- generateRoundPartyTotals(store, lastRound, nrParties)
          .map(sumRoundPartyTotalsPerRound)
      } yield {
        def countActiveParties() = storage
          .query(sql"""select count(1) from active_parties""".as[Int].head, "count active parties")
          .futureValueUS
        clue("aggregate up to restartRound") {
          // aggregate round party totals until restartRound
          aggregateRounds(aggr, 0, restartRound)
          assertActiveParties(store, nrParties, restartRound)
          assertRoundPartyTotalsWithLeaderBoards(
            expectedRoundPartyRewardTotals.filter { case (round, _) => round <= restartRound },
            aggr,
            store,
            restartRound,
            lastRound.toInt,
          )
        }
        // simulate migration, no active_parties exist
        storage.update(sqlu"delete from active_parties", "delete active_parties").futureValueUS;
        countActiveParties() shouldBe 0

        clue("Continue aggregation to lastRound with empty active_parties table") {
          aggregateRounds(aggr, restartRound, lastRound)
          assertRoundPartyTotalsWithLeaderBoards(
            expectedRoundPartyRewardTotals,
            aggr,
            store,
            lastRound,
            lastRound.toInt,
          )
          assertActiveParties(store, nrParties, lastRound)
        }
      }
    }

    "Aggregate round_party_totals where some parties are not active in all rounds" in {
      cleanup()
      val (aggr, store) = mkAggregator(dsoParty).futureValue
      val lastRound = 5L
      val firstParties = Seq(1, 2)
      val firstRounds = Seq(0L, 1L, 2L)
      val lastParties = Seq(3, 4, 5)
      val lastRounds = Seq(3L, 4L, 5L)
      for {
        first <- generateRoundPartyTotalsRange(store, firstRounds, firstParties)
        last <- generateRoundPartyTotalsRange(store, lastRounds, lastParties)
        expectedRoundPartyRewardTotals = sumRoundPartyTotalsPerRound(first ++ last)
      } yield {
        aggregateRounds(aggr, 0, lastRound)
        assertRoundPartyTotalsWithLeaderBoards(
          expectedRoundPartyRewardTotals,
          aggr,
          store,
          lastRound,
          lastRound.toInt,
        )
        val activeParties = queryActiveParties()

        forAll(firstParties) { party =>
          activeParties
            .filter(_.party == mkPartyId(s"party-$party").toProtoPrimitive)
            .map(_.closedRound) should contain theSameElementsAs Seq(firstRounds.last)
        }
        forAll(lastParties) { party =>
          activeParties
            .filter(_.party == mkPartyId(s"party-$party").toProtoPrimitive)
            .map(_.closedRound) should contain theSameElementsAs Seq(lastRounds.last)
        }
      }
    }

    "Aggregate round_party_totals where some parties are not active in some rounds, then active again in other rounds" in {
      cleanup()
      val (aggr, store) = mkAggregator(dsoParty).futureValue
      val lastRound = 9L
      val middleRound = 5L
      val firstParties = Seq(1, 2)
      val firstRounds = Seq(0L, 1L, 2L)
      val middleParties = Seq(3, 4, 5)
      val middleRounds = Seq(3L, 4L, 5L)
      // new parties start to get active after the middle rounds, not active in the last rounds
      val newParties = Seq(6, 7, 8)
      val newPartyRounds = Seq(6L, 7L)
      // first parties are active in the first and last rounds, not in the middle or 'new party rounds'
      val lastParties = firstParties
      val lastRounds = Seq(8L, 9L)

      for {
        first <- generateRoundPartyTotalsRange(store, firstRounds, firstParties)
        middle <- generateRoundPartyTotalsRange(store, middleRounds, middleParties)
        newOnes <- generateRoundPartyTotalsRange(store, newPartyRounds, newParties)
        last <- generateRoundPartyTotalsRange(store, lastRounds, lastParties)
        expectedRoundPartyRewardTotals = sumRoundPartyTotalsPerRound(
          first ++ middle ++ newOnes ++ last
        )
      } yield {
        aggregateRounds(aggr, 0, middleRound - 1)
        aggregateRounds(aggr, middleRound, lastRound)
        assertRoundPartyTotalsWithLeaderBoards(
          expectedRoundPartyRewardTotals,
          aggr,
          store,
          lastRound,
          lastRound.toInt,
        )
        val activeParties = queryActiveParties()

        forAll(firstParties) { party =>
          activeParties
            .filter(_.party == mkPartyId(s"party-$party").toProtoPrimitive)
            .map(_.closedRound) should contain theSameElementsAs Seq(lastRounds.last)
        }
        forAll(middleParties) { party =>
          activeParties
            .filter(_.party == mkPartyId(s"party-$party").toProtoPrimitive)
            .map(_.closedRound) should contain theSameElementsAs Seq(middleRounds.last)
        }
        forAll(newParties) { party =>
          activeParties
            .filter(_.party == mkPartyId(s"party-$party").toProtoPrimitive)
            .map(_.closedRound) should contain theSameElementsAs Seq(newPartyRounds.last)
        }
      }
    }

    "Fail to backfill aggregates if it cannot read aggregates before round" in {
      val (aggr, store) = mkAggregator(dsoParty).futureValue
      val now = CantonTimestamp.now()
      val lastRound = 10L
      (for {
        _ <- storage
          .update_(aggr.insertRoundTotals(RoundTotals(lastRound, now)), "insert round total")
          .failOnShutdown("insertRoundTotals")
        res <- store.backFillAggregates()
      } yield {
        res
      }).futureValue shouldBe None
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
    "not fail on concurrent init of active parties" in {
      cleanup()
      val (aggr, store) =
        mkAggregator(
          dsoParty,
          ingestFromParticipantBegin = false,
          createReader,
        ).futureValue

      for {
        _ <- generateRoundPartyTotalsRange(store, Seq(0, 1, 2), Seq(0, 1, 2))
      } yield {
        queryActiveParties() should be(empty)
        aggregateRounds(aggr, 0, 2)
        // simulate that no active parties exist
        storage.update_(sqlu"truncate active_parties;", "truncate active_parties").futureValueUS
        // should not fail at least
        val actions = (1 to 100).map(_ => sqlu"call initialize_active_parties()")
        MonadUtil
          .parTraverseWithLimit(PositiveInt.tryCreate(10))(actions) { action =>
            storage.update_(action, "initialize_active_parties")
          }
          .futureValueUS should have size 100
        queryActiveParties() should not be (empty)
      }
    }
  }

  "append round totals from round zero to last closed round (inclusive)" in testRoundTotals(
    initialRound = 0L
  )

  "append round totals from non-zero initial round to last closed round (inclusive)" in testRoundTotals(
    initialRound = 10L
  )

  "aggregate round_party_totals from round zero to last closed round (inclusive)" in testRoundPartyTotalsAggregation(
    initialRound = 0L
  )

  "backfill aggregates before existing rounds total, up to round zero" in testBackfillingUpToInitialRound(
    initialRound = 0L
  )

  "backfill aggregates before existing rounds total, up to non-zero round" in testBackfillingUpToInitialRound(
    initialRound = 10000L
  )

  private def testRoundTotals(initialRound: Long): Future[Assertion] = {
    cleanup()
    val (aggr, store) =
      mkAggregator(dsoParty, initialRound = initialRound).futureValue
    val range = (initialRound to initialRound + 3L)

    val party1 = mkPartyId("party1")
    for {
      _ <- MonadUtil
        .sequentialTraverse(range) { i =>
          addAppReward(
            store,
            i,
            i.toDouble - initialRound,
            party1,
          )
        }
      _ <- MonadUtil
        .sequentialTraverse(range) { i =>
          addValidatorReward(
            store,
            i,
            (i + 1).toDouble - initialRound,
            party1,
          )
        }
      _ <- MonadUtil
        .sequentialTraverse(range) { i =>
          addClosedRound(
            store,
            i,
          )
        }
    } yield {
      val lastClosedRound = initialRound + 2L
      val previousRoundTotals = aggr.getLastAggregatedRoundTotals().futureValue
      val _ = storage
        .update_(
          aggr.aggregateRoundTotals(previousRoundTotals, lastClosedRound),
          "aggregate round totals",
        )
        .futureValueUS

      val roundTotals0 = aggr.getRoundTotals(initialRound).futureValue.value
      roundTotals0.copy(closedRoundEffectiveAt = CantonTimestamp.MinValue) shouldBe
        RoundTotals(
          closedRound = initialRound,
          closedRoundEffectiveAt = CantonTimestamp.MinValue,
          appRewards = BigDecimal(0),
          validatorRewards = BigDecimal(1),
          cumulativeAppRewards = BigDecimal(0),
          cumulativeValidatorRewards = BigDecimal(1),
        )

      val roundTotals1 = aggr.getRoundTotals(initialRound + 1L).futureValue.value
      roundTotals1.copy(closedRoundEffectiveAt = CantonTimestamp.MinValue) shouldBe
        RoundTotals(
          closedRound = initialRound + 1L,
          closedRoundEffectiveAt = CantonTimestamp.MinValue,
          appRewards = BigDecimal(1),
          validatorRewards = BigDecimal(2),
          cumulativeAppRewards = roundTotals0.appRewards + BigDecimal(1),
          cumulativeValidatorRewards = roundTotals0.validatorRewards + BigDecimal(2),
        )

      val prevTotals = aggr.getLastAggregatedRoundTotals().futureValue.value

      prevTotals.copy(closedRoundEffectiveAt = CantonTimestamp.MinValue) shouldBe
        RoundTotals(
          closedRound = initialRound + 2L,
          closedRoundEffectiveAt = CantonTimestamp.MinValue,
          appRewards = BigDecimal(2),
          validatorRewards = BigDecimal(3),
          cumulativeAppRewards = BigDecimal(3),
          cumulativeValidatorRewards = BigDecimal(6),
        )
      store
        .getRewardsCollectedInRound(initialRound + 2L)
        .futureValue shouldBe prevTotals.appRewards + prevTotals.validatorRewards
      val (round, effectiveAt) = store.getRoundOfLatestData().futureValue
      round shouldBe prevTotals.closedRound
      effectiveAt shouldBe prevTotals.closedRoundEffectiveAt.toInstant
      store.getAggregatedRounds().futureValue.value shouldBe ScanAggregator.RoundRange(
        initialRound,
        prevTotals.closedRound,
      )
      store
        .getRoundTotals(prevTotals.closedRound, prevTotals.closedRound)
        .futureValue
        .loneElement shouldBe prevTotals
      store
        .getRoundTotals(initialRound, prevTotals.closedRound)
        .futureValue should contain theSameElementsAs Seq(roundTotals0, roundTotals1, prevTotals)
    }
  }

  private def testRoundPartyTotalsAggregation(initialRound: Long): Future[Assertion] = {
    cleanup()
    val (aggr, store) = mkAggregator(dsoParty, initialRound = initialRound).futureValue
    val lastRound = initialRound + 4L

    for {
      expectedRoundPartyRewardTotals <- generateRoundPartyTotals(store, lastRound)
        .map(sumRoundPartyTotalsPerRound)
    } yield {
      aggregateRounds(aggr, initialRound, lastRound)
      val limit = 4

      assertRoundPartyTotalsWithLeaderBoards(
        expectedRoundPartyRewardTotals,
        aggr,
        store,
        lastRound,
        limit,
      )

      store.getAggregatedRounds().futureValue.value shouldBe ScanAggregator.RoundRange(
        initialRound,
        lastRound,
      )
    }
  }

  def testBackfillingUpToInitialRound(initialRound: Long): Future[Assertion] = {
    cleanup()
    val lastRound = initialRound + 10L
    val now = CantonTimestamp.now()

    val previousRoundAggregates = mkRoundAggregates(initialRound, lastRound - 1)

    def createDsoReader(store: DbScanStore) = {
      val _ = store
      new TestScanAggregatesReader(previousRoundAggregates)
    }

    val (aggr, store) =
      mkAggregator(
        dsoParty,
        ingestFromParticipantBegin = false,
        createDsoReader,
        initialRound = initialRound,
      ).futureValue

    val prevRoundTotals = RoundTotals(lastRound, now)
    for {
      _ <- storage
        .update_(aggr.insertRoundTotals(prevRoundTotals), "insert round total")
        .failOnShutdown("insertRoundTotals")
      res <- MonadUtil
        .sequentialTraverse(initialRound to lastRound - 1) { _ =>
          store.backFillAggregates()
        }
        .map(_.forall(_.exists(_ >= initialRound)))
      backFilledRoundTotals <- store.getRoundTotals(initialRound, lastRound - 1)
      backFilledRoundPartyTotals <- store.getRoundPartyTotals(initialRound, lastRound - 1)
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

  private def cleanup() = {
    storage
      .update(
        sqlu"""
        truncate table active_parties;
        truncate table round_totals;
        truncate table round_party_totals;
        """,
        "truncate",
      )
      .futureValueUS;
  }

  private def aggregateRounds(aggr: ScanAggregator, start: Long, end: Long): Unit = {
    MonadUtil
      .sequentialTraverse(start.toInt to end.toInt) { i =>
        storage
          .update(
            aggr
              .aggregateRoundTotals(None, i.toLong)
              .andThen(aggr.aggregateRoundPartyTotals(i.toLong)),
            "aggregate",
          )
      }
      .futureValueUS
  }
  case class ActiveParty(
      storeId: Int,
      party: Party,
      closedRound: Long,
  )
  implicit val GetResultActiveParty: GetResult[ActiveParty] =
    GetResult { prs =>
      import prs.*
      (ActiveParty.apply _).tupled(
        (
          <<[Int],
          <<[String],
          <<[Long],
        )
      )
    }

  private def queryActiveParties(): Vector[ActiveParty] = storage
    .query(
      sql"""select store_id, party, closed_round from active_parties order by store_id, party"""
        .as[ActiveParty],
      "count active parties",
    )
    .futureValueUS

  private def assertActiveParties(
      store: DbScanStore,
      nrParties: Long,
      expectedClosedRound: Long,
  ) = {
    val activeParties = queryActiveParties()
    activeParties should have size (nrParties)
    activeParties.map(_.party).distinct should have size (nrParties)
    forAll(activeParties) { ap =>
      ap.storeId shouldBe store.txLogStoreId
      ap.closedRound should be(expectedClosedRound)
    }
  }

  private def assertRoundPartyTotalsWithLeaderBoards(
      expectedRoundPartyRewardTotals: Map[Long, List[RoundPartyTotals]],
      aggr: ScanAggregator,
      store: DbScanStore,
      lastRound: Long,
      limit: Int,
  ) = {
    for (i <- 0 to lastRound.toInt) {
      val round = i.toLong
      val roundPartyTotals = aggr.getRoundPartyTotals(round).futureValue
      roundPartyTotals should contain theSameElementsAs expectedRoundPartyRewardTotals(round)
      val topProviders =
        getTopProvidersByAppRewardsFromTxLog(round, limit, aggr.txLogStoreId).futureValueUS
      topProviders should not be empty
      if (i == lastRound.toInt) {
        store.getTopProvidersByAppRewards(round, limit).futureValue shouldBe topProviders
      }
      val topValidatorsByValidatorRewards =
        getTopValidatorsByValidatorRewardsFromTxLog(
          round,
          limit,
          aggr.txLogStoreId,
        ).futureValueUS

      if (i == lastRound.toInt) {
        store
          .getTopValidatorsByValidatorRewards(round, limit)
          .futureValue shouldBe topValidatorsByValidatorRewards
      }
      if (i == lastRound.toInt) {
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
    }
    val topProviders =
      getTopProvidersByAppRewardsFromTxLog(lastRound, limit, aggr.txLogStoreId).futureValueUS
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
  }

  private def generateRoundPartyTotalsRange(
      store: DbScanStore,
      rounds: Seq[Long],
      parties: Seq[Int],
  ) = {
    MonadUtil
      .sequentialTraverse(rounds) { round =>
        MonadUtil
          .sequentialTraverse(parties) { j =>
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
  }

  private def generateRoundPartyTotals(
      store: DbScanStore,
      lastRound: Long,
      nrParties: Long = 10L,
  ) = {
    val partiesRange = (0 until nrParties.toInt).toList
    generateRoundPartyTotalsRange(store, (0L to lastRound).toList, partiesRange)
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
            acc.lastOption.getOrElse(RoundPartyTotals(closedRound = 0, party = party))
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

  override protected def cleanDb(storage: DbStorage)(implicit
      traceContext: TraceContext
  ): FutureUnlessShutdown[?] =
    resetAllAppTables(storage)

  def mkAggregator(
      dsoParty: PartyId,
      ingestFromParticipantBegin: Boolean = false,
      createReader: DbScanStore => ScanAggregatesReader = createReader,
      initialRound: Long = 0,
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
      ingestionConfig = IngestionConfig(),
      new DbScanStoreMetrics(new NoOpMetricsFactory(), loggerFactory, ProcessingTimeout()),
      initialRound = initialRound,
    )(parallelExecutionContext, implicitly, implicitly)
    for {
      _ <- store.multiDomainAcsStore.testIngestionSink.initialize()
      _ <- store.multiDomainAcsStore.testIngestionSink
        .ingestAcs(nextOffset(), Seq.empty, Seq.empty, Seq.empty)
      _ <- store.domains.ingestionSink.ingestConnectedDomains(
        Map(SynchronizerAlias.tryCreate(domain) -> dummyDomain)
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
      result <- storage
        .query(
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
        .failOnShutdown
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
        order by total_app_rewards desc, rewarded_party desc
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
        order by total_validator_rewards desc, rewarded_party desc
        limit $limit;
        """.as[(PartyId, BigDecimal)]
    storage.query(q, "getTopValidatorsByValidatorRewardsFromTxLog")
  }

  def getTopValidatorsByPurchasedTrafficFromTxLog(
      asOfEndOfRound: Long,
      limit: Int,
      txLogStoreId: TxLogStoreId,
  ): Future[Seq[HttpScanAppClient.ValidatorPurchasedTraffic]] = for {
    rows <- storage
      .query(
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
              order by total_traffic_purchased desc, validator desc
              limit $limit;
           """.as[(PartyId, Long, Long, BigDecimal, Long)],
        "getTopValidatorsByPurchasedTrafficFromTxLog",
      )
      .failOnShutdown
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
