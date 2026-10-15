// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

import cats.data.NonEmptyList
import com.daml.ledger.javaapi.data.{OffsetCheckpoint, SynchronizerTime}
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.{ParticipantId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{
  FeaturedAppKind,
  FeaturedAppRight,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.{
  RewardConfig,
  RewardVersion,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.decentralizedsynchronizer as decentralizedsynchronizerCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.codegen.java.splice.{
  cometbft as cometbftCodegen,
  dsorules as dsorulesCodegen,
}
import org.lfdecentralizedtrust.splice.config.IngestionConfig
import org.lfdecentralizedtrust.splice.environment.ledger.api.TreeUpdateOrOffsetCheckpoint
import org.lfdecentralizedtrust.splice.environment.{DarResources, RetryProvider}
import org.lfdecentralizedtrust.splice.scan.rewards.AssetActivityParams
import org.lfdecentralizedtrust.splice.scan.store.ScanRewardsReferenceStore
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanRewardsReferenceStore
import org.lfdecentralizedtrust.splice.store.{HardLimit, Limit, PageLimit, StoreTestBase, TcsStore}
import org.lfdecentralizedtrust.splice.util.{Contract, ResourceTemplateDecoder, TemplateJsonDecoder}
import slick.jdbc.JdbcProfile

import java.util.{Collections, Optional}
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

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

    "lookupFeaturedAppPartiesAsOf" should {

      "combine the FeaturedAppRights of the same provider" in {
        val store = mkStore()
        val p1 = userParty(1)
        val p2 = userParty(2)
        val p3 = userParty(3)
        val ops1 = userParty(11)
        val ops2 = userParty(12)
        // weights and kinds are combined to max weight and most general kind
        val far1a = featuredAppRight(
          p1,
          activityWeight = Some(BigDecimal(2)),
          appKind = Some(FeaturedAppKind.FAK_ASSET),
          opsParties = Some(Seq(ops1)),
        )
        val far1b = featuredAppRight(
          p1,
          activityWeight = Some(BigDecimal("0.5")),
          appKind = Some(FeaturedAppKind.FAK_GENERAL),
          opsParties = Some(Seq(ops2)),
        )
        val far2a = featuredAppRight(p2, appKind = Some(FeaturedAppKind.FAK_ASSET))
        val far2b = featuredAppRight(p2, appKind = Some(FeaturedAppKind.FAK_ASSETMOVING))
        val far3 = featuredAppRight(p3)
        val p1Entry = (FeaturedAppKind.FAK_GENERAL, BigDecimal(2))
        val p1EntryAfterArchive = (FeaturedAppKind.FAK_ASSET, BigDecimal(2))
        val p2Entry = (FeaturedAppKind.FAK_ASSETMOVING, BigDecimal(1))
        val p3Entry = (FeaturedAppKind.FAK_GENERAL, BigDecimal(1))
        for {
          _ <- initWithAcs()(store.multiDomainAcsStore)
          _ <- MonadUtil.sequentialTraverse_(Seq(far1a, far1b, far2a, far2b, far3))(
            createFeaturedAppRight(store, _, ts(100))
          )
          result <- store.lookupFeaturedAppPartiesAsOf(ts(100))
          _ = result shouldBe ScanRewardsReferenceStore.FeaturedAppParties(
            providers = Map(
              p1.toProtoPrimitive -> p1Entry,
              p2.toProtoPrimitive -> p2Entry,
              p3.toProtoPrimitive -> p3Entry,
            ),
            opsPartyProviders = Map(
              ops1.toProtoPrimitive -> p1.toProtoPrimitive,
              ops2.toProtoPrimitive -> p1.toProtoPrimitive,
            ),
          )
          // archiving the far1b, fixes the ops2
          _ <- sync1.archive(far1b, recordTime = ts(200).toInstant)(store.multiDomainAcsStore)
          resultAfterArchive <- store.lookupFeaturedAppPartiesAsOf(ts(200))
          _ = resultAfterArchive shouldBe ScanRewardsReferenceStore.FeaturedAppParties(
            providers = Map(
              p1.toProtoPrimitive -> p1EntryAfterArchive,
              p2.toProtoPrimitive -> p2Entry,
              p3.toProtoPrimitive -> p3Entry,
            ),
            opsPartyProviders = Map(ops1.toProtoPrimitive -> p1.toProtoPrimitive),
          )
        } yield succeed
      }

      "resolve an ops party claimed by several providers to the lexicographically lowest provider" in {
        val store = mkStore()
        val p1 = userParty(1)
        val p2 = userParty(2)
        val p3 = userParty(3)
        val x = userParty(10)
        p1.toProtoPrimitive should be < p2.toProtoPrimitive
        p2.toProtoPrimitive should be < p3.toProtoPrimitive
        // p1 lists x
        val far1 = featuredAppRight(p1, opsParties = Some(Seq(x)))
        // p2 lists both x and the provider p3 as ops parties
        val far2 = featuredAppRight(
          p2,
          activityWeight = Some(BigDecimal(2)),
          appKind = Some(FeaturedAppKind.FAK_ASSETMOVING),
          opsParties = Some(Seq(x, p3)),
        )
        // p3 lists the provider p1
        val far3 = featuredAppRight(
          p3,
          activityWeight = Some(BigDecimal(3)),
          opsParties = Some(Seq(p1)),
        )
        val p1Entry = (FeaturedAppKind.FAK_GENERAL, BigDecimal(1))
        val p2Entry = (FeaturedAppKind.FAK_ASSETMOVING, BigDecimal(2))
        val p3Entry = (FeaturedAppKind.FAK_GENERAL, BigDecimal(3))
        for {
          _ <- initWithAcs()(store.multiDomainAcsStore)
          _ <- MonadUtil.sequentialTraverse_(Seq(far3, far2, far1))(
            createFeaturedAppRight(store, _, ts(100))
          )
          resultAt100 <- store.lookupFeaturedAppPartiesAsOf(ts(100))
          _ = resultAt100 shouldBe ScanRewardsReferenceStore.FeaturedAppParties(
            providers = Map(
              p1.toProtoPrimitive -> p1Entry,
              p2.toProtoPrimitive -> p2Entry,
              p3.toProtoPrimitive -> p3Entry,
            ),
            opsPartyProviders = Map(
              x.toProtoPrimitive -> p1.toProtoPrimitive,
              p1.toProtoPrimitive -> p3.toProtoPrimitive,
              p3.toProtoPrimitive -> p2.toProtoPrimitive,
            ),
          )
          _ <- sync1.archive(far1, recordTime = ts(200).toInstant)(store.multiDomainAcsStore)
          resultAt200 <- store.lookupFeaturedAppPartiesAsOf(ts(200))
          // x falls back to p2
          _ = resultAt200 shouldBe ScanRewardsReferenceStore.FeaturedAppParties(
            providers = Map(
              p2.toProtoPrimitive -> p2Entry,
              p3.toProtoPrimitive -> p3Entry,
            ),
            opsPartyProviders = Map(
              x.toProtoPrimitive -> p2.toProtoPrimitive,
              p1.toProtoPrimitive -> p3.toProtoPrimitive,
              p3.toProtoPrimitive -> p2.toProtoPrimitive,
            ),
          )
          _ <- sync1.archive(far2, recordTime = ts(300).toInstant)(store.multiDomainAcsStore)
          resultAt300 <- store.lookupFeaturedAppPartiesAsOf(ts(300))
          _ = resultAt300 shouldBe ScanRewardsReferenceStore.FeaturedAppParties(
            providers = Map(p3.toProtoPrimitive -> p3Entry),
            opsPartyProviders = Map(p1.toProtoPrimitive -> p3.toProtoPrimitive),
          )
        } yield succeed
      }
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

    "lookupSvParticipantIdsAsOf returns the SV participants from the DsoRules active at a given time" in {
      val store = mkStore()
      val sv1Pid = mkParticipantId("sv1")
      val sv2Pid = mkParticipantId("sv2")
      val sv3Pid = mkParticipantId("sv3")

      val rulesV1 = dsoRules(
        svs = Map(
          userParty(11).toProtoPrimitive -> svInfo("sv1", sv1Pid),
          userParty(12).toProtoPrimitive -> svInfo("sv2", sv2Pid),
        )
      ).copy(createdAt = CantonTimestamp.ofEpochSecond(100).toInstant)
      val rulesV2 = dsoRules(
        svs = Map(
          userParty(11).toProtoPrimitive -> svInfo("sv1", sv1Pid),
          userParty(13).toProtoPrimitive -> svInfo("sv3", sv3Pid),
        )
      ).copy(createdAt = CantonTimestamp.ofEpochSecond(300).toInstant)

      for {
        _ <- initWithAcs()(store.multiDomainAcsStore)

        // Ingest v1 at t=100, swap to v2 at t=300.
        _ <- sync1.create(rulesV1, recordTime = CantonTimestamp.ofEpochSecond(100).toInstant)(
          store.multiDomainAcsStore
        )
        _ <- sync1.archive(rulesV1, recordTime = CantonTimestamp.ofEpochSecond(300).toInstant)(
          store.multiDomainAcsStore
        )
        _ <- sync1.create(rulesV2, recordTime = CantonTimestamp.ofEpochSecond(300).toInstant)(
          store.multiDomainAcsStore
        )

        // Before ingestion start
        emptyResult <- store.lookupSvParticipantIdsAsOf(CantonTimestamp.ofEpochSecond(50))
        _ = emptyResult shouldBe empty

        atV1 <- store.lookupSvParticipantIdsAsOf(CantonTimestamp.ofEpochSecond(100))
        _ = atV1 shouldBe Set(sv1Pid.uid.toProtoPrimitive, sv2Pid.uid.toProtoPrimitive)

        midV1 <- store.lookupSvParticipantIdsAsOf(CantonTimestamp.ofEpochSecond(150))
        _ = midV1 shouldBe Set(sv1Pid.uid.toProtoPrimitive, sv2Pid.uid.toProtoPrimitive)

        atV2 <- store.lookupSvParticipantIdsAsOf(CantonTimestamp.ofEpochSecond(300))
        _ = atV2 shouldBe Set(sv1Pid.uid.toProtoPrimitive, sv3Pid.uid.toProtoPrimitive)
      } yield succeed
    }

    "lookupAmuletConfigAsOf returns the config from the AmuletRules active at a given time" in {
      val store = mkStore()

      val rulesV1 = amuletRules(initialTickDuration = 10)
        .copy(createdAt = CantonTimestamp.ofEpochSecond(100).toInstant)
      val rulesV2 = amuletRules(initialTickDuration = 20)
        .copy(createdAt = CantonTimestamp.ofEpochSecond(300).toInstant)

      for {
        _ <- initWithAcs()(store.multiDomainAcsStore)

        // Ingest v1 at t=100, swap to v2 at t=300.
        _ <- sync1.create(rulesV1, recordTime = CantonTimestamp.ofEpochSecond(100).toInstant)(
          store.multiDomainAcsStore
        )
        _ <- sync1.archive(rulesV1, recordTime = CantonTimestamp.ofEpochSecond(300).toInstant)(
          store.multiDomainAcsStore
        )
        _ <- sync1.create(rulesV2, recordTime = CantonTimestamp.ofEpochSecond(300).toInstant)(
          store.multiDomainAcsStore
        )

        // Before ingestion start
        emptyResult <- store.lookupAmuletConfigAsOf(CantonTimestamp.ofEpochSecond(50))
        _ = emptyResult shouldBe None

        atV1 <- store.lookupAmuletConfigAsOf(CantonTimestamp.ofEpochSecond(100))
        _ = atV1 shouldBe Some(rulesV1.payload.configSchedule.initialValue)

        midV1 <- store.lookupAmuletConfigAsOf(CantonTimestamp.ofEpochSecond(150))
        _ = midV1 shouldBe Some(rulesV1.payload.configSchedule.initialValue)

        atV2 <- store.lookupAmuletConfigAsOf(CantonTimestamp.ofEpochSecond(300))
        _ = atV2 shouldBe Some(rulesV2.payload.configSchedule.initialValue)
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

    "listActiveCalculateRewardsV2 returns active contracts sorted by round" in {
      val store = mkStore()
      val cr5 = calculateRewardsV2(dsoParty, round = 5)
        .copy(createdAt = CantonTimestamp.ofEpochSecond(100).toInstant)
      val cr3 = calculateRewardsV2(dsoParty, round = 3)
        .copy(createdAt = CantonTimestamp.ofEpochSecond(200).toInstant)
      val cr7 = calculateRewardsV2(dsoParty, round = 7)
        .copy(createdAt = CantonTimestamp.ofEpochSecond(300).toInstant)
      for {
        _ <- initWithAcs()(store.multiDomainAcsStore)
        _ <- sync1.create(cr5, recordTime = CantonTimestamp.ofEpochSecond(100).toInstant)(
          store.multiDomainAcsStore
        )
        _ <- sync1.create(cr3, recordTime = CantonTimestamp.ofEpochSecond(200).toInstant)(
          store.multiDomainAcsStore
        )
        _ <- sync1.create(cr7, recordTime = CantonTimestamp.ofEpochSecond(300).toInstant)(
          store.multiDomainAcsStore
        )

        // All three active, sorted by round ascending
        all <- store.listActiveCalculateRewardsV2()
        _ = all.map(_.payload.round.number) shouldBe Seq(3L, 5L, 7L)

        // Limit respected
        limited <- store.listActiveCalculateRewardsV2(PageLimit.tryCreate(2))
        _ = limited.map(_.payload.round.number) shouldBe Seq(3L, 5L)

        // Archive round 3 — no longer returned
        _ <- sync1.archive(cr3, recordTime = CantonTimestamp.ofEpochSecond(400).toInstant)(
          store.multiDomainAcsStore
        )
        afterArchive <- store.listActiveCalculateRewardsV2()
        _ = afterArchive.map(_.payload.round.number) shouldBe Seq(5L, 7L)

        // Empty when all archived
        _ <- sync1.archive(cr5, recordTime = CantonTimestamp.ofEpochSecond(500).toInstant)(
          store.multiDomainAcsStore
        )
        _ <- sync1.archive(cr7, recordTime = CantonTimestamp.ofEpochSecond(600).toInstant)(
          store.multiDomainAcsStore
        )
        allArchived <- store.listActiveCalculateRewardsV2()
        _ = allArchived shouldBe Seq.empty
      } yield succeed
    }

    "listActiveCalculateRewardsV2ForRound returns contracts for the given round" in {
      val store = mkStore()
      val cr5 = calculateRewardsV2(dsoParty, round = 5)
        .copy(createdAt = CantonTimestamp.ofEpochSecond(100).toInstant)
      val cr3 = calculateRewardsV2(dsoParty, round = 3)
        .copy(createdAt = CantonTimestamp.ofEpochSecond(200).toInstant)
      val cr7 = calculateRewardsV2(dsoParty, round = 7)
        .copy(createdAt = CantonTimestamp.ofEpochSecond(300).toInstant)
      for {
        _ <- initWithAcs()(store.multiDomainAcsStore)
        _ <- sync1.create(cr5, recordTime = CantonTimestamp.ofEpochSecond(100).toInstant)(
          store.multiDomainAcsStore
        )
        _ <- sync1.create(cr3, recordTime = CantonTimestamp.ofEpochSecond(200).toInstant)(
          store.multiDomainAcsStore
        )
        _ <- sync1.create(cr7, recordTime = CantonTimestamp.ofEpochSecond(300).toInstant)(
          store.multiDomainAcsStore
        )

        // Returns contract for matching round
        result5 <- store.listActiveCalculateRewardsV2ForRound(5)
        _ = result5.map(_.payload.round.number) shouldBe Seq(5L)

        // Returns empty for non-existent round
        result99 <- store.listActiveCalculateRewardsV2ForRound(99)
        _ = result99 shouldBe empty

        // Returns empty after archiving
        _ <- sync1.archive(cr5, recordTime = CantonTimestamp.ofEpochSecond(400).toInstant)(
          store.multiDomainAcsStore
        )
        afterArchive <- store.listActiveCalculateRewardsV2ForRound(5)
        _ = afterArchive shouldBe empty

        // Other rounds unaffected
        still3 <- store.listActiveCalculateRewardsV2ForRound(3)
        _ = still3.map(_.payload.round.number) shouldBe Seq(3L)
      } yield succeed
    }

    "lookupLatestArchivedOpenMiningRound returns the max archived round as of a time" in {
      val store = mkStore()
      val omr3 = openMiningRound(dsoParty, round = 3, amuletPrice = 1.0)
        .copy(createdAt = ts(100).toInstant)
      val omr4 = openMiningRound(dsoParty, round = 4, amuletPrice = 1.5)
        .copy(createdAt = ts(200).toInstant)
      val omr5 = openMiningRound(dsoParty, round = 5, amuletPrice = 2.0)
        .copy(createdAt = ts(300).toInstant)
      // Archived with a higher round number, but not an OpenMiningRound —
      // must not be counted.
      val cr9 = calculateRewardsV2(dsoParty, round = 9)
        .copy(createdAt = ts(300).toInstant)
      for {
        _ <- initWithAcs()(store.multiDomainAcsStore)
        _ <- sync1.create(omr3, recordTime = ts(100).toInstant)(store.multiDomainAcsStore)
        _ <- sync1.create(omr4, recordTime = ts(200).toInstant)(store.multiDomainAcsStore)
        _ <- sync1.create(omr5, recordTime = ts(300).toInstant)(store.multiDomainAcsStore)
        _ <- sync1.create(cr9, recordTime = ts(300).toInstant)(store.multiDomainAcsStore)
        _ <- sync1.archive(omr3, recordTime = ts(350).toInstant)(store.multiDomainAcsStore)
        _ <- sync1.archive(cr9, recordTime = ts(400).toInstant)(store.multiDomainAcsStore)
        _ <- sync1.archive(omr4, recordTime = ts(450).toInstant)(store.multiDomainAcsStore)
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

        beforeAnyArchival <- store.lookupLatestArchivedOpenMiningRound(ts(300))
        atArchivalBoundary <- store.lookupLatestArchivedOpenMiningRound(ts(350))
        betweenArchivals <- store.lookupLatestArchivedOpenMiningRound(ts(449))
        atSecondArchival <- store.lookupLatestArchivedOpenMiningRound(ts(450))
        afterAllArchivals <- store.lookupLatestArchivedOpenMiningRound(ts(600))
      } yield {
        beforeAnyArchival shouldBe None
        // archived_at <= asOf is inclusive
        atArchivalBoundary shouldBe Some(3L)
        // cr9 (round 9, archived at t=400) is not an OpenMiningRound
        betweenArchivals shouldBe Some(3L)
        atSecondArchival shouldBe Some(4L)
        // omr5 is still active, so the max archived round stays 4
        afterAllArchivals shouldBe Some(4L)
      }
    }

    "lookupArchivedAtForOpenMiningRound returns the archival record_time for a round" in {
      val store = mkStore()
      val omr3 = openMiningRound(dsoParty, round = 3, amuletPrice = 1.0)
        .copy(createdAt = ts(100).toInstant)
      val omr4 = openMiningRound(dsoParty, round = 4, amuletPrice = 1.5)
        .copy(createdAt = ts(200).toInstant)
      val omr5 = openMiningRound(dsoParty, round = 5, amuletPrice = 2.0)
        .copy(createdAt = ts(300).toInstant)
      val cr3 = calculateRewardsV2(dsoParty, round = 3)
        .copy(createdAt = ts(350).toInstant)
      for {
        _ <- initWithAcs()(store.multiDomainAcsStore)
        _ <- sync1.create(omr3, recordTime = ts(100).toInstant)(store.multiDomainAcsStore)
        _ <- sync1.create(omr4, recordTime = ts(200).toInstant)(store.multiDomainAcsStore)
        _ <- sync1.create(omr5, recordTime = ts(300).toInstant)(store.multiDomainAcsStore)
        _ <- sync1.archive(omr3, recordTime = ts(350).toInstant)(store.multiDomainAcsStore)
        _ <- sync1.create(cr3, recordTime = ts(350).toInstant)(store.multiDomainAcsStore)
        _ <- sync1.archive(omr4, recordTime = ts(450).toInstant)(store.multiDomainAcsStore)

        archivedAt3 <- store.lookupArchivedAtForOpenMiningRound(3)
        archivedAt4 <- store.lookupArchivedAtForOpenMiningRound(4)
        archivedAt5 <- store.lookupArchivedAtForOpenMiningRound(5)
        archivedAt9 <- store.lookupArchivedAtForOpenMiningRound(9)
      } yield {
        archivedAt3 shouldBe Some(ts(350))
        archivedAt4 shouldBe Some(ts(450))
        archivedAt5 shouldBe None
        // Round doesn't exist
        archivedAt9 shouldBe None
      }
    }

    "pruneArchivedUpToRound" should {

      "delete all archived rows with archived_at at or before the round archival time" in {
        val store = mkStore()
        val omr3 = openMiningRound(dsoParty, round = 3, amuletPrice = 1.0)
          .copy(createdAt = ts(50).toInstant)
        val cr3 = calculateRewardsV2(dsoParty, round = 3)
          .copy(createdAt = ts(50).toInstant)
        val far1 = featuredAppRight(userParty(1))
          .copy(createdAt = ts(50).toInstant)
        val far2 = featuredAppRight(userParty(2))
          .copy(createdAt = ts(50).toInstant)
        val omr4 = openMiningRound(dsoParty, round = 4, amuletPrice = 1.5)
          .copy(createdAt = ts(200).toInstant)
        for {
          _ <- initWithAcs()(store.multiDomainAcsStore)
          _ <- sync1.create(omr3, recordTime = ts(50).toInstant)(store.multiDomainAcsStore)
          _ <- sync1.create(cr3, recordTime = ts(50).toInstant)(store.multiDomainAcsStore)
          _ <- sync1.create(far1, recordTime = ts(50).toInstant)(store.multiDomainAcsStore)
          _ <- sync1.create(far2, recordTime = ts(50).toInstant)(store.multiDomainAcsStore)
          _ <- sync1.create(omr4, recordTime = ts(200).toInstant)(store.multiDomainAcsStore)

          _ <- sync1.archive(far1, recordTime = ts(340).toInstant)(store.multiDomainAcsStore)
          _ <- sync1.archive(omr3, recordTime = ts(350).toInstant)(store.multiDomainAcsStore)
          _ <- sync1.archive(far1, recordTime = ts(360).toInstant)(store.multiDomainAcsStore)
          _ <- sync1.archive(cr3, recordTime = ts(380).toInstant)(store.multiDomainAcsStore)

          lowestPrunableBeforePrune <- store.lookupLowestPrunableArchivedRewardRound()

          deletedCount <- store.pruneArchivedUpToRound(roundNumber = 3)

          lowestPrunableAfterPrune <- store.lookupLowestPrunableArchivedRewardRound()

          afterPruneRound3 <- store.lookupOpenMiningRoundByNumber(3)
          farAfterPrune <- store.lookupFeaturedAppPartiesAsOf(ts(300))
          afterPruneRound4 <- store.lookupOpenMiningRoundByNumber(4)
        } yield {
          deletedCount shouldBe 2L
          lowestPrunableBeforePrune shouldBe Some(3L)
          lowestPrunableAfterPrune shouldBe None
          afterPruneRound3 shouldBe None
          farAfterPrune.providers.keySet should not contain userParty(1).toProtoPrimitive
          farAfterPrune.providers.keySet should contain(userParty(2).toProtoPrimitive)
          afterPruneRound4 shouldBe defined
        }
      }

      "moves the ingestion start to the earliest remaining archival" in {
        val store = mkStore()
        for {
          _ <- ingestOverlappingRounds(store)

          beforePrune <- store.lookupActiveOpenMiningRounds(Seq(ts(450), ts(620)))
          _ = beforePrune shouldBe Map(
            ts(450) -> roundInfo(5L, ts(300)),
            ts(620) -> roundInfo(7L, ts(500)),
          )

          deleted <- store.pruneArchivedUpToRound(3)
          _ = deleted shouldBe 1L

          // The store's "ingestion start" now moved to round 4's archival at t=400
          // so we no longer have the complete data for 450.
          afterPrune <- store.lookupActiveOpenMiningRounds(Seq(ts(450), ts(620)))
          _ = afterPrune shouldBe Map(ts(620) -> roundInfo(7L, ts(500)))
        } yield succeed
      }

      "fail when the round has not been observed as archived" in {
        val store = mkStore()
        for {
          _ <- initWithAcs()(store.multiDomainAcsStore)
          result <- store.pruneArchivedUpToRound(roundNumber = 2).failed
        } yield {
          result shouldBe an[IllegalStateException]
        }
      }

    }

    "lookupLowestPrunableArchivedRewardRound" should {

      "return the lowest archived round processed completely" in {
        val store = mkStore()
        val omr4 = openMiningRound(dsoParty, round = 4, amuletPrice = 1.0)
          .copy(createdAt = ts(100).toInstant)
        val cr3 = calculateRewardsV2(dsoParty, round = 3)
          .copy(createdAt = ts(200).toInstant)
        val pr5 = processRewardsV2(dsoParty, round = 5)
          .copy(createdAt = ts(300).toInstant)
        // Archiving this must not affect the result even though it archives earliest.
        val far1 = featuredAppRight(userParty(1))
          .copy(createdAt = ts(100).toInstant)
        for {
          _ <- initWithAcs()(store.multiDomainAcsStore)

          beforeAnyArchival <- store.lookupLowestPrunableArchivedRewardRound()
          _ = beforeAnyArchival shouldBe None

          _ <- sync1.create(far1, recordTime = ts(100).toInstant)(store.multiDomainAcsStore)
          _ <- sync1.create(omr4, recordTime = ts(100).toInstant)(store.multiDomainAcsStore)
          _ <- sync1.create(cr3, recordTime = ts(200).toInstant)(store.multiDomainAcsStore)
          _ <- sync1.create(pr5, recordTime = ts(300).toInstant)(store.multiDomainAcsStore)

          _ <- sync1.archive(far1, recordTime = ts(150).toInstant)(store.multiDomainAcsStore)
          onlyRoundlessArchived <- store.lookupLowestPrunableArchivedRewardRound()
          _ = onlyRoundlessArchived shouldBe None

          _ <- sync1.archive(omr4, recordTime = ts(400).toInstant)(store.multiDomainAcsStore)
          onlyOmr4Archived <- store.lookupLowestPrunableArchivedRewardRound()
          _ = onlyOmr4Archived shouldBe None

          _ <- sync1.archive(cr3, recordTime = ts(410).toInstant)(store.multiDomainAcsStore)

          // Round 3's OpenMiningRound was never ingested, so round 3 itself is
          // not a candidate; round 4 is the lowest round with an archived
          // OpenMiningRound with no active reward contract at or below it.
          cr3AndOmr4Archived <- store.lookupLowestPrunableArchivedRewardRound()
          _ = cr3AndOmr4Archived shouldBe Some(4L)

          _ <- sync1.archive(pr5, recordTime = ts(420).toInstant)(store.multiDomainAcsStore)

          // All of 3, 4, and 5 are archived
          allThreeArchived <- store.lookupLowestPrunableArchivedRewardRound()
          _ = allThreeArchived shouldBe Some(4L)
        } yield succeed
      }

      "exclude a round when a lower round still has an active CalculateRewardsV2" in {
        val store = mkStore()
        val omr3 = openMiningRound(dsoParty, round = 3, amuletPrice = 1.0)
          .copy(createdAt = ts(50).toInstant)
        val cr3 = calculateRewardsV2(dsoParty, round = 3)
          .copy(createdAt = ts(60).toInstant)
        val omr4 = openMiningRound(dsoParty, round = 4, amuletPrice = 1.0)
          .copy(createdAt = ts(100).toInstant)
        val cr4 = calculateRewardsV2(dsoParty, round = 4)
          .copy(createdAt = ts(110).toInstant)
        for {
          _ <- initWithAcs()(store.multiDomainAcsStore)

          _ <- sync1.create(omr3, recordTime = ts(50).toInstant)(store.multiDomainAcsStore)
          _ <- sync1.create(cr3, recordTime = ts(60).toInstant)(store.multiDomainAcsStore)
          _ <- sync1.create(omr4, recordTime = ts(100).toInstant)(store.multiDomainAcsStore)
          _ <- sync1.create(cr4, recordTime = ts(110).toInstant)(store.multiDomainAcsStore)

          _ <- sync1.archive(omr3, recordTime = ts(200).toInstant)(store.multiDomainAcsStore)
          _ <- sync1.archive(omr4, recordTime = ts(210).toInstant)(store.multiDomainAcsStore)
          _ <- sync1.archive(cr4, recordTime = ts(220).toInstant)(store.multiDomainAcsStore)
          whileCr3StillActive <- store.lookupLowestPrunableArchivedRewardRound()
          _ = whileCr3StillActive shouldBe None

          _ <- sync1.archive(cr3, recordTime = ts(230).toInstant)(store.multiDomainAcsStore)
          afterCr3Archived <- store.lookupLowestPrunableArchivedRewardRound()
          _ = afterCr3Archived shouldBe Some(3L)
        } yield succeed
      }

      "exclude a round when a lower round still has an active ProcessRewardsV2" in {
        val store = mkStore()
        val omr3 = openMiningRound(dsoParty, round = 3, amuletPrice = 1.0)
          .copy(createdAt = ts(50).toInstant)
        val pr3 = processRewardsV2(dsoParty, round = 3)
          .copy(createdAt = ts(60).toInstant)
        val omr4 = openMiningRound(dsoParty, round = 4, amuletPrice = 1.0)
          .copy(createdAt = ts(100).toInstant)
        val cr4 = calculateRewardsV2(dsoParty, round = 4)
          .copy(createdAt = ts(110).toInstant)
        for {
          _ <- initWithAcs()(store.multiDomainAcsStore)

          _ <- sync1.create(omr3, recordTime = ts(50).toInstant)(store.multiDomainAcsStore)
          _ <- sync1.create(pr3, recordTime = ts(60).toInstant)(store.multiDomainAcsStore)
          _ <- sync1.create(omr4, recordTime = ts(100).toInstant)(store.multiDomainAcsStore)
          _ <- sync1.create(cr4, recordTime = ts(110).toInstant)(store.multiDomainAcsStore)

          _ <- sync1.archive(omr3, recordTime = ts(200).toInstant)(store.multiDomainAcsStore)
          _ <- sync1.archive(omr4, recordTime = ts(210).toInstant)(store.multiDomainAcsStore)
          _ <- sync1.archive(cr4, recordTime = ts(220).toInstant)(store.multiDomainAcsStore)
          whilePr3StillActive <- store.lookupLowestPrunableArchivedRewardRound()
          _ = whilePr3StillActive shouldBe None

          _ <- sync1.archive(pr3, recordTime = ts(230).toInstant)(store.multiDomainAcsStore)
          afterPr3Archived <- store.lookupLowestPrunableArchivedRewardRound()
          _ = afterPr3Archived shouldBe Some(3L)
        } yield succeed
      }

    }

    "lookupPrunableRewardRound" should {

      // Assuming that the verdict ingestion is lagging, and we have no retention window
      // confirm that we don't prune a round until the verdict ingestion has moved past
      // the prunable round's next round's archival
      "return the round once the ingestion moved past the next round's archival" in {
        val store = mkStore()
        for {
          _ <- ingestOverlappingRounds(store)

          // Round 3 is the lowest round with an archived OpenMiningRound and no
          // active reward contract at or below it, and round 4 archived at t=400.
          lowestPrunable <- store.lookupLowestPrunableArchivedRewardRound()
          _ = lowestPrunable shouldBe Some(3L)
          archivedAt4 <- store.lookupArchivedAtForOpenMiningRound(4)
          _ = archivedAt4 shouldBe Some(ts(400))

          // Oldest open round 5, opened at t=300, ie before round 4's archival.
          atRound5 <- store.lookupPrunableRewardRound(ts(420), timeAfterAllRounds, noRetention)
          _ = atRound5 shouldBe None
          // Oldest open round 6, opened exactly at round 4's archival.
          atRound6 <- store.lookupPrunableRewardRound(ts(520), timeAfterAllRounds, noRetention)
          _ = atRound6 shouldBe None
          // Oldest open round 7, opened at t=500, ie past round 4's archival.
          atRound7 <- store.lookupPrunableRewardRound(ts(620), timeAfterAllRounds, noRetention)
          _ = atRound7 shouldBe Some(3L)

          activeAtRound7 <- store.lookupActiveOpenMiningRounds(Seq(ts(620)))
          _ = activeAtRound7 shouldBe Map(ts(620) -> roundInfo(7L, ts(500)))

          // Doing prune here confirms that the lookupPrunableRewardRound works with the new db state
          deleted <- store.pruneArchivedUpToRound(3)
          _ = deleted shouldBe 1L

          // Lookup works even after prune
          activeAtRound7AfterDelete <- store.lookupActiveOpenMiningRounds(Seq(ts(620)))
          _ = activeAtRound7AfterDelete shouldBe Map(ts(620) -> roundInfo(7L, ts(500)))

          // Round 4 is now the lowest prunable round, and round 5 archived at t=500.
          afterPruneAtRound7 <- store.lookupPrunableRewardRound(
            ts(620),
            timeAfterAllRounds,
            noRetention,
          )
          _ = afterPruneAtRound7 shouldBe None

          // Oldest open round 8, opened at t=600, ie past round 5's archival.
          afterPruneAtRound8 <- store.lookupPrunableRewardRound(
            ts(720),
            timeAfterAllRounds,
            noRetention,
          )
          _ = afterPruneAtRound8 shouldBe Some(4L)

          // Pruning round 4 as well, ie two rounds are pruned in a row.
          deletedRound4 <- store.pruneArchivedUpToRound(4)
          _ = deletedRound4 shouldBe 1L

          // At the same record time round 5 is kept, as round 8 opened exactly at
          // round 6's archival (t=600).
          afterPruneAtRound4 <- store.lookupPrunableRewardRound(
            ts(720),
            timeAfterAllRounds,
            noRetention,
          )
          _ = afterPruneAtRound4 shouldBe None
        } yield succeed
      }

      // Similar to above, but confirm that logic works correct even when we have a lag in round archival
      "return the round once the ingestion moved past the next round's archival even when round advancement lags" in {
        val store = mkStore()
        for {
          _ <- ingestLaggingArchivalRounds(store)

          // Round 3 is the lowest round with an archived OpenMiningRound and no
          // active reward contract at or below it, and round 4 archived at t=500.
          lowestPrunable <- store.lookupLowestPrunableArchivedRewardRound()
          _ = lowestPrunable shouldBe Some(3L)
          archivedAt4 <- store.lookupArchivedAtForOpenMiningRound(4)
          _ = archivedAt4 shouldBe Some(ts(500))

          // Round 5, the oldest open round as of t=520, opened at t=300, ie before
          // the ingestion start (t=350), so no active round resolves for t=520.
          activeAtRound5 <- store.lookupActiveOpenMiningRounds(Seq(ts(520)))
          _ = activeAtRound5 shouldBe empty
          atRound5 <- store.lookupPrunableRewardRound(ts(520), timeAfterAllRounds, noRetention)
          _ = atRound5 shouldBe None
          // Round 6, opened at t=450, ie before round 4's archival.
          atRound6 <- store.lookupPrunableRewardRound(ts(670), timeAfterAllRounds, noRetention)
          _ = atRound6 shouldBe None
          // Round 7, opened at t=600, ie past round 4's archival.
          atRound7 <- store.lookupPrunableRewardRound(ts(820), timeAfterAllRounds, noRetention)
          _ = atRound7 shouldBe Some(3L)

          activeAtRound7 <- store.lookupActiveOpenMiningRounds(Seq(ts(820)))
          _ = activeAtRound7 shouldBe Map(ts(820) -> roundInfo(7L, ts(600)))

          // Doing prune here confirms that the lookupPrunableRewardRound works with the new db state
          deleted <- store.pruneArchivedUpToRound(3)
          _ = deleted shouldBe 1L

          // Lookup works even after prune
          activeAtRound7AfterDelete <- store.lookupActiveOpenMiningRounds(Seq(ts(820)))
          _ = activeAtRound7AfterDelete shouldBe Map(ts(820) -> roundInfo(7L, ts(600)))

          // Round 4 is now the lowest prunable round, and round 5 archived at t=650.
          afterPruneAtRound7 <- store.lookupPrunableRewardRound(
            ts(820),
            timeAfterAllRounds,
            noRetention,
          )
          _ = afterPruneAtRound7 shouldBe None

          // Round 8, active as of t=970, opened at t=750, ie past round 5's archival.
          afterPruneAtRound8 <- store.lookupPrunableRewardRound(
            ts(970),
            timeAfterAllRounds,
            noRetention,
          )
          _ = afterPruneAtRound8 shouldBe Some(4L)

          // Pruning round 4 as well, ie two rounds are pruned in a row.
          deletedRound4 <- store.pruneArchivedUpToRound(4)
          _ = deletedRound4 shouldBe 1L

          // At the same record time round 5 is kept, as round 8 opened at t=750,
          // ie before round 6's archival (t=800).
          afterPruneAtRound4 <- store.lookupPrunableRewardRound(
            ts(970),
            timeAfterAllRounds,
            noRetention,
          )
          _ = afterPruneAtRound4 shouldBe None
        } yield succeed
      }

      "keep a round until it falls outside the retention period" in {
        val store = mkStore()
        val retentionPeriod = NonNegativeFiniteDuration.ofSeconds(300)
        for {
          _ <- ingestOverlappingRounds(store)

          noRetentionLookupAt620 <- store.lookupPrunableRewardRound(ts(620), ts(620), noRetention)
          _ = noRetentionLookupAt620 shouldBe Some(3L)

          withinRetention <- store.lookupPrunableRewardRound(ts(620), ts(620), retentionPeriod)
          _ = withinRetention shouldBe None

          atRetentionBoundary <- store.lookupPrunableRewardRound(ts(620), ts(700), retentionPeriod)
          _ = atRetentionBoundary shouldBe None

          pastRetention <- store.lookupPrunableRewardRound(ts(620), ts(701), retentionPeriod)
          _ = pastRetention shouldBe Some(3L)
        } yield succeed
      }

      // This test just describes the behavior, but it should never occur in
      // practice as verdict ingestion should itself wait on the
      // ScanRewardsReferenceStore.
      "wait until the store's ingestion reaches the last ingested verdict record time" in {
        val store = mkStore()
        for {
          _ <- ingestOverlappingRounds(store)

          atIngestedRecordTime <- store.lookupPrunableRewardRound(
            ts(800),
            timeAfterAllRounds,
            noRetention,
          )
          _ = atIngestedRecordTime shouldBe Some(3L)

          // A lastIngestedRecordTimes value past it waits for the ingestion to catch up
          pending = store.lookupPrunableRewardRound(ts(900), timeAfterAllRounds, noRetention)
          _ = pending.isCompleted shouldBe false

          _ <- advanceRecordTime(store, ts(900))
          prunableRound <- pending
          _ = prunableRound shouldBe Some(3L)
        } yield succeed
      }

      // Right after bootstrapping, only a single OpenMiningRound has been archived, so
      // there is no archival for the next round yet. The active round cannot resolve
      // either, as the next round opened before the store's ingestion start.
      "skip after bootstrap while only a single round has been archived" in {
        val store = mkStore()
        def omr(round: Long, createdAt: Long, opensAt: Long) =
          openMiningRound(
            dsoParty,
            round = round,
            amuletPrice = 1.0,
            opensAt = ts(opensAt).toInstant,
          )
            .copy(createdAt = ts(createdAt).toInstant)
        // Round n is archived (and round n+3 created) when round n+2 opens, so
        // round 2 was archived at t=200 together with round 5's creation. The store
        // bootstraps after that, with rounds 3, 4 and 5 open in the initial ACS.
        val omr3 = omr(3, createdAt = 0, opensAt = 100)
        val omr4 = omr(4, createdAt = 100, opensAt = 200)
        val omr5 = omr(5, createdAt = 200, opensAt = 300)
        val omr6 = omr(6, createdAt = 300, opensAt = 400)
        val acsStore = store.multiDomainAcsStore
        for {
          _ <- initWithAcs(
            Seq(omr3, omr4, omr5).map(StoreTestBase.AcsImportEntry(_, sync1, 0L))
          )(acsStore)
          _ <- sync1.create(omr6, recordTime = ts(300).toInstant)(acsStore)
          _ <- sync1.archive(omr3, recordTime = ts(300).toInstant)(acsStore)
          _ <- advanceRecordTime(store, ts(350))

          lowestPrunable <- store.lookupLowestPrunableArchivedRewardRound()
          _ = lowestPrunable shouldBe Some(3L)
          archivedAt4 <- store.lookupArchivedAtForOpenMiningRound(4)
          _ = archivedAt4 shouldBe None
          // Round 4 opened at t=200, before the ingestion start at t=300
          active <- store.lookupActiveOpenMiningRounds(Seq(ts(350)))
          _ = active shouldBe empty

          result <- store.lookupPrunableRewardRound(ts(350), timeAfterAllRounds, noRetention)
          _ = result shouldBe None
        } yield succeed
      }
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
        openMiningRound(
          dsoParty,
          round = 5,
          amuletPrice = 2.0,
          opensAt = ts(400).toInstant,
          rewardConfig = Optional.of(
            rewardConfig(
              extraFeaturedAssetActivityWeight = Some("0.2"),
              maxExtraFeaturedAssetActivityWeightPerAsset = Some("0.1"),
              dsoAppActivityWeight = Some("0.5"),
            )
          ),
        ).copy(createdAt = ts(300).toInstant)
      // round6 does not set all the asset activity params
      val round6 =
        openMiningRound(
          dsoParty,
          round = 6,
          amuletPrice = 2.5,
          opensAt = ts(500).toInstant,
          rewardConfig = Optional.of(
            rewardConfig(
              extraFeaturedAssetActivityWeight = Some("0.2"),
              maxExtraFeaturedAssetActivityWeightPerAsset = Some("0.1"),
              dsoAppActivityWeight = None,
            )
          ),
        ).copy(createdAt = ts(400).toInstant)
      val round5Info = ScanRewardsReferenceStore.ActiveRoundInfo(
        5L,
        ts(400),
        Some(
          AssetActivityParams(
            extraFeaturedAssetActivityWeight = BigDecimal("0.2"),
            maxExtraFeaturedAssetActivityWeightPerAsset = BigDecimal("0.1"),
            dsoAppActivityWeight = BigDecimal("0.5"),
          )
        ),
      )
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
        _ <- sync1.archive(round5, recordTime = ts(650).toInstant)(store.multiDomainAcsStore)
        afterRound5Archived <- store.lookupActiveOpenMiningRounds(Seq(ts(650)))
      } yield {
        result.get(ts(30)) shouldBe None // before earliest archived_at
        result.get(ts(150)) shouldBe None // before earliest archived_at
        result.get(ts(220)) shouldBe None // before earliest archived_at
        result.get(ts(250)) shouldBe None // round4.opensAt before earliest archived_at
        result.get(ts(275)) shouldBe None // round4.opensAt before earliest archived_at
        result.get(ts(350)) shouldBe None // round4.opensAt before earliest archived_at
        result.get(ts(375)) shouldBe None // gap: round4 archived, round5 not yet open
        result(ts(400)) shouldBe round5Info
        result.get(ts(401)) shouldBe None // 401 was not present in request
        result(ts(450)) shouldBe round5Info // round5 open, round6 not yet open
        result(ts(550)) shouldBe round5Info // both open, lowest round selected
        afterRound5Archived shouldBe Map(
          ts(650) -> ScanRewardsReferenceStore.ActiveRoundInfo(6L, ts(500), None)
        )
      }
    }
  }

  private def ts(epochSecond: Long): CantonTimestamp =
    CantonTimestamp.ofEpochSecond(epochSecond)

  // The info of a round without a reward config
  private def roundInfo(roundNumber: Long, opensAt: CantonTimestamp) =
    ScanRewardsReferenceStore.ActiveRoundInfo(roundNumber, opensAt, None)

  private def rewardConfig(
      extraFeaturedAssetActivityWeight: Option[String],
      maxExtraFeaturedAssetActivityWeightPerAsset: Option[String],
      dsoAppActivityWeight: Option[String],
  ): RewardConfig = {
    def decimal(value: Option[String]) = value.map(new java.math.BigDecimal(_)).toJava
    new RewardConfig(
      RewardVersion.REWARDVERSION_TRAFFICBASEDAPPREWARDS,
      Optional.empty(),
      100L,
      new RelTime(36L * 3600L * 1000000L),
      new java.math.BigDecimal("0.5"),
      decimal(extraFeaturedAssetActivityWeight),
      decimal(maxExtraFeaturedAssetActivityWeightPerAsset),
      decimal(dsoAppActivityWeight),
    )
  }

  private def createFeaturedAppRight(
      store: DbScanRewardsReferenceStore,
      far: Contract[FeaturedAppRight.ContractId, FeaturedAppRight],
      recordTime: CantonTimestamp,
  ): Future[?] =
    sync1.create(far.copy(createdAt = recordTime.toInstant), recordTime = recordTime.toInstant)(
      store.multiDomainAcsStore
    )

  /** A `now` past every timeline used here, to ensure that when paired with a noRetention
    * it never gates lookupPrunableRewardRound.
    */
  private val timeAfterAllRounds: CantonTimestamp = ts(2000)
  private val noRetention: NonNegativeFiniteDuration = NonNegativeFiniteDuration.Zero

  /** Advance the record time so that lookups waiting for ingestion to reach a
    * given time are unblocked.
    */
  private def advanceRecordTime(
      store: DbScanRewardsReferenceStore,
      recordTime: CantonTimestamp,
  ): Future[?] =
    store.multiDomainAcsStore.testIngestionSink.ingestUpdateBatch(
      NonEmptyList.of(
        TreeUpdateOrOffsetCheckpoint.Checkpoint(
          new OffsetCheckpoint(
            nextOffset(),
            java.util.List.of(new SynchronizerTime(sync1.toProtoPrimitive, recordTime.toInstant)),
          )
        )
      )
    )

  /** Ingests rounds 3 to 9, with advancement (creation+archive) happening
    * according to the daml logic of AmuletRules_AdvanceOpenMiningRounds.
    * Specifically advancement happens with the diff of a tick (100) and at the openAt of latest round
    * {{{
    *   round:     3    4    5    6    7    8    9
    *   created:   75   100  200  300  400  500  600
    *   opensAt:   100  200  300  400  500  600  700
    *   archived:  300  400  500  600  700  -    -
    * }}}
    */
  private def ingestOverlappingRounds(store: DbScanRewardsReferenceStore): Future[?] = {
    def omr(round: Long, createdAt: Long, opensAt: Long) =
      openMiningRound(dsoParty, round = round, amuletPrice = 1.0, opensAt = ts(opensAt).toInstant)
        .copy(createdAt = ts(createdAt).toInstant)
    val omr3 = omr(3, createdAt = 75, opensAt = 100)
    val omr4 = omr(4, createdAt = 100, opensAt = 200)
    val omr5 = omr(5, createdAt = 200, opensAt = 300)
    val omr6 = omr(6, createdAt = 300, opensAt = 400)
    val omr7 = omr(7, createdAt = 400, opensAt = 500)
    val omr8 = omr(8, createdAt = 500, opensAt = 600)
    val omr9 = omr(9, createdAt = 600, opensAt = 700)
    val acsStore = store.multiDomainAcsStore
    for {
      _ <- initWithAcs()(acsStore)
      _ <- sync1.create(omr3, recordTime = ts(75).toInstant)(acsStore)
      _ <- sync1.create(omr4, recordTime = ts(100).toInstant)(acsStore)
      _ <- sync1.create(omr5, recordTime = ts(200).toInstant)(acsStore)
      _ <- sync1.create(omr6, recordTime = ts(300).toInstant)(acsStore)
      _ <- sync1.archive(omr3, recordTime = ts(300).toInstant)(acsStore)
      _ <- sync1.create(omr7, recordTime = ts(400).toInstant)(acsStore)
      _ <- sync1.archive(omr4, recordTime = ts(400).toInstant)(acsStore)
      _ <- sync1.create(omr8, recordTime = ts(500).toInstant)(acsStore)
      _ <- sync1.archive(omr5, recordTime = ts(500).toInstant)(acsStore)
      _ <- sync1.create(omr9, recordTime = ts(600).toInstant)(acsStore)
      _ <- sync1.archive(omr6, recordTime = ts(600).toInstant)(acsStore)
      _ <- sync1.archive(omr7, recordTime = ts(700).toInstant)(acsStore)
      _ <- advanceRecordTime(store, ts(800))
    } yield ()
  }

  /** Similar to ingestOverlappingRounds. But here the round advancement lags behind
    * by 50 (with a tick of 100)
    * {{{
    *   round:     3    4    5    6    7    8    9
    *   created:   75   100  200  350  500  650  800
    *   opensAt:   100  200  300  450  600  750  900
    *   archived:  350  500  650  800  950  -    -
    * }}}
    */
  private def ingestLaggingArchivalRounds(store: DbScanRewardsReferenceStore): Future[?] = {
    def omr(round: Long, createdAt: Long, opensAt: Long) =
      openMiningRound(dsoParty, round = round, amuletPrice = 1.0, opensAt = ts(opensAt).toInstant)
        .copy(createdAt = ts(createdAt).toInstant)
    val omr3 = omr(3, createdAt = 75, opensAt = 100)
    val omr4 = omr(4, createdAt = 100, opensAt = 200)
    val omr5 = omr(5, createdAt = 200, opensAt = 300)
    val omr6 = omr(6, createdAt = 350, opensAt = 450)
    val omr7 = omr(7, createdAt = 500, opensAt = 600)
    val omr8 = omr(8, createdAt = 650, opensAt = 750)
    val omr9 = omr(9, createdAt = 800, opensAt = 900)
    val acsStore = store.multiDomainAcsStore
    for {
      _ <- initWithAcs()(acsStore)
      _ <- sync1.create(omr3, recordTime = ts(75).toInstant)(acsStore)
      _ <- sync1.create(omr4, recordTime = ts(100).toInstant)(acsStore)
      _ <- sync1.create(omr5, recordTime = ts(200).toInstant)(acsStore)
      _ <- sync1.create(omr6, recordTime = ts(350).toInstant)(acsStore)
      _ <- sync1.archive(omr3, recordTime = ts(350).toInstant)(acsStore)
      _ <- sync1.create(omr7, recordTime = ts(500).toInstant)(acsStore)
      _ <- sync1.archive(omr4, recordTime = ts(500).toInstant)(acsStore)
      _ <- sync1.create(omr8, recordTime = ts(650).toInstant)(acsStore)
      _ <- sync1.archive(omr5, recordTime = ts(650).toInstant)(acsStore)
      _ <- sync1.create(omr9, recordTime = ts(800).toInstant)(acsStore)
      _ <- sync1.archive(omr6, recordTime = ts(800).toInstant)(acsStore)
      _ <- sync1.archive(omr7, recordTime = ts(950).toInstant)(acsStore)
      _ <- advanceRecordTime(store, ts(1100))
    } yield ()
  }

  private def svInfo(name: String, participant: ParticipantId): dsorulesCodegen.SvInfo =
    new dsorulesCodegen.SvInfo(
      name,
      new Round(0L),
      1L,
      participant.toProtoPrimitive,
    )

  private def dsoRules(
      svs: Map[String, dsorulesCodegen.SvInfo]
  ) = {
    val templateId = dsorulesCodegen.DsoRules.TEMPLATE_ID_WITH_PACKAGE_ID
    val newSynchronizerId = "new-domain-id"
    val template = new dsorulesCodegen.DsoRules(
      dsoParty.toProtoPrimitive,
      1,
      svs.asJava,
      Collections.emptyMap(),
      dsoParty.toProtoPrimitive,
      new dsorulesCodegen.DsoRulesConfig(
        1,
        1,
        new RelTime(1),
        new RelTime(1),
        new RelTime(1),
        new RelTime(1),
        new RelTime(1),
        new decentralizedsynchronizerCodegen.SynchronizerNodeConfigLimits(
          new cometbftCodegen.CometBftConfigLimits(1, 1, 1, 1, 1)
        ),
        1,
        new decentralizedsynchronizerCodegen.DsoDecentralizedSynchronizerConfig(
          Collections.emptyMap(),
          newSynchronizerId,
          newSynchronizerId,
        ),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
      ),
      Collections.emptyMap(),
      true,
    )
    contract(
      identifier = templateId,
      contractId = new dsorulesCodegen.DsoRules.ContractId(nextCid()),
      payload = template,
    )
  }

  override lazy val profile: JdbcProfile = storage.api.jdbcProfile

  protected val sync1: SynchronizerId = SynchronizerId.tryFromString("domain1::domain")

  private def mkStore(): DbScanRewardsReferenceStore = {
    val participantId = mkParticipantId("DbScanRewardsReferenceStoreTest")
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResources(
        DarResources.amulet.all ++ DarResources.dsoGovernance.all
      )
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)

    new DbScanRewardsReferenceStore(
      key = ScanRewardsReferenceStore.Key(dsoParty, sync1),
      storage,
      loggerFactory,
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
      0L,
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
