// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

import cats.data.NonEmptyList
import com.daml.ledger.javaapi.data.{OffsetCheckpoint, SynchronizerTime}
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.config.IngestionConfig
import org.lfdecentralizedtrust.splice.environment.ledger.api.TreeUpdateOrOffsetCheckpoint
import org.lfdecentralizedtrust.splice.environment.{DarResources, RetryProvider}
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.scan.store.ScanRewardsReferenceStore
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanRewardsReferenceStore
import org.lfdecentralizedtrust.splice.store.{HardLimit, Limit, StoreTestBase, TcsStore}
import org.lfdecentralizedtrust.splice.util.{ResourceTemplateDecoder, TemplateJsonDecoder}
import slick.jdbc.JdbcProfile

class DbScanRewardsReferenceStoreTest
    extends StoreTestBase
    with SplicePostgresTest
    with AcsJdbcTypes {

  "DbScanRewardsReferenceStore" should {

    "lookupFeaturedAppRightsAsOf returns correct contracts" in {
      val store = mkStore()
      val far1 = featuredAppRight(userParty(1))
        .copy(createdAt = CantonTimestamp.ofEpochSecond(100).toInstant)
      val far2 = featuredAppRight(userParty(2))
        .copy(createdAt = CantonTimestamp.ofEpochSecond(200).toInstant)
      val far3 = featuredAppRight(userParty(3))
        .copy(createdAt = CantonTimestamp.ofEpochSecond(300).toInstant)
      for {
        _ <- initWithAcs()(store.multiDomainAcsStore)
        _ <- sync1.create(far1, recordTime = CantonTimestamp.ofEpochSecond(100).toInstant)(
          store.multiDomainAcsStore
        )
        _ <- sync1.create(far2, recordTime = CantonTimestamp.ofEpochSecond(200).toInstant)(
          store.multiDomainAcsStore
        )
        _ <- sync1.create(far3, recordTime = CantonTimestamp.ofEpochSecond(300).toInstant)(
          store.multiDomainAcsStore
        )
        _ <- sync1.archive(far1, recordTime = CantonTimestamp.ofEpochSecond(300).toInstant)(
          store.multiDomainAcsStore
        )
        _ <- sync1.archive(far3, recordTime = CantonTimestamp.ofEpochSecond(400).toInstant)(
          store.multiDomainAcsStore
        )

        resultAt50 <- store.lookupFeaturedAppRightsAsOf(CantonTimestamp.ofEpochSecond(50))
        _ = resultAt50 shouldBe empty

        resultAt100 <- store.lookupFeaturedAppRightsAsOf(CantonTimestamp.ofEpochSecond(100))
        _ = resultAt100.map(_.contract) shouldBe Seq(far1)

        resultAt250 <- store.lookupFeaturedAppRightsAsOf(CantonTimestamp.ofEpochSecond(250))
        _ = resultAt250.map(_.contract).toSet shouldBe Set(far1, far2)

        resultAt300 <- store.lookupFeaturedAppRightsAsOf(CantonTimestamp.ofEpochSecond(300))
        _ = resultAt300.map(_.contract).toSet shouldBe Set(far2, far3)

        resultAt400 <- store.lookupFeaturedAppRightsAsOf(CantonTimestamp.ofEpochSecond(400))
        _ = resultAt400.map(_.contract) shouldBe Seq(far2)
      } yield succeed
    }

    "lookupOpenMiningRoundsAsOf and lookupOpenMiningRoundsActiveWithin return correct contracts" in {
      val store = mkStore()
      val omr1 = openMiningRound(dsoParty, round = 3, amuletPrice = 1.0)
        .copy(createdAt = CantonTimestamp.ofEpochSecond(100).toInstant)
      val omr2 = openMiningRound(dsoParty, round = 4, amuletPrice = 1.5)
        .copy(createdAt = CantonTimestamp.ofEpochSecond(200).toInstant)
      val omr3 = openMiningRound(dsoParty, round = 5, amuletPrice = 2.0)
        .copy(createdAt = CantonTimestamp.ofEpochSecond(300).toInstant)
      for {
        _ <- initWithAcs()(store.multiDomainAcsStore)
        _ <- sync1.create(omr1, recordTime = CantonTimestamp.ofEpochSecond(100).toInstant)(
          store.multiDomainAcsStore
        )
        _ <- sync1.create(omr2, recordTime = CantonTimestamp.ofEpochSecond(200).toInstant)(
          store.multiDomainAcsStore
        )
        _ <- sync1.create(omr3, recordTime = CantonTimestamp.ofEpochSecond(300).toInstant)(
          store.multiDomainAcsStore
        )
        _ <- sync1.archive(omr1, recordTime = CantonTimestamp.ofEpochSecond(300).toInstant)(
          store.multiDomainAcsStore
        )
        _ <- sync1.archive(omr3, recordTime = CantonTimestamp.ofEpochSecond(400).toInstant)(
          store.multiDomainAcsStore
        )

        // lookupOpenMiningRoundsAsOf point-in-time checks
        resultAt50 <- store.lookupOpenMiningRoundsAsOf(CantonTimestamp.ofEpochSecond(50))
        _ = resultAt50 shouldBe empty

        resultAt100 <- store.lookupOpenMiningRoundsAsOf(CantonTimestamp.ofEpochSecond(100))
        _ = resultAt100.map(_.contract) shouldBe Seq(omr1)

        resultAt250 <- store.lookupOpenMiningRoundsAsOf(CantonTimestamp.ofEpochSecond(250))
        _ = resultAt250.map(_.contract).toSet shouldBe Set(omr1, omr2)

        resultAt300 <- store.lookupOpenMiningRoundsAsOf(CantonTimestamp.ofEpochSecond(300))
        _ = resultAt300.map(_.contract).toSet shouldBe Set(omr2, omr3)

        resultAt400 <- store.lookupOpenMiningRoundsAsOf(CantonTimestamp.ofEpochSecond(400))
        _ = resultAt400.map(_.contract) shouldBe Seq(omr2)

        // lookupOpenMiningRoundsActiveWithin: [100, 400] should return all 3 contracts
        resultRange_100_400 <- store.lookupOpenMiningRoundsActiveWithin(
          CantonTimestamp.ofEpochSecond(100),
          CantonTimestamp.ofEpochSecond(400),
        )
        _ = resultRange_100_400
          .map(_.contractWithState.contract)
          .toSet shouldBe Set(omr1, omr2, omr3)

        // contractsActiveAsOf on the range result should match lookupOpenMiningRoundsAsOf
        _ = TcsStore
          .contractsActiveAsOf(
            resultRange_100_400,
            CantonTimestamp.ofEpochSecond(100),
          ) shouldBe resultAt100
        _ = TcsStore
          .contractsActiveAsOf(
            resultRange_100_400,
            CantonTimestamp.ofEpochSecond(250),
          ) shouldBe resultAt250
        _ = TcsStore
          .contractsActiveAsOf(
            resultRange_100_400,
            CantonTimestamp.ofEpochSecond(300),
          ) shouldBe resultAt300
        _ = TcsStore
          .contractsActiveAsOf(
            resultRange_100_400,
            CantonTimestamp.ofEpochSecond(400),
          ) shouldBe resultAt400

        // Also confirm lookupOpenMiningRoundsActiveWithin for various ranges
        resultRange_100_200 <- store.lookupOpenMiningRoundsActiveWithin(
          CantonTimestamp.ofEpochSecond(100),
          CantonTimestamp.ofEpochSecond(200),
        )
        _ = resultRange_100_200
          .map(_.contractWithState.contract)
          .toSet shouldBe Set(omr1, omr2)

        resultRange_100_300 <- store.lookupOpenMiningRoundsActiveWithin(
          CantonTimestamp.ofEpochSecond(100),
          CantonTimestamp.ofEpochSecond(300),
        )
        _ = resultRange_100_300
          .map(_.contractWithState.contract)
          .toSet shouldBe Set(omr1, omr2, omr3)

        resultRange_200_300 <- store.lookupOpenMiningRoundsActiveWithin(
          CantonTimestamp.ofEpochSecond(200),
          CantonTimestamp.ofEpochSecond(300),
        )
        _ = resultRange_200_300
          .map(_.contractWithState.contract)
          .toSet shouldBe Set(omr1, omr2, omr3)

        resultRange_300_400 <- store.lookupOpenMiningRoundsActiveWithin(
          CantonTimestamp.ofEpochSecond(300),
          CantonTimestamp.ofEpochSecond(400),
        )
        _ = resultRange_300_400
          .map(_.contractWithState.contract)
          .toSet shouldBe Set(omr2, omr3)
      } yield succeed
    }

    "lookupOpenMiningRoundByNumber returns the correct contract" in {
      val store = mkStore()
      val omr3 = openMiningRound(dsoParty, round = 3, amuletPrice = 1.0)
        .copy(createdAt = CantonTimestamp.ofEpochSecond(100).toInstant)
      val omr4 = openMiningRound(dsoParty, round = 4, amuletPrice = 1.5)
        .copy(createdAt = CantonTimestamp.ofEpochSecond(200).toInstant)
      val omr5 = openMiningRound(dsoParty, round = 5, amuletPrice = 2.0)
        .copy(createdAt = CantonTimestamp.ofEpochSecond(300).toInstant)
      for {
        _ <- initWithAcs()(store.multiDomainAcsStore)
        _ <- sync1.create(omr3, recordTime = CantonTimestamp.ofEpochSecond(100).toInstant)(
          store.multiDomainAcsStore
        )
        _ <- sync1.create(omr4, recordTime = CantonTimestamp.ofEpochSecond(200).toInstant)(
          store.multiDomainAcsStore
        )
        _ <- sync1.create(omr5, recordTime = CantonTimestamp.ofEpochSecond(300).toInstant)(
          store.multiDomainAcsStore
        )
        // Archive round 3 — it should still be found in the archive table
        _ <- sync1.archive(omr3, recordTime = CantonTimestamp.ofEpochSecond(350).toInstant)(
          store.multiDomainAcsStore
        )

        // Round 3: archived — found via archive table
        result3 <- store.lookupOpenMiningRoundByNumber(3)
        _ = result3 shouldBe Some(omr3)

        // Round 4: still active — found via active table
        result4 <- store.lookupOpenMiningRoundByNumber(4)
        _ = result4 shouldBe Some(omr4)

        // Round 5: still active
        result5 <- store.lookupOpenMiningRoundByNumber(5)
        _ = result5 shouldBe Some(omr5)

        // Round 99: never existed
        resultMissing <- store.lookupOpenMiningRoundByNumber(99)
        _ = resultMissing shouldBe None
      } yield succeed
    }

    "lookupActiveOpenMiningRounds" in {
      val store = mkStore()
      // Timeline (ingestion start = 250, earliest archived_at):
      //   t=100: round3 created (opensAt=100)
      //   t=200: round4 created (opensAt=200)
      //   t=250: round3 archived
      //   t=300: round5 created (opensAt=400)
      //   t=375: round4 archived
      //   t=400: round6 created (opensAt=500)
      val round3 =
        openMiningRound(dsoParty, round = 3, amuletPrice = 1.0, opensAt = ts(100).toInstant)
          .copy(createdAt = ts(75).toInstant)
      val round4 =
        openMiningRound(dsoParty, round = 4, amuletPrice = 1.5, opensAt = ts(200).toInstant)
          .copy(createdAt = ts(200).toInstant)
      val round5 =
        openMiningRound(dsoParty, round = 5, amuletPrice = 2.0, opensAt = ts(400).toInstant)
          .copy(createdAt = ts(300).toInstant)
      val round6 =
        openMiningRound(dsoParty, round = 6, amuletPrice = 2.5, opensAt = ts(500).toInstant)
          .copy(createdAt = ts(400).toInstant)
      for {
        _ <- initWithAcs()(store.multiDomainAcsStore)

        // Before any archives: returns empty
        emptyResult <- store.lookupActiveOpenMiningRounds(Seq(ts(100)))
        _ = emptyResult shouldBe empty

        // Create rounds
        _ <- sync1.create(round3, recordTime = ts(100).toInstant)(store.multiDomainAcsStore)
        _ <- sync1.create(round4, recordTime = ts(200).toInstant)(store.multiDomainAcsStore)
        _ <- sync1.archive(round3, recordTime = ts(250).toInstant)(store.multiDomainAcsStore)
        _ <- sync1.create(round5, recordTime = ts(300).toInstant)(store.multiDomainAcsStore)
        _ <- sync1.archive(round4, recordTime = ts(375).toInstant)(store.multiDomainAcsStore)
        _ <- sync1.create(round6, recordTime = ts(400).toInstant)(store.multiDomainAcsStore)
        // Advance record time past all query points to unblock waitUntilRecordTimeReached
        _ <- store.multiDomainAcsStore.testIngestionSink.ingestUpdateBatch(
          NonEmptyList.of(
            TreeUpdateOrOffsetCheckpoint.Checkpoint(
              new OffsetCheckpoint(
                nextOffset(),
                java.util.List.of(
                  new SynchronizerTime(sync1.toProtoPrimitive, ts(600).toInstant)
                ),
              )
            )
          )
        )

        result <- store.lookupActiveOpenMiningRounds(
          Seq(30L, 150L, 220L, 250L, 275L, 350L, 375L, 400L, 450L, 550L).map(ts)
        )
      } yield {
        result.get(ts(30)) shouldBe None // before earliest archived_at
        result.get(ts(150)) shouldBe None // before earliest archived_at
        result.get(ts(220)) shouldBe None // before earliest archived_at
        result(ts(250)) shouldBe (4L, ts(200)) // exactly at earliest archived_at
        result(ts(275)) shouldBe (4L, ts(200))
        result(ts(350)) shouldBe (4L, ts(200)) // round5 not yet open (opensAt=400)
        result.get(ts(375)) shouldBe None // gap: round4 archived, round5 not yet open
        result(ts(400)) shouldBe (5L, ts(400))
        result.get(ts(401)) shouldBe None // 401 was not present in request
        result(ts(450)) shouldBe (5L, ts(400)) // round5 open, round6 not yet open
        result(ts(550)) shouldBe (5L, ts(400)) // both open, lowest round selected
      }
    }
  }

  private def ts(epochSecond: Long): CantonTimestamp =
    CantonTimestamp.ofEpochSecond(epochSecond)

  override lazy val profile: JdbcProfile = storage.api.jdbcProfile

  protected val sync1: SynchronizerId = SynchronizerId.tryFromString("domain1::domain")

  private def mkStore(): DbScanRewardsReferenceStore = {
    val participantId = mkParticipantId("DbScanRewardsReferenceStoreTest")
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResources(
        DarResources.amulet.all
      )
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)

    new DbScanRewardsReferenceStore(
      key = ScanRewardsReferenceStore.Key(dsoParty, sync1),
      storage,
      loggerFactory,
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
      DomainMigrationInfo(0L, None),
      participantId,
      IngestionConfig(),
      defaultLimit = HardLimit.tryCreate(Limit.DefaultMaxPageSize),
    )
  }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] = {
    for {
      _ <- resetAllAppTables(storage)
    } yield ()
  }
}
