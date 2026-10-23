package org.lfdecentralizedtrust.splice.store.db

import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext, SynchronizerAlias}
import org.lfdecentralizedtrust.splice.config.IngestionConfig
import org.lfdecentralizedtrust.splice.environment.{DarResources, RetryProvider}
import org.lfdecentralizedtrust.splice.store.{
  HardLimit,
  Limit,
  TransferInputStore,
  TransferInputStoreTest,
}
import org.lfdecentralizedtrust.splice.util.{ResourceTemplateDecoder, TemplateJsonDecoder}
import org.lfdecentralizedtrust.splice.wallet.store.*
import org.lfdecentralizedtrust.splice.wallet.store.db.DbExternalPartyWalletStore

import scala.concurrent.Future

abstract class ExternalPartyWalletStoreTest
    extends TransferInputStoreTest
    with HasExecutionContext {

  "ExternalPartyWalletStore" should {

    "listAmulets" should {

      "return correct results" in {
        for {
          store <- mkStore(externalParty1)
          amulet1 = amulet(externalParty1, 11.0, 1L, 1.0)
          amulet2 = amulet(externalParty1, 12.0, 1L, 1.0)
          amulet3 = amulet(externalParty2, 13.0, 1L, 1.0)
          _ <- dummyDomain.create(amulet1, createdEventSignatories = Seq(dsoParty, externalParty1))(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(amulet2, createdEventSignatories = Seq(dsoParty, externalParty1))(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(amulet3, createdEventSignatories = Seq(dsoParty, externalParty2))(
            store.multiDomainAcsStore
          )
        } yield {
          store.listAmulets().futureValue shouldBe Seq(amulet1, amulet2)
        }
      }

    }

    "listLockedAmulets" should {

      "return correct results" in {
        for {
          store <- mkStore(externalParty1)
          lockedAmulet1 = lockedAmulet(externalParty1, 11.0, 1L, 1.0)
          lockedAmulet2 = lockedAmulet(externalParty1, 12.0, 1L, 1.0)
          lockedAmulet3 = lockedAmulet(externalParty2, 13.0, 1L, 1.0)
          _ <- dummyDomain.create(
            lockedAmulet1,
            createdEventSignatories = Seq(dsoParty, externalParty1),
          )(store.multiDomainAcsStore)
          _ <- dummyDomain.create(
            lockedAmulet2,
            createdEventSignatories = Seq(dsoParty, externalParty1),
          )(store.multiDomainAcsStore)
          _ <- dummyDomain.create(
            lockedAmulet3,
            createdEventSignatories = Seq(dsoParty, externalParty2),
          )(store.multiDomainAcsStore)
        } yield {
          store.listLockedAmulets().futureValue shouldBe Seq(lockedAmulet1, lockedAmulet2)
        }
      }

    }

    "lookupTransferCommandCounter" should {

      "return correct results" in {
        for {
          store1 <- mkStore(externalParty1)
          store2 <- mkStore(externalParty2)
          transferCommandCtr1 = transferCommandCounter(externalParty1, 0L)
          transferCommandCtr3 = transferCommandCounter(externalParty3, 0L)
          _ <- dummyDomain.create(
            transferCommandCtr1,
            createdEventSignatories = Seq(dsoParty),
            createdEventObservers = Seq(externalParty1),
          )(store1.multiDomainAcsStore)
          _ <- dummyDomain.create(
            transferCommandCtr3,
            createdEventSignatories = Seq(dsoParty),
            createdEventObservers = Seq(externalParty3),
          )(store1.multiDomainAcsStore)
        } yield {
          store1.lookupTransferCommandCounter().futureValue.value shouldBe transferCommandCtr1
          store2.lookupTransferCommandCounter().futureValue shouldBe None
        }
      }

    }

    "listSortedSvRewardCoupons" should {
      "return correct results, sorted, filtered by beneficiary" in {
        for {
          store <- mkStore(externalParty1)
          // per round n: two coupons for externalParty1 with weights n and 2n
          _ <- MonadUtil.sequentialTraverse(1 to 4)(n =>
            for {
              _ <- dummyDomain.create(
                svRewardCoupon(
                  round = n,
                  sv = validator,
                  beneficiary = externalParty1,
                  weight = n.toLong,
                ),
                createdEventSignatories = Seq(dsoParty),
                createdEventObservers = Seq(externalParty1),
              )(store.multiDomainAcsStore)
              _ <- dummyDomain.create(
                svRewardCoupon(
                  round = n,
                  sv = validator,
                  beneficiary = externalParty1,
                  weight = 2L * n,
                ),
                createdEventSignatories = Seq(dsoParty),
                createdEventObservers = Seq(externalParty1),
              )(store.multiDomainAcsStore)
            } yield ()
          )
          // a coupon for a DIFFERENT beneficiary — must NOT be returned (the #7141 filter point)
          _ <- dummyDomain.create(
            svRewardCoupon(round = 2, sv = validator, beneficiary = externalParty2, weight = 99L),
            createdEventSignatories = Seq(dsoParty),
            createdEventObservers = Seq(externalParty2),
          )(store.multiDomainAcsStore)
        } yield {
          store.listSortedSvRewardCoupons(Map.empty).futureValue shouldBe empty
          val rounds = (2 to 4).map(n => issuingMiningRound(dsoParty, n.toLong))
          store
            .listSortedSvRewardCoupons(rounds.map(r => r.payload.round -> r.payload).toMap)
            .futureValue
            .map(_._1.payload.weight.toLong) should contain theSameElementsInOrderAs Seq(
            4L, 2L, // round 2 (99L for externalParty2 excluded)
            6L, 3L, // round 3
            8L, 4L, // round 4
          )
        }
      }
    }
  }

  private lazy val externalParty1 = userParty(1)
  private lazy val externalParty2 = userParty(2)
  private lazy val externalParty3 = userParty(3)
  private lazy val validator = mkPartyId(s"validator")

  protected def storeKey(externalParty: PartyId): ExternalPartyWalletStore.Key =
    ExternalPartyWalletStore.Key(
      dsoParty = dsoParty,
      validatorParty = validator,
      externalParty = externalParty,
    )

  protected def mkStore(
      externalParty: PartyId,
      migrationId: Long = domainMigrationId,
  ): Future[ExternalPartyWalletStore]

  override def mkTransferInputStore(partyId: PartyId): Future[TransferInputStore] = mkStore(partyId)

  protected lazy val acsOffset: Long = nextOffset()
  protected lazy val domain: String = dummyDomain.toProtoPrimitive
  protected lazy val synchronizerAlias: SynchronizerAlias = SynchronizerAlias.tryCreate(domain)
}

class DbExternalPartyWalletStoreTest
    extends ExternalPartyWalletStoreTest
    with HasActorSystem
    with SplicePostgresTest
    with AcsJdbcTypes
    with AcsTables {

  override protected def mkStore(
      externalParty: PartyId,
      migrationId: Long = domainMigrationId,
  ): Future[DbExternalPartyWalletStore] = {
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResources(
        DarResources.amulet.all
//          DarResources.wallet.all ++
//          DarResources.amuletNameService.all
      )
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)

    for {
      store <- DbExternalPartyWalletStore.create(
        key = storeKey(externalParty),
        storage = storage,
        loggerFactory = loggerFactory,
        retryProvider =
          RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
        migrationId,
        participantId = mkParticipantId("ExternalPartyWalletStoreTest"),
        IngestionConfig(),
        defaultLimit = HardLimit.tryCreate(Limit.DefaultMaxPageSize),
      )
      _ <- store.multiDomainAcsStore.testIngestionSink.initialize()
      _ <- store.multiDomainAcsStore.testIngestionSink
        .ingestAcs(acsOffset, Seq.empty, Seq.empty, Seq.empty)
      _ <- store.domains.ingestionSink.ingestConnectedDomains(
        Map(synchronizerAlias -> dummyDomain)
      )
    } yield store
  }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] = resetAllAppTables(storage)
}
