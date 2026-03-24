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
  TcsStore,
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
import org.lfdecentralizedtrust.splice.store.db.AcsInterfaceViewRowData
import slick.jdbc.JdbcProfile

class DbTcsStoreTest extends StoreTestBase with SplicePostgresTest with AcsJdbcTypes {
  private def mkStore(synchronizerId: SynchronizerId): DbTcsStore = {
    val participantId = mkParticipantId("DbTcsStoreTest")
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResources(
        DarResources.amulet.all ++ DarResources.TokenStandard.allPackageResources.flatMap(_.all)
      )
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)

    val acsStore = new DbMultiDomainAcsStore(
      storage,
      "acs_store_template",
      Some("txlog_store_template"),
      None,
      storeDescriptor(0, participantId, synchronizerId),
      Some(storeDescriptor(1, participantId, synchronizerId)),
      loggerFactory,
      contractFilter(synchronizerId),
      testTxLogConfig,
      DomainMigrationInfo(0L, None),
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
      IngestionConfig(),
      defaultLimit = HardLimit.tryCreate(Limit.DefaultMaxPageSize),
      acsArchiveConfigOpt = Some(
        AcsArchiveConfig(
          archiveTableName,
          AcsArchiveConfig.defaultBaseColumns,
        )
      ),
    )
    new DbTcsStore(
      acsStore,
      descriptor => SynchronizerId.tryFromString(descriptor.key("synchronizerId")),
    )
  }

  protected def c(i: Int): Contract[AppRewardCoupon.ContractId, AppRewardCoupon] =
    appRewardCoupon(i, dsoParty, contractId = validContractId(i))

  "DbTcsStore" should {

    "lookupContractByIdAsOf is visible in [created_at, archived_at) interval" in {
      val store = mkStore(sync1)
      val createTime = CantonTimestamp.ofEpochSecond(100)
      val archiveTime = CantonTimestamp.ofEpochSecond(200)
      val coupon = c(1).copy(createdAt = createTime.toInstant)
      for {
        _ <- initWithAcs()(store.acsStore)
        _ <- sync1.create(coupon, recordTime = createTime.toInstant)(store.acsStore)
        _ <- sync1.archive(coupon, recordTime = archiveTime.toInstant)(store.acsStore)
        resultBefore <- store.lookupContractByIdAsOf(AppRewardCoupon.COMPANION)(
          coupon.contractId,
          CantonTimestamp.ofEpochSecond(50),
        )
        _ = resultBefore shouldBe None
        resultAtCreate <- store.lookupContractByIdAsOf(AppRewardCoupon.COMPANION)(
          coupon.contractId,
          createTime,
        )
        _ = resultAtCreate.map(_.contract) shouldBe Some(coupon)
        resultBetween <- store.lookupContractByIdAsOf(AppRewardCoupon.COMPANION)(
          coupon.contractId,
          CantonTimestamp.ofEpochSecond(150),
        )
        _ = resultBetween.map(_.contract) shouldBe Some(coupon)
        resultAtArchive <- store.lookupContractByIdAsOf(AppRewardCoupon.COMPANION)(
          coupon.contractId,
          archiveTime,
        )
      } yield resultAtArchive shouldBe None
    }

    "listAllContractsAsOf and listAllContractsActiveWithin return correct contracts" in {
      val store = mkStore(sync1)
      val coupon1 = c(1).copy(createdAt = CantonTimestamp.ofEpochSecond(100).toInstant)
      val coupon2 = c(2).copy(createdAt = CantonTimestamp.ofEpochSecond(200).toInstant)
      val coupon3 = c(3).copy(createdAt = CantonTimestamp.ofEpochSecond(300).toInstant)
      for {
        _ <- initWithAcs()(store.acsStore)
        _ <- sync1.create(coupon1, recordTime = CantonTimestamp.ofEpochSecond(100).toInstant)(
          store.acsStore
        )
        _ <- sync1.create(coupon2, recordTime = CantonTimestamp.ofEpochSecond(200).toInstant)(
          store.acsStore
        )
        _ <- sync1.create(coupon3, recordTime = CantonTimestamp.ofEpochSecond(300).toInstant)(
          store.acsStore
        )
        _ <- sync1.archive(coupon1, recordTime = CantonTimestamp.ofEpochSecond(300).toInstant)(
          store.acsStore
        )
        _ <- sync1.archive(coupon3, recordTime = CantonTimestamp.ofEpochSecond(400).toInstant)(
          store.acsStore
        )

        // listAllContractsAsOf point-in-time checks
        resultAt50 <- store.listAllContractsAsOf(
          AppRewardCoupon.COMPANION,
          CantonTimestamp.ofEpochSecond(50),
        )
        _ = resultAt50 shouldBe empty

        resultAt100 <- store.listAllContractsAsOf(
          AppRewardCoupon.COMPANION,
          CantonTimestamp.ofEpochSecond(100),
        )
        _ = resultAt100.map(_.contract) shouldBe Seq(coupon1)

        resultAt250 <- store.listAllContractsAsOf(
          AppRewardCoupon.COMPANION,
          CantonTimestamp.ofEpochSecond(250),
        )
        _ = resultAt250.map(_.contract).toSet shouldBe Set(coupon1, coupon2)

        resultAt300 <- store.listAllContractsAsOf(
          AppRewardCoupon.COMPANION,
          CantonTimestamp.ofEpochSecond(300),
        )
        _ = resultAt300.map(_.contract).toSet shouldBe Set(coupon2, coupon3)

        resultAt400 <- store.listAllContractsAsOf(
          AppRewardCoupon.COMPANION,
          CantonTimestamp.ofEpochSecond(400),
        )
        _ = resultAt400.map(_.contract) shouldBe Seq(coupon2)

        // listAllContractsActiveWithin: [100, 400] should return all 3 contracts
        resultRange_100_400 <- store.listAllContractsActiveWithin(
          AppRewardCoupon.COMPANION,
          CantonTimestamp.ofEpochSecond(100),
          CantonTimestamp.ofEpochSecond(400),
        )
        _ = resultRange_100_400
          .map(_.contractWithState.contract)
          .toSet shouldBe Set(coupon1, coupon2, coupon3)

        // And we should be able to extract results for each asOf from its result
        // contractsActiveAsOf on the range result should match listAllContractsAsOf
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

        // Also confirm listAllContractsActiveWithin for various ranges
        resultRange_100_200 <- store.listAllContractsActiveWithin(
          AppRewardCoupon.COMPANION,
          CantonTimestamp.ofEpochSecond(100),
          CantonTimestamp.ofEpochSecond(200),
        )
        _ = resultRange_100_200
          .map(_.contractWithState.contract)
          .toSet shouldBe Set(coupon1, coupon2)

        resultRange_100_300 <- store.listAllContractsActiveWithin(
          AppRewardCoupon.COMPANION,
          CantonTimestamp.ofEpochSecond(100),
          CantonTimestamp.ofEpochSecond(300),
        )
        _ = resultRange_100_300
          .map(_.contractWithState.contract)
          .toSet shouldBe Set(coupon1, coupon2, coupon3)

        resultRange_200_300 <- store.listAllContractsActiveWithin(
          AppRewardCoupon.COMPANION,
          CantonTimestamp.ofEpochSecond(200),
          CantonTimestamp.ofEpochSecond(300),
        )
        _ = resultRange_200_300
          .map(_.contractWithState.contract)
          .toSet shouldBe Set(coupon1, coupon2, coupon3)

        resultRange_300_400 <- store.listAllContractsActiveWithin(
          AppRewardCoupon.COMPANION,
          CantonTimestamp.ofEpochSecond(300),
          CantonTimestamp.ofEpochSecond(400),
        )
        _ = resultRange_300_400
          .map(_.contractWithState.contract)
          .toSet shouldBe Set(coupon2, coupon3)
      } yield succeed
    }

    "waitUntilRecordTimeReached completes when record time is reached via offset checkpoint" in {
      val store = mkStore(sync1).acsStore
      val sync1CheckpointTime = CantonTimestamp.ofEpochSecond(200)
      val sync2CheckpointTime = CantonTimestamp.ofEpochSecond(150)
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
                  new SynchronizerTime(sync1.toProtoPrimitive, sync1CheckpointTime.toInstant),
                  new SynchronizerTime(sync2.toProtoPrimitive, sync2CheckpointTime.toInstant),
                ),
              )
            )
          )
        )
        // Both domains should have their record times updated independently
        _ = store.lastIngestedRecordTimes.get(sync1) shouldBe Some(sync1CheckpointTime)
        _ = store.lastIngestedRecordTimes.get(sync2) shouldBe Some(sync2CheckpointTime)
        _ <- store.waitUntilRecordTimeReached(sync1, sync1CheckpointTime)
        _ <- store.waitUntilRecordTimeReached(sync2, sync2CheckpointTime)
        // sync2 is at 150, so waiting for 200 on sync2should not complete
        waitSync2Future = store.waitUntilRecordTimeReached(
          sync2,
          CantonTimestamp.ofEpochSecond(200),
        )
        _ = waitSync2Future.isCompleted shouldBe false
        // Advance sync2via another checkpoint
        _ <- store.testIngestionSink.ingestUpdateBatch(
          NonEmptyList.of(
            TreeUpdateOrOffsetCheckpoint.Checkpoint(
              new OffsetCheckpoint(
                nextOffset(),
                java.util.List.of(
                  new SynchronizerTime(
                    sync2.toProtoPrimitive,
                    CantonTimestamp.ofEpochSecond(200).toInstant,
                  )
                ),
              )
            )
          )
        )
        _ <- waitSync2Future
      } yield succeed
    }

    "waitUntilRecordTimeReached complete when record time is reached via transaction ingestion" in {
      val store = mkStore(sync1).acsStore
      for {
        _ <- initWithAcs()(store)
        // Ingest a transaction on sync1at t=100
        _ <- sync1.create(c(1), recordTime = CantonTimestamp.ofEpochSecond(100).toInstant)(store)
        // Waiting for t=200 on sync1should not complete immediately
        waitSync1Future = store.waitUntilRecordTimeReached(
          sync1,
          CantonTimestamp.ofEpochSecond(200),
        )
        _ = waitSync1Future.isCompleted shouldBe false
        // Advancing sync2 to t=200 should not unblock sync1's wait
        _ <- sync2.create(c(2), recordTime = CantonTimestamp.ofEpochSecond(200).toInstant)(store)
        _ = waitSync1Future.isCompleted shouldBe false
        // Advancing sync1 to t=200 unblocks sync1's wait
        _ <- sync1.create(c(3), recordTime = CantonTimestamp.ofEpochSecond(200).toInstant)(store)
        _ <- waitSync1Future
      } yield succeed
    }

    "waitUntilRecordTimeReached blocks on a synchronizer with no ingested contracts" in {
      val store = mkStore(sync1).acsStore
      for {
        _ <- initWithAcs()(store)
        // No contracts ingested for sync1yet, so waiting should block
        waitFuture = store.waitUntilRecordTimeReached(sync1, CantonTimestamp.ofEpochSecond(100))
        _ = waitFuture.isCompleted shouldBe false
        // An offset checkpoint advancing sync1to t=100 should unblock the wait
        _ <- store.testIngestionSink.ingestUpdateBatch(
          NonEmptyList.of(
            TreeUpdateOrOffsetCheckpoint.Checkpoint(
              new OffsetCheckpoint(
                nextOffset(),
                java.util.List.of(
                  new SynchronizerTime(
                    sync1.toProtoPrimitive,
                    CantonTimestamp.ofEpochSecond(100).toInstant,
                  )
                ),
              )
            )
          )
        )
        _ <- waitFuture
        // Also verify sync2with no ingested contracts, unblocked via transaction ingestion
        waitSync2Future = store.waitUntilRecordTimeReached(
          sync2,
          CantonTimestamp.ofEpochSecond(100),
        )
        _ = waitSync2Future.isCompleted shouldBe false
        _ <- sync2.create(c(1), recordTime = CantonTimestamp.ofEpochSecond(100).toInstant)(store)
        _ <- waitSync2Future
      } yield succeed
    }

  }

  override lazy val profile: JdbcProfile = storage.api.jdbcProfile

  protected val sync1: SynchronizerId = SynchronizerId.tryFromString("domain1::domain")
  protected val sync2: SynchronizerId = SynchronizerId.tryFromString("domain2::domain")

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

  private def contractFilter(synchronizerId: SynchronizerId) = {
    import MultiDomainAcsStore.mkFilter

    MultiDomainAcsStore
      .SimpleContractFilter[GenericAcsRowData, AcsInterfaceViewRowData.NoInterfacesIngested](
        dsoParty,
        templateFilters = Map(
          mkFilter(AppRewardCoupon.COMPANION)(c => !c.payload.featured) { contract =>
            GenericAcsRowData(contract)
          }
        ),
        interfaceFilters = Map.empty,
        synchronizerFilter = Some(synchronizerId),
      )
  }

  private def storeDescriptor(
      id: Int,
      participantId: ParticipantId,
      synchronizerId: SynchronizerId,
  ) =
    StoreDescriptor(
      version = 1,
      name = "DbTcsStoreTest",
      party = dsoParty,
      participant = participantId,
      key = Map(
        "id" -> id.toString,
        "synchronizerId" -> synchronizerId.toProtoPrimitive,
      ),
    )

  private val archiveTableName = "acs_store_archived_test"

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] = {
    import storage.api.jdbcProfile.api.*
    for {
      _ <- resetAllAppTables(storage)
      _ <- storage.queryAndUpdate(
        sqlu"""create table if not exists #$archiveTableName(
            like acs_store_template including all,
            foreign key (store_id) references store_descriptors(id),
            archived_at bigint not null
          )""",
        "createArchiveTestTable",
      )
      _ <- storage.queryAndUpdate(
        sqlu"truncate #$archiveTableName",
        "truncateArchiveTestTable",
      )
    } yield ()
  }
}
