// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.config.IngestionConfig
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
  }

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
