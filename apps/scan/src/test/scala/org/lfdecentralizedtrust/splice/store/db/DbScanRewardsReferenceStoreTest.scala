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
import org.lfdecentralizedtrust.splice.scan.store.ScanStore
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanRewardsReferenceStore
import org.lfdecentralizedtrust.splice.store.{HardLimit, Limit, StoreTestBase}
import org.lfdecentralizedtrust.splice.util.{ResourceTemplateDecoder, TemplateJsonDecoder}
import slick.jdbc.JdbcProfile

class DbScanRewardsReferenceStoreTest
    extends StoreTestBase
    with SplicePostgresTest
    with AcsJdbcTypes {

  "DbScanRewardsReferenceStore" should {

    "lookupFeaturedAppRightsAsOf returns all active FeaturedAppRights at given time" in {
      val store = mkStore()
      val far1 = featuredAppRight(userParty(1))
        .copy(createdAt = CantonTimestamp.ofEpochSecond(100).toInstant)
      val far2 = featuredAppRight(userParty(2))
        .copy(createdAt = CantonTimestamp.ofEpochSecond(200).toInstant)
      for {
        _ <- initWithAcs()(store.multiDomainAcsStore)
        _ <- d1.create(far1, recordTime = CantonTimestamp.ofEpochSecond(100).toInstant)(
          store.multiDomainAcsStore
        )
        _ <- d1.create(far2, recordTime = CantonTimestamp.ofEpochSecond(200).toInstant)(
          store.multiDomainAcsStore
        )
        _ <- d1.archive(far1, recordTime = CantonTimestamp.ofEpochSecond(300).toInstant)(
          store.multiDomainAcsStore
        )
        farBefore <- store.lookupFeaturedAppRightsAsOf(CantonTimestamp.ofEpochSecond(50))
        _ = farBefore shouldBe empty
        resultAt100 <- store.lookupFeaturedAppRightsAsOf(CantonTimestamp.ofEpochSecond(100))
        _ = resultAt100.map(_.contract) shouldBe Seq(far1)
        resultAt250 <- store.lookupFeaturedAppRightsAsOf(CantonTimestamp.ofEpochSecond(250))
        _ = resultAt250.map(_.contract) should contain theSameElementsAs Seq(far1, far2)
        resultAt300 <- store.lookupFeaturedAppRightsAsOf(CantonTimestamp.ofEpochSecond(300))
        _ = resultAt300.map(_.contract) shouldBe Seq(far2)
      } yield succeed
    }

    "lookupOpenMiningRoundsAsOf returns all active OpenMiningRounds at given time" in {
      val store = mkStore()
      val omr1 = openMiningRound(dsoParty, round = 3, amuletPrice = 1.0)
        .copy(createdAt = CantonTimestamp.ofEpochSecond(100).toInstant)
      val omr2 = openMiningRound(dsoParty, round = 4, amuletPrice = 1.5)
        .copy(createdAt = CantonTimestamp.ofEpochSecond(200).toInstant)
      for {
        _ <- initWithAcs()(store.multiDomainAcsStore)
        _ <- d1.create(omr1, recordTime = CantonTimestamp.ofEpochSecond(100).toInstant)(
          store.multiDomainAcsStore
        )
        _ <- d1.create(omr2, recordTime = CantonTimestamp.ofEpochSecond(200).toInstant)(
          store.multiDomainAcsStore
        )
        _ <- d1.archive(omr1, recordTime = CantonTimestamp.ofEpochSecond(300).toInstant)(
          store.multiDomainAcsStore
        )
        omrBefore <- store.lookupOpenMiningRoundsAsOf(CantonTimestamp.ofEpochSecond(50))
        _ = omrBefore shouldBe empty
        resultAt100 <- store.lookupOpenMiningRoundsAsOf(CantonTimestamp.ofEpochSecond(100))
        _ = resultAt100.map(_.contract) shouldBe Seq(omr1)
        resultAt250 <- store.lookupOpenMiningRoundsAsOf(CantonTimestamp.ofEpochSecond(250))
        _ = resultAt250.map(_.contract) should contain theSameElementsAs Seq(omr1, omr2)
        resultAt300 <- store.lookupOpenMiningRoundsAsOf(CantonTimestamp.ofEpochSecond(300))
        _ = resultAt300.map(_.contract) shouldBe Seq(omr2)
      } yield succeed
    }

    "lookupAmuletRulesAsOf returns AmuletRules active at given time" in {
      val store = mkStore()
      val createTime = CantonTimestamp.ofEpochSecond(100)
      val archiveTime = CantonTimestamp.ofEpochSecond(300)
      val ar = amuletRules().copy(createdAt = createTime.toInstant)
      for {
        _ <- initWithAcs()(store.multiDomainAcsStore)
        _ <- d1.create(ar, recordTime = createTime.toInstant)(store.multiDomainAcsStore)
        _ <- d1.archive(ar, recordTime = archiveTime.toInstant)(store.multiDomainAcsStore)
        resultAt50 <- store.lookupAmuletRulesAsOf(CantonTimestamp.ofEpochSecond(50))
        _ = resultAt50 shouldBe None
        resultAt200 <- store.lookupAmuletRulesAsOf(CantonTimestamp.ofEpochSecond(200))
        _ = resultAt200.map(_.contract) shouldBe Some(ar)
        resultAt300 <- store.lookupAmuletRulesAsOf(CantonTimestamp.ofEpochSecond(300))
        _ = resultAt300 shouldBe None
      } yield succeed
    }
  }

  override lazy val profile: JdbcProfile = storage.api.jdbcProfile

  protected val d1: SynchronizerId = SynchronizerId.tryFromString("domain1::domain")

  private def mkStore(): DbScanRewardsReferenceStore = {
    val participantId = mkParticipantId("DbScanRewardsReferenceStoreTest")
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResources(
        DarResources.amulet.all
      )
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)

    new DbScanRewardsReferenceStore(
      key = ScanStore.Key(dsoParty),
      storage,
      loggerFactory,
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
      DomainMigrationInfo(0L, None),
      participantId,
      d1,
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
