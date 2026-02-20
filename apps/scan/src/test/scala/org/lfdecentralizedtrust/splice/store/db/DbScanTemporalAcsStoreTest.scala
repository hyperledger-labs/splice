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
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.FeaturedAppRight
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.OpenMiningRound
import org.lfdecentralizedtrust.splice.codegen.java.splice.{amulet as amuletCodegen}
import org.lfdecentralizedtrust.splice.config.IngestionConfig
import org.lfdecentralizedtrust.splice.environment.{DarResources, RetryProvider}
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.scan.store.ScanStore
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanTemporalAcsStore
import org.lfdecentralizedtrust.splice.store.{HardLimit, Limit, StoreTestBase}
import org.lfdecentralizedtrust.splice.util.{ResourceTemplateDecoder, TemplateJsonDecoder}
import slick.jdbc.JdbcProfile

class DbScanTemporalAcsStoreTest
    extends StoreTestBase
    with SplicePostgresTest
    with AcsJdbcTypes {

  override lazy val profile: JdbcProfile = storage.api.jdbcProfile

  protected val d1: SynchronizerId = SynchronizerId.tryFromString("domain1::domain")

  private def mkStore(): DbScanTemporalAcsStore = {
    val participantId = mkParticipantId("DbScanTemporalAcsStoreTest")
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResources(
        DarResources.amulet.all
      )
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)

    new DbScanTemporalAcsStore(
      key = ScanStore.Key(dsoParty),
      storage,
      loggerFactory,
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
      DomainMigrationInfo(0L, None),
      participantId,
      IngestionConfig(),
      defaultLimit = HardLimit.tryCreate(Limit.DefaultMaxPageSize),
    )
  }

  "DbScanTemporalAcsStoreTest" should {

    "ingest and temporally query FeaturedAppRight" in {
      val store = mkStore()
      val createTime = CantonTimestamp.ofEpochSecond(100)
      val archiveTime = CantonTimestamp.ofEpochSecond(300)
      val far = featuredAppRight(userParty(1)).copy(createdAt = createTime.toInstant)
      for {
        _ <- initWithAcs()(store.multiDomainAcsStore)
        _ <- d1.create(far, recordTime = createTime.toInstant)(store.multiDomainAcsStore)
        _ <- d1.archive(far, recordTime = archiveTime.toInstant)(store.multiDomainAcsStore)
        // found at t=200 (between create and archive)
        resultAt200 <- store.lookupContractByIdAsOf(FeaturedAppRight.COMPANION)(
          far.contractId,
          CantonTimestamp.ofEpochSecond(200),
        )
        _ = resultAt200.map(_.contract) shouldBe Some(far)
        // not found at t=400 (after archive)
        resultAt400 <- store.lookupContractByIdAsOf(FeaturedAppRight.COMPANION)(
          far.contractId,
          CantonTimestamp.ofEpochSecond(400),
        )
        _ = resultAt400 shouldBe None
      } yield succeed
    }

    "ingest and temporally query OpenMiningRound" in {
      val store = mkStore()
      val createTime = CantonTimestamp.ofEpochSecond(100)
      val archiveTime = CantonTimestamp.ofEpochSecond(300)
      val omr = openMiningRound(dsoParty, round = 5, amuletPrice = 1.0)
        .copy(createdAt = createTime.toInstant)
      for {
        _ <- initWithAcs()(store.multiDomainAcsStore)
        _ <- d1.create(omr, recordTime = createTime.toInstant)(store.multiDomainAcsStore)
        _ <- d1.archive(omr, recordTime = archiveTime.toInstant)(store.multiDomainAcsStore)
        // found at t=200
        resultAt200 <- store.lookupContractByIdAsOf(OpenMiningRound.COMPANION)(
          omr.contractId,
          CantonTimestamp.ofEpochSecond(200),
        )
        _ = resultAt200.map(_.contract) shouldBe Some(omr)
        // not found at t=400
        resultAt400 <- store.lookupContractByIdAsOf(OpenMiningRound.COMPANION)(
          omr.contractId,
          CantonTimestamp.ofEpochSecond(400),
        )
        _ = resultAt400 shouldBe None
      } yield succeed
    }

    "ingest and temporally query AmuletRules" in {
      val store = mkStore()
      val createTime = CantonTimestamp.ofEpochSecond(100)
      val archiveTime = CantonTimestamp.ofEpochSecond(300)
      val ar = amuletRules().copy(createdAt = createTime.toInstant)
      for {
        _ <- initWithAcs()(store.multiDomainAcsStore)
        _ <- d1.create(ar, recordTime = createTime.toInstant)(store.multiDomainAcsStore)
        _ <- d1.archive(ar, recordTime = archiveTime.toInstant)(store.multiDomainAcsStore)
        // found at t=200
        resultAt200 <- store.lookupContractByIdAsOf(AmuletRules.COMPANION)(
          ar.contractId,
          CantonTimestamp.ofEpochSecond(200),
        )
        _ = resultAt200.map(_.contract) shouldBe Some(ar)
        // not found at t=400
        resultAt400 <- store.lookupContractByIdAsOf(AmuletRules.COMPANION)(
          ar.contractId,
          CantonTimestamp.ofEpochSecond(400),
        )
        _ = resultAt400 shouldBe None
      } yield succeed
    }

    "listContractsAsOf with mixed contract types" in {
      val store = mkStore()
      val far = featuredAppRight(userParty(1))
        .copy(createdAt = CantonTimestamp.ofEpochSecond(100).toInstant)
      val omr = openMiningRound(dsoParty, round = 3, amuletPrice = 1.0)
        .copy(createdAt = CantonTimestamp.ofEpochSecond(200).toInstant)
      val ar = amuletRules()
        .copy(createdAt = CantonTimestamp.ofEpochSecond(300).toInstant)
      for {
        _ <- initWithAcs()(store.multiDomainAcsStore)
        _ <- d1.create(far, recordTime = CantonTimestamp.ofEpochSecond(100).toInstant)(
          store.multiDomainAcsStore
        )
        _ <- d1.create(omr, recordTime = CantonTimestamp.ofEpochSecond(200).toInstant)(
          store.multiDomainAcsStore
        )
        _ <- d1.create(ar, recordTime = CantonTimestamp.ofEpochSecond(300).toInstant)(
          store.multiDomainAcsStore
        )
        _ <- d1.archive(far, recordTime = CantonTimestamp.ofEpochSecond(400).toInstant)(
          store.multiDomainAcsStore
        )

        // At t=150: only FeaturedAppRight
        resultAt150 <- store.listContractsAsOf(
          FeaturedAppRight.COMPANION,
          CantonTimestamp.ofEpochSecond(150),
          HardLimit.tryCreate(10),
        )
        _ = resultAt150.map(_.contract) shouldBe Seq(far)

        // At t=250: FeaturedAppRight + OpenMiningRound (no AmuletRules yet)
        farAt250 <- store.listContractsAsOf(
          FeaturedAppRight.COMPANION,
          CantonTimestamp.ofEpochSecond(250),
          HardLimit.tryCreate(10),
        )
        _ = farAt250.map(_.contract) shouldBe Seq(far)

        omrAt250 <- store.listContractsAsOf(
          OpenMiningRound.COMPANION,
          CantonTimestamp.ofEpochSecond(250),
          HardLimit.tryCreate(10),
        )
        _ = omrAt250.map(_.contract) shouldBe Seq(omr)

        arAt250 <- store.listContractsAsOf(
          AmuletRules.COMPANION,
          CantonTimestamp.ofEpochSecond(250),
          HardLimit.tryCreate(10),
        )
        _ = arAt250 shouldBe empty

        // At t=500: FeaturedAppRight is archived, OpenMiningRound + AmuletRules remain
        farAt500 <- store.listContractsAsOf(
          FeaturedAppRight.COMPANION,
          CantonTimestamp.ofEpochSecond(500),
          HardLimit.tryCreate(10),
        )
        _ = farAt500 shouldBe empty
      } yield succeed
    }

    "lookupFeaturedAppRight by provider" in {
      val store = mkStore()
      val far1 = featuredAppRight(userParty(1))
      val far2 = featuredAppRight(userParty(2))
      for {
        _ <- initWithAcs()(store.multiDomainAcsStore)
        _ <- d1.create(far1)(store.multiDomainAcsStore)
        _ <- d1.create(far2)(store.multiDomainAcsStore)
        result1 <- store.lookupFeaturedAppRight(userParty(1))
        _ = result1.map(_.contract) shouldBe Some(far1)
        result2 <- store.lookupFeaturedAppRight(userParty(2))
        _ = result2.map(_.contract) shouldBe Some(far2)
        resultNone <- store.lookupFeaturedAppRight(userParty(3))
        _ = resultNone shouldBe None
      } yield succeed
    }

    "lookupFeaturedAppRightAsOf by provider and time" in {
      val store = mkStore()
      val createTime = CantonTimestamp.ofEpochSecond(100)
      val archiveTime = CantonTimestamp.ofEpochSecond(300)
      val far = featuredAppRight(userParty(1)).copy(createdAt = createTime.toInstant)
      for {
        _ <- initWithAcs()(store.multiDomainAcsStore)
        _ <- d1.create(far, recordTime = createTime.toInstant)(store.multiDomainAcsStore)
        _ <- d1.archive(far, recordTime = archiveTime.toInstant)(store.multiDomainAcsStore)
        // found at t=200
        resultAt200 <- store.lookupFeaturedAppRightAsOf(
          userParty(1),
          CantonTimestamp.ofEpochSecond(200),
        )
        _ = resultAt200.map(_.contract) shouldBe Some(far)
        // not found at t=400
        resultAt400 <- store.lookupFeaturedAppRightAsOf(
          userParty(1),
          CantonTimestamp.ofEpochSecond(400),
        )
        _ = resultAt400 shouldBe None
      } yield succeed
    }

    "non-matching contracts are not ingested" in {
      val store = mkStore()
      val amuletContract = amulet(userParty(1), BigDecimal(100.0), 0L, BigDecimal(1.0))
      for {
        _ <- initWithAcs()(store.multiDomainAcsStore)
        _ <- d1.create(amuletContract)(store.multiDomainAcsStore)
        // the Amulet contract should not be in the temporal store since the filter rejects it
        result <- store.listContractsAsOf(
          amuletCodegen.Amulet.COMPANION,
          CantonTimestamp.now(),
          HardLimit.tryCreate(10),
        )
        _ = result shouldBe empty
      } yield succeed
    }
  }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] = {
    for {
      _ <- resetAllAppTables(storage)
    } yield ()
  }
}
