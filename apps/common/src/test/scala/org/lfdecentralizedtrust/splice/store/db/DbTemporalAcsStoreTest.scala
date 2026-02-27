package org.lfdecentralizedtrust.splice.store.db

import cats.data.NonEmptyList
import com.daml.ledger.javaapi.data.{Identifier, OffsetCheckpoint, SynchronizerTime}
import com.daml.ledger.javaapi.data.codegen.DamlRecord
import com.digitalasset.daml.lf.data.Time.Timestamp
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.AppRewardCoupon
import org.lfdecentralizedtrust.splice.environment.{DarResources, RetryProvider}
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.environment.ledger.api.TreeUpdateOrOffsetCheckpoint
import org.lfdecentralizedtrust.splice.store.StoreTestBase.testTxLogConfig
import org.lfdecentralizedtrust.splice.store.{
  HardLimit,
  Limit,
  MultiDomainAcsStore,
  StoreTestBase,
  TestTxLogEntry,
}
import org.lfdecentralizedtrust.splice.util.{Contract, ResourceTemplateDecoder, TemplateJsonDecoder}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.{ParticipantId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.config.IngestionConfig
import org.lfdecentralizedtrust.splice.store.db.AcsRowData.HasIndexColumns
import slick.jdbc.JdbcProfile


class DbTemporalAcsStoreTest extends StoreTestBase with SplicePostgresTest with AcsJdbcTypes {
  private def mkStore(
      acsArchiveConfigOpt: Option[DbMultiDomainAcsStore.AcsArchiveConfig] = Some(
        DbMultiDomainAcsStore.AcsArchiveConfig(
          "acs_store_archived_test",
          DbMultiDomainAcsStore.AcsArchiveConfig.defaultBaseColumns,
        )
      )
  ): DbMultiDomainAcsStore[TestTxLogEntry] = {
    val participantId = mkParticipantId("DbTemporalAcsStoreTest")
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResources(
        DarResources.amulet.all ++ DarResources.TokenStandard.allPackageResources.flatMap(_.all)
      )
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)

    new DbMultiDomainAcsStore(
      storage,
      "acs_store_template",
      Some("txlog_store_template"),
      None,
      storeDescriptor(0, participantId),
      Some(storeDescriptor(1, participantId)),
      loggerFactory,
      defaultContractFilter,
      testTxLogConfig,
      DomainMigrationInfo(0L, None),
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
      IngestionConfig(),
      defaultLimit = HardLimit.tryCreate(Limit.DefaultMaxPageSize),
      acsArchiveConfigOpt = acsArchiveConfigOpt,
    )
  }

  protected def c(i: Int): Contract[AppRewardCoupon.ContractId, AppRewardCoupon] =
    appRewardCoupon(i, dsoParty, contractId = validContractId(i))

  "DbTemporalAcsStore" should {

    "lookupContractByIdAsOf is visible in [created_at, archived_at) interval" in {
      implicit val store = mkStore()
      val createTime = CantonTimestamp.ofEpochSecond(100)
      val archiveTime = CantonTimestamp.ofEpochSecond(200)
      val coupon = c(1).copy(createdAt = createTime.toInstant)
      for {
        _ <- initWithAcs()(store)
        _ <- d1.create(coupon, recordTime = createTime.toInstant)(store)
        _ <- d1.archive(coupon, recordTime = archiveTime.toInstant)(store)
        resultBefore <- store.lookupContractByIdAsOf(AppRewardCoupon.COMPANION)(
          coupon.contractId,
          CantonTimestamp.ofEpochSecond(50),
          d1,
        )
        _ = resultBefore shouldBe None
        resultAtCreate <- store.lookupContractByIdAsOf(AppRewardCoupon.COMPANION)(
          coupon.contractId,
          createTime,
          d1,
        )
        _ = resultAtCreate.map(_.contract) shouldBe Some(coupon)
        resultBetween <- store.lookupContractByIdAsOf(AppRewardCoupon.COMPANION)(
          coupon.contractId,
          CantonTimestamp.ofEpochSecond(150),
          d1,
        )
        _ = resultBetween.map(_.contract) shouldBe Some(coupon)
        resultAtArchive <- store.lookupContractByIdAsOf(AppRewardCoupon.COMPANION)(
          coupon.contractId,
          archiveTime,
          d1,
        )
      } yield resultAtArchive shouldBe None
    }

    "listContractsAsOf returns correct subset of visible contracts" in {
      implicit val store = mkStore()
      val coupon1 = c(1).copy(createdAt = CantonTimestamp.ofEpochSecond(100).toInstant)
      val coupon2 = c(2).copy(createdAt = CantonTimestamp.ofEpochSecond(200).toInstant)
      val coupon3 = c(3).copy(createdAt = CantonTimestamp.ofEpochSecond(300).toInstant)
      for {
        _ <- initWithAcs()(store)
        _ <- d1.create(coupon1, recordTime = CantonTimestamp.ofEpochSecond(100).toInstant)(store)
        _ <- d1.create(coupon2, recordTime = CantonTimestamp.ofEpochSecond(200).toInstant)(store)
        _ <- d1.create(coupon3, recordTime = CantonTimestamp.ofEpochSecond(300).toInstant)(store)
        _ <- d1.archive(coupon1, recordTime = CantonTimestamp.ofEpochSecond(300).toInstant)(store)
        _ <- d1.archive(coupon3, recordTime = CantonTimestamp.ofEpochSecond(400).toInstant)(store)

        // At t=50: nothing exists yet
        resultAt50 <- store.listContractsAsOf(
          AppRewardCoupon.COMPANION,
          CantonTimestamp.ofEpochSecond(50),
          d1,
          HardLimit.tryCreate(10),
        )
        _ = resultAt50 shouldBe empty

        resultAt100 <- store.listContractsAsOf(
          AppRewardCoupon.COMPANION,
          CantonTimestamp.ofEpochSecond(100),
          d1,
          HardLimit.tryCreate(10),
        )
        _ = resultAt100.map(_.contract) shouldBe Seq(coupon1)

        resultAt250 <- store.listContractsAsOf(
          AppRewardCoupon.COMPANION,
          CantonTimestamp.ofEpochSecond(250),
          d1,
          HardLimit.tryCreate(10),
        )
        _ = resultAt250.map(_.contract).toSet shouldBe Set(coupon1, coupon2)

        resultAt300 <- store.listContractsAsOf(
          AppRewardCoupon.COMPANION,
          CantonTimestamp.ofEpochSecond(300),
          d1,
          HardLimit.tryCreate(10),
        )
        _ = resultAt300.map(_.contract).toSet shouldBe Set(coupon2, coupon3)

        resultAt400 <- store.listContractsAsOf(
          AppRewardCoupon.COMPANION,
          CantonTimestamp.ofEpochSecond(400),
          d1,
          HardLimit.tryCreate(10),
        )
        _ = resultAt400.map(_.contract) shouldBe Seq(coupon2)
      } yield succeed
    }

    "waitUntilRecordTimeReached completes when record time is reached via offset checkpoint" in {
      implicit val store = mkStore()
      val d1CheckpointTime = CantonTimestamp.ofEpochSecond(200)
      val d2CheckpointTime = CantonTimestamp.ofEpochSecond(150)
      for {
        _ <- initWithAcs()(store)
        _ = store.lastIngestedRecordTimes shouldBe empty
        // Ingest an offset checkpoint carrying synchronizer times for both domains
        _ <- store.testIngestionSink.ingestUpdateBatch(
          NonEmptyList.of(
            TreeUpdateOrOffsetCheckpoint.Checkpoint(
              new OffsetCheckpoint(
                nextOffset(),
                java.util.List.of(
                  new SynchronizerTime(d1.toProtoPrimitive, d1CheckpointTime.toInstant),
                  new SynchronizerTime(d2.toProtoPrimitive, d2CheckpointTime.toInstant),
                ),
              )
            )
          )
        )
        // Both domains should have their record times updated independently
        _ = store.lastIngestedRecordTimes.get(d1) shouldBe Some(d1CheckpointTime)
        _ = store.lastIngestedRecordTimes.get(d2) shouldBe Some(d2CheckpointTime)
        _ <- store.waitUntilRecordTimeReached(d1, d1CheckpointTime)
        _ <- store.waitUntilRecordTimeReached(d2, d2CheckpointTime)
        // d2 is at 150, so waiting for 200 on d2 should not complete
        waitD2Future = store.waitUntilRecordTimeReached(d2, CantonTimestamp.ofEpochSecond(200))
        _ = waitD2Future.isCompleted shouldBe false
        // Advance d2 via another checkpoint
        _ <- store.testIngestionSink.ingestUpdateBatch(
          NonEmptyList.of(
            TreeUpdateOrOffsetCheckpoint.Checkpoint(
              new OffsetCheckpoint(
                nextOffset(),
                java.util.List.of(
                  new SynchronizerTime(
                    d2.toProtoPrimitive,
                    CantonTimestamp.ofEpochSecond(200).toInstant,
                  )
                ),
              )
            )
          )
        )
        _ <- waitD2Future
      } yield succeed
    }

    "waitUntilRecordTimeReached complete when record time is reached via transaction ingestion" in {
      implicit val store = mkStore()
      for {
        _ <- initWithAcs()(store)
        // Ingest a transaction on d1 at t=100
        _ <- d1.create(c(1), recordTime = CantonTimestamp.ofEpochSecond(100).toInstant)(store)
        // Waiting for t=200 on d1 should not complete immediately
        waitD1Future = store.waitUntilRecordTimeReached(d1, CantonTimestamp.ofEpochSecond(200))
        _ = waitD1Future.isCompleted shouldBe false
        // Advancing d2 to t=200 should not unblock d1's wait
        _ <- d2.create(c(2), recordTime = CantonTimestamp.ofEpochSecond(200).toInstant)(store)
        _ = waitD1Future.isCompleted shouldBe false
        // Advancing d1 to t=200 unblocks d1's wait
        _ <- d1.create(c(3), recordTime = CantonTimestamp.ofEpochSecond(200).toInstant)(store)
        _ <- waitD1Future
      } yield succeed
    }

    "temporal store query methods throw when archive config is None" in {
      implicit val store = mkStore(acsArchiveConfigOpt = None)
      for {
        _ <- initWithAcs()(store)
        _ <- d1.create(c(1))(store)
      } yield {
        val lookupError = the[IllegalStateException] thrownBy {
          store.lookupContractByIdAsOf(AppRewardCoupon.COMPANION)(
            c(1).contractId,
            CantonTimestamp.Epoch,
            d1,
          )
        }
        lookupError.getMessage should include("lookupContractByIdAsOf requires an AcsArchiveConfig")

        val listError = the[IllegalStateException] thrownBy {
          store.listContractsAsOf(
            AppRewardCoupon.COMPANION,
            CantonTimestamp.Epoch,
            d1,
            HardLimit.tryCreate(10),
          )
        }
        listError.getMessage should include("listContractsAsOf requires an AcsArchiveConfig")
      }
    }
  }

  override lazy val profile: JdbcProfile = storage.api.jdbcProfile

  protected val d1: SynchronizerId = SynchronizerId.tryFromString("domain1::domain")
  protected val d2: SynchronizerId = SynchronizerId.tryFromString("domain2::domain")

  case class GenericAcsRowData(contract: Contract[?, ?]) extends AcsRowData.AcsRowDataFromContract {
    override def contractExpiresAt: Option[Timestamp] = None
    override def indexColumns: Seq[(String, IndexColumnValue[?])] = Seq.empty
  }
  object GenericAcsRowData {
    implicit val hasIndexColumns: HasIndexColumns[GenericAcsRowData] =
      new HasIndexColumns[GenericAcsRowData] {
        override def indexColumnNames: Seq[String] = Seq.empty
      }
  }

  case class GenericInterfaceRowData(
      override val interfaceId: Identifier,
      override val interfaceView: DamlRecord[?],
  ) extends AcsInterfaceViewRowData {
    override def indexColumns: Seq[(String, IndexColumnValue[?])] = Seq.empty
  }
  object GenericInterfaceRowData {
    implicit val hasIndexColumns: HasIndexColumns[GenericInterfaceRowData] =
      new HasIndexColumns[GenericInterfaceRowData] {
        override def indexColumnNames: Seq[String] = Seq.empty
      }
  }

  private val defaultContractFilter = {
    import MultiDomainAcsStore.mkFilter

    MultiDomainAcsStore.SimpleContractFilter[GenericAcsRowData](
      dsoParty,
      templateFilters = Map(
        mkFilter(AppRewardCoupon.COMPANION)(c => !c.payload.featured) { contract =>
          GenericAcsRowData(contract)
        }
      ),
    )
  }

  private def storeDescriptor(id: Int, participantId: ParticipantId) =
    StoreDescriptor(
      version = 1,
      name = "DbTemporalAcsStoreTest",
      party = dsoParty,
      participant = participantId,
      key = Map(
        "id" -> id.toString
      ),
    )

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] = {
    for {
      _ <- resetAllAppTables(storage)
    } yield ()
  }
}
