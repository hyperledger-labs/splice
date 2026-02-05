package org.lfdecentralizedtrust.splice.store.db

import com.daml.metrics.api.noop.NoOpMetricsFactory
import org.lfdecentralizedtrust.splice.codegen.java.splice.{
  amulet as amuletCodegen,
  amuletrules as amuletRulesCodegen,
  ans as ansCodegen,
  round as roundCodegen,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.{
  buytrafficrequest as trafficRequestCodegen,
  install as installCodegen,
  payment as paymentCodegen,
  subscriptions as subsCodegen,
  transferoffer as transferOffersCodegen,
  transferpreapproval as preapprovalCodegen,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.install
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.install.WalletAppInstall_CreateBuyTrafficRequest
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.transferpreapproval.TransferPreapprovalProposal
import org.lfdecentralizedtrust.splice.wallet.store.{
  BalanceChangeTxLogEntry,
  BuyTrafficRequestStatusRejected,
  BuyTrafficRequestTxLogEntry,
  DevelopmentFundCouponArchivedTxLogEntry,
  DevelopmentFundCouponStatusClaimed,
  DevelopmentFundCouponStatusExpired,
  DevelopmentFundCouponStatusRejected,
  DevelopmentFundCouponStatusWithdrawn,
  TransferOfferStatusAccepted,
  TransferOfferTxLogEntry,
  TxLogEntry,
  UserWalletStore,
}
import org.lfdecentralizedtrust.splice.wallet.store.db.DbUserWalletStore
import org.lfdecentralizedtrust.splice.environment.{DarResources, RetryProvider}
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.store.{
  Limit,
  PageLimit,
  TransferInputStore,
  TransferInputStoreTest,
}
import org.lfdecentralizedtrust.splice.util.{
  Contract,
  ContractWithState,
  ResourceTemplateDecoder,
  TemplateJsonDecoder,
}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.{Member, PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext, SynchronizerAlias}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.DevelopmentFundCoupon
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.TransferInput
import org.lfdecentralizedtrust.splice.config.IngestionConfig
import org.lfdecentralizedtrust.splice.util.SpliceUtil.damlDecimal
import org.scalatest.{Assertion, Succeeded}
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks.forAll as scForAll

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.{Optional, UUID}
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

abstract class UserWalletStoreTest extends TransferInputStoreTest with HasExecutionContext {

  "UserWalletStore" should {

    "lookupInstall" should {

      "return the install of the user" in {
        for {
          store <- mkStore(user1)
          unwantedContract = walletInstall(user2)
          wantedContract = walletInstall(user1)
          _ <- dummyDomain.create(unwantedContract, createdEventSignatories = Seq(user2))(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(wantedContract, createdEventSignatories = Seq(user1))(
            store.multiDomainAcsStore
          )
        } yield {
          store.getInstall().futureValue.contractId should be(
            wantedContract.contractId
          )
        }
      }

      "return the install of the user when using multiple stores" in {
        for {
          store1 <- mkStore(user1)
          store2 <- mkStore(user2)
          allAcsStores = Seq(store1.multiDomainAcsStore, store2.multiDomainAcsStore)
          install1 = walletInstall(user1)
          install2 = walletInstall(user2)
          _ <- dummyDomain.createMulti(install1, createdEventSignatories = Seq(user1))(allAcsStores)
          _ <- dummyDomain.createMulti(install2, createdEventSignatories = Seq(user2))(allAcsStores)
        } yield {
          store1.getInstall().futureValue.contractId should be(
            install1.contractId
          )
          store2.getInstall().futureValue.contractId should be(
            install2.contractId
          )
        }
      }

    }

    "listExpiredTransferOffers" should {

      "return correct results" in {
        for {
          store <- mkStore(user1)
          offer1 = transferOffer(user1, user2, 10.0, paymentCodegen.Unit.AMULETUNIT, time(1))
          offer3 = transferOffer(user1, user2, 20.0, paymentCodegen.Unit.USDUNIT, time(3))
          _ <- dummyDomain.create(offer1, createdEventSignatories = Seq(user1))(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(offer3, createdEventSignatories = Seq(user1))(
            store.multiDomainAcsStore
          )
        } yield {
          def cidsAt(t: CantonTimestamp) = store
            .listExpiredTransferOffers(t, PageLimit.tryCreate(10))(TraceContext.empty)
            .futureValue
            .map(_.contract.contractId)

          cidsAt(time(0)) should be(empty)
          cidsAt(time(1)) should be(empty)
          cidsAt(time(2)) should contain theSameElementsAs Seq(offer1.contractId)
          cidsAt(time(3)) should contain theSameElementsAs Seq(offer1.contractId)
          cidsAt(time(4)) should contain theSameElementsAs Seq(
            offer1.contractId,
            offer3.contractId,
          )
        }
      }

    }

    "listExpiredAcceptedTransferOffers" should {

      "return correct results" in {
        for {
          store <- mkStore(user1)
          offer1 = acceptedTransferOffer(
            user1,
            user2,
            10.0,
            paymentCodegen.Unit.AMULETUNIT,
            time(1),
          )
          offer3 = acceptedTransferOffer(user1, user2, 20.0, paymentCodegen.Unit.USDUNIT, time(3))
          _ <- dummyDomain.create(offer1, createdEventSignatories = Seq(user1))(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(offer3, createdEventSignatories = Seq(user1))(
            store.multiDomainAcsStore
          )
        } yield {
          def cidsAt(t: CantonTimestamp) = store
            .listExpiredAcceptedTransferOffers(t, PageLimit.tryCreate(10))(TraceContext.empty)
            .futureValue
            .map(_.contract.contractId)

          cidsAt(time(0)) should be(empty)
          cidsAt(time(1)) should be(empty)
          cidsAt(time(2)) should contain theSameElementsAs Seq(offer1.contractId)
          cidsAt(time(3)) should contain theSameElementsAs Seq(offer1.contractId)
          cidsAt(time(4)) should contain theSameElementsAs Seq(
            offer1.contractId,
            offer3.contractId,
          )
        }
      }

    }

    "getLatestTransferOfferEventByTrackingId" should {

      "return None when missing" in {
        for {
          store <- mkStore(user1)
          result <- store.getLatestTransferOfferEventByTrackingId("nope")
        } yield {
          result.offset should be(acsOffset)
          result.value should be(None)
        }
      }

      "return None after ingesting unrelated entries only" in {
        def mkUnrelatedEntry()(offset: Long) = {
          mintTransaction(user1, 11.0, 1L, 1.0)(offset)
        }
        for {
          store <- mkStore(user1)
          _ <- dummyDomain.ingest(mkUnrelatedEntry())(store.multiDomainAcsStore)
          result <- store.getLatestTransferOfferEventByTrackingId("nope")
        } yield {
          result.value should be(None)
        }
      }

      "return entry stored by multiple stores" in {
        def mkSharedTx()(offset: Long) = {
          mkTransferOfferTx(offset, "trackingId", user1, user2, nextCid())
        }

        for {
          store1 <- mkStore(user1, migrationId = 1L)
          store2 <- mkStore(user2, migrationId = 2L)
          allStores = List(store1.multiDomainAcsStore, store2.multiDomainAcsStore)
          _ <- dummyDomain.ingestMulti(mkSharedTx())(allStores)
          result1 <- store1.getLatestTransferOfferEventByTrackingId("trackingId")
          result2 <- store2.getLatestTransferOfferEventByTrackingId("trackingId")
        } yield {
          result1.value.value.trackingId should be("trackingId")
          result2.value.value.trackingId should be("trackingId")
        }
      }

      "return the latest entry" in {
        val goodTransferOfferCid = nextCid()
        val goodAcceptedTransferOfferCid = nextCid()
        val badTransferOfferCid = nextCid()

        def mkTransferOffer(trackingId: String, cid: String)(offset: Long) = {
          mkTransferOfferTx(offset, trackingId, user1, user2, cid)
        }
        def mkAcceptTransfer(trackingId: String, cid: String)(offset: Long) = {
          mkAcceptTransferTx(offset, trackingId, user1, user2, cid)
        }

        for {
          store <- mkStore(user1)
          _ <- dummyDomain.ingest(mkTransferOffer("good", goodTransferOfferCid))(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.ingest(mkTransferOffer("bad", badTransferOfferCid))(
            store.multiDomainAcsStore
          )
          acceptedTree <- dummyDomain.ingest(
            mkAcceptTransfer("good", goodAcceptedTransferOfferCid)
          )(
            store.multiDomainAcsStore
          )
          result <- store.getLatestTransferOfferEventByTrackingId("good")
        } yield {
          result.offset should be(acceptedTree.getOffset)
          result.value.map(_.status) should be(
            Some(
              TransferOfferTxLogEntry.Status.Accepted(
                TransferOfferStatusAccepted(
                  goodAcceptedTransferOfferCid,
                  acceptedTree.getUpdateId,
                )
              )
            )
          )
        }
      }

    }

    "getLatestBuyTrafficRequestEventByTrackingId" should {

      "return None when missing" in {
        for {
          store <- mkStore(user1)
          result <- store.getLatestBuyTrafficRequestEventByTrackingId("nope")
        } yield {
          result.offset should be(acsOffset)
          result.value should be(None)
        }
      }

      "return None after ingesting unrelated entries only" in {
        def mkUnrelatedEntry()(offset: Long) = {
          mintTransaction(user1, 11.0, 1L, 1.0)(offset)
        }

        for {
          store <- mkStore(user1)
          _ <- dummyDomain.ingest(mkUnrelatedEntry())(store.multiDomainAcsStore)
          result <- store.getLatestBuyTrafficRequestEventByTrackingId("nope")
        } yield {
          result.value should be(None)
        }
      }

      def mkBuyTrafficRequest(
          trackingId: String,
          cid: trafficRequestCodegen.BuyTrafficRequest.ContractId,
      )(offset: Long) = {
        mkBuyTrafficRequestTx(offset, trackingId, user1, participantId, dummyDomain, cid)
      }
      def mkCancelTrafficRequest(trackingId: String, reason: String, cid: String)(
          offset: Long
      ) = {
        mkCancelTrafficRequestTx(offset, trackingId, user1, reason, cid)
      }

      "return the latest entry" in {
        val trafficRequestCid = nextCid()
        for {
          store <- mkStore(user1)
          _ <- dummyDomain.ingest(
            mkBuyTrafficRequest(
              "trackingId",
              new trafficRequestCodegen.BuyTrafficRequest.ContractId(trafficRequestCid),
            )
          )(
            store.multiDomainAcsStore
          )
          cancelledTree <- dummyDomain.ingest(
            mkCancelTrafficRequest("trackingId", "just because", trafficRequestCid)
          )(
            store.multiDomainAcsStore
          )
          result <- store.getLatestBuyTrafficRequestEventByTrackingId("trackingId")
        } yield {
          result.offset should be(cancelledTree.getOffset)
          result.value.map(_.status) should be(
            Some(
              BuyTrafficRequestTxLogEntry.Status.Rejected(
                BuyTrafficRequestStatusRejected("just because")
              )
            )
          )
        }
      }

      "return the entry even if it's on a different migration id" in {
        val trafficRequestCid = nextCid()
        for {
          store1 <- mkStore(user1, migrationId = 1L)
          store2 <- mkStore(user1, migrationId = 2L)
          _ <- dummyDomain.ingest(
            mkBuyTrafficRequest(
              "trackingId",
              new trafficRequestCodegen.BuyTrafficRequest.ContractId(trafficRequestCid),
            )
          )(
            store1.multiDomainAcsStore
          )
          cancelledTree <- dummyDomain.ingest(
            mkCancelTrafficRequest("trackingId", "just because", trafficRequestCid)
          )(
            store1.multiDomainAcsStore
          )
          result1 <- store1.getLatestBuyTrafficRequestEventByTrackingId("trackingId")
          result2 <- store2.getLatestBuyTrafficRequestEventByTrackingId("trackingId")
        } yield {
          result1.offset shouldBe cancelledTree.getOffset
          result2.offset shouldBe 0L
          result2.value.map(_.status) should be(
            Some(
              BuyTrafficRequestTxLogEntry.Status.Rejected(
                BuyTrafficRequestStatusRejected("just because")
              )
            )
          )
        }
      }

      "retrieve requests from batch operations without losing elements" in scForAll {
        (n: Int, xs: List[Int]) =>
          import org.lfdecentralizedtrust.splice.wallet.store.UserWalletTxLogParser.splitFirst
          val withoutN = xs.filterNot(Set(n))
          val withN = util.Random.shuffle(n +: xs)
          val isN: Int PartialFunction Int = { case nn if n == nn => nn }
          splitFirst(withoutN)(isN) shouldBe (withoutN, None) withClue "passthrough if no match"
          inside(splitFirst(withN)(isN)) { case (prefix, Some((found, suffix))) =>
            prefix ++ (found +: suffix) shouldBe withN
          } withClue "finds match if it exists"
          splitFirst(n +: withN)(isN) shouldBe
            (List.empty, Some((n, withN))) withClue "prefers the first match"
      }
    }

    "listExpiredAppPaymentRequests" should {

      "return correct results" in {
        def paymentExpiringAt(t: Long) =
          appPaymentRequest(
            user1,
            provider1,
            10.0,
            paymentCodegen.Unit.AMULETUNIT,
            time(t),
            s"expiring at $t",
          )

        for {
          store <- mkStore(user1)
          request1 = paymentExpiringAt(1)
          request3 = paymentExpiringAt(3)
          _ <- dummyDomain.create(request1, createdEventSignatories = Seq(user1))(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(request3, createdEventSignatories = Seq(user1))(
            store.multiDomainAcsStore
          )
        } yield {
          def cidsAt(t: CantonTimestamp) = store
            .listExpiredAppPaymentRequests(t, PageLimit.tryCreate(10))(TraceContext.empty)
            .futureValue
            .map(_.contract.contractId)

          cidsAt(time(0)) should be(empty)
          cidsAt(time(1)) should be(empty)
          cidsAt(time(2)) should contain theSameElementsAs Seq(request1.contractId)
          cidsAt(time(3)) should contain theSameElementsAs Seq(request1.contractId)
          cidsAt(time(4)) should contain theSameElementsAs Seq(
            request1.contractId,
            request3.contractId,
          )
        }
      }

    }

    "listAppPaymentRequests and getAppPaymentRequest" should {

      "return correct results" in {
        for {
          Seq(store1, store2, storeP) <- MonadUtil.sequentialTraverse(
            Seq(user1, user2, provider1)
          ) { endUserParty =>
            for {
              store <- mkStore(endUserParty)
              _ <- dummyDomain.create(
                walletInstall(endUserParty),
                createdEventSignatories = Seq(endUserParty, dsoParty),
              )(store.multiDomainAcsStore)
            } yield store
          }
          allAcsStores = Seq(
            store1.multiDomainAcsStore,
            store2.multiDomainAcsStore,
            storeP.multiDomainAcsStore,
          )
          request1 = appPaymentRequest(
            user1,
            provider1,
            10.0,
            paymentCodegen.Unit.AMULETUNIT,
            time(1),
            s"request for $user1",
          )
          request2 = appPaymentRequest(
            user2,
            provider1,
            10.0,
            paymentCodegen.Unit.AMULETUNIT,
            time(1),
            s"request for $user2",
          )
          _ <- dummyDomain.createMulti(request1, createdEventSignatories = Seq(user1))(allAcsStores)
          _ <- dummyDomain.createMulti(request2, createdEventSignatories = Seq(user2))(allAcsStores)
        } yield {
          def resultCids(
              r: ContractWithState[
                paymentCodegen.AppPaymentRequest.ContractId,
                paymentCodegen.AppPaymentRequest,
              ]
          ) = r.contractId.contractId

          // Listing - user only sees their own request
          val actual1 = store1.listAppPaymentRequests().futureValue.map(resultCids)
          val expected1 = Seq(
            request1.contractId.contractId
          )
          actual1 should contain theSameElementsInOrderAs expected1

          // Listing - user only sees their own request
          val actual2 = store2.listAppPaymentRequests().futureValue.map(resultCids)
          val expected2 = Seq(
            request2.contractId.contractId
          )
          actual2 should contain theSameElementsInOrderAs expected2

          // Listing - provider doesn't see any request
          val actualP = storeP.listAppPaymentRequests().futureValue.map(resultCids)
          actualP should be(empty)

          // Pointwise lookup - only user1 store should see request1
          store1
            .getAppPaymentRequest(request1.contractId)
            .map(resultCids)
            .futureValue should be(
            request1.contractId.contractId
          )
          assertThrows[Throwable](store2.getAppPaymentRequest(request1.contractId).futureValue)
          assertThrows[Throwable](storeP.getAppPaymentRequest(request1.contractId).futureValue)
        }
      }

      "return correct results if a request is archived" in {
        for {
          store1 <- mkStore(user1)
          _ <- dummyDomain.create(
            walletInstall(user1),
            createdEventSignatories = Seq(user1, dsoParty),
          )(store1.multiDomainAcsStore)
          request1 = appPaymentRequest(
            user1,
            provider1,
            10.0,
            paymentCodegen.Unit.AMULETUNIT,
            time(1),
            "request1",
          )
          request2 = appPaymentRequest(
            user1,
            provider1,
            20.0,
            paymentCodegen.Unit.AMULETUNIT,
            time(2),
            "request2",
          )
          request3 = appPaymentRequest(
            user1,
            provider1,
            30.0,
            paymentCodegen.Unit.AMULETUNIT,
            time(3),
            "request3",
          )
          _ <- dummyDomain.create(request1, createdEventSignatories = Seq(user1))(
            store1.multiDomainAcsStore
          )
          _ <- dummyDomain.create(request2, createdEventSignatories = Seq(user1))(
            store1.multiDomainAcsStore
          )
          _ <- dummyDomain.archive(request2)(store1.multiDomainAcsStore)
          _ <- dummyDomain.create(request3, createdEventSignatories = Seq(user1))(
            store1.multiDomainAcsStore
          )
          _ <- dummyDomain.archive(request3)(store1.multiDomainAcsStore)
        } yield {
          def resultCids(
              r: ContractWithState[
                paymentCodegen.AppPaymentRequest.ContractId,
                paymentCodegen.AppPaymentRequest,
              ]
          ) =
            r.contractId.contractId

          // Listing - only request1 should be visible
          val actual = store1.listAppPaymentRequests().futureValue.map(resultCids)
          val expected = Seq(
            request1.contractId.contractId
          )
          actual should contain theSameElementsInOrderAs expected

          // Pointwise lookup - only request1 should be visible
          store1
            .getAppPaymentRequest(request1.contractId)
            .map(resultCids)
            .futureValue should be(
            request1.contractId.contractId
          )
          assertThrows[Throwable](store1.getAppPaymentRequest(request2.contractId).futureValue)
          assertThrows[Throwable](store1.getAppPaymentRequest(request3.contractId).futureValue)
        }
      }
    }

    "listSubscriptionStatesReadyForPayment" should {

      "return correct results" in {
        def subscriptionDueAt(dueAt: Long, duration: Long) = subscriptionInIdleState(
          user1,
          provider1,
          subscriptionPayData(paymentDurationSeconds = duration),
          time(dueAt),
        )

        for {
          store <- mkStore(user1)
          (subscription2, idleState2) = subscriptionDueAt(3, 1)
          (subscription1, idleState1) = subscriptionDueAt(3, 2)
          _ <- dummyDomain.create(subscription2, createdEventSignatories = Seq(user1))(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(idleState2, createdEventSignatories = Seq(user1))(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(subscription1, createdEventSignatories = Seq(user1))(
            store.multiDomainAcsStore
          )
          _ <- dummyDomain.create(idleState1, createdEventSignatories = Seq(user1))(
            store.multiDomainAcsStore
          )
        } yield {
          def cidsAt(t: CantonTimestamp) = store
            .listSubscriptionStatesReadyForPayment(t, PageLimit.tryCreate(10))(TraceContext.empty)
            .futureValue
            .map(_.contract.contractId)

          cidsAt(time(1)) should be(empty)
          cidsAt(time(2)) should contain theSameElementsAs Seq(idleState1.contractId)
          cidsAt(time(3)) should contain theSameElementsAs Seq(
            idleState2.contractId,
            idleState1.contractId,
          )
        }
      }

    }

    "listSubscriptions" should {

      def resultCids(store: UserWalletStore) = store
        .listSubscriptions(time(0))
        .futureValue
        .map(r => r.subscription.contractId.contractId -> r.state.contract.contractId.contractId)

      "return correct results" in {
        val payData = subscriptionPayData()
        for {
          store1 <- mkStore(user1)
          store2 <- mkStore(user2)
          storeP <- mkStore(provider1)
          allAcsStores = Seq(
            store1.multiDomainAcsStore,
            store2.multiDomainAcsStore,
            storeP.multiDomainAcsStore,
          )
          (subscription1, state1) = subscriptionInIdleState(
            user1,
            provider1,
            payData,
            time(1),
          )
          lockedAmuletCid = new amuletCodegen.LockedAmulet.ContractId(nextCid())
          (subscription2, state2) = subscriptionInPaymentState(
            user1,
            provider1,
            payData,
            time(1),
            lockedAmuletCid,
            1L,
          )
          _ <- dummyDomain.createMulti(subscription1, createdEventSignatories = Seq(user1))(
            allAcsStores
          )
          _ <- dummyDomain.createMulti(state1, createdEventSignatories = Seq(user1))(allAcsStores)
          _ <- dummyDomain.createMulti(subscription2, createdEventSignatories = Seq(user1))(
            allAcsStores
          )
          _ <- dummyDomain.createMulti(state2, createdEventSignatories = Seq(user1))(allAcsStores)
        } yield {
          val actual = resultCids(store1)
          val expected = Seq(
            subscription1.contractId.contractId -> state1.contractId.contractId,
            subscription2.contractId.contractId -> state2.contractId.contractId,
          )
          actual should contain theSameElementsAs expected
        }
      }

      "return correct results after state changes" in {
        val payData = subscriptionPayData()
        for {
          store1 <- mkStore(user1)
          (subscription1, state1) = subscriptionInIdleState(
            user1,
            provider1,
            payData,
            time(1),
          )
          lockedAmuletCid = new amuletCodegen.LockedAmulet.ContractId(nextCid())
          state2 = subscriptionPaymentState(
            subscription1,
            payData,
            time(1),
            lockedAmuletCid,
            1,
          )
          _ <- dummyDomain.create(subscription1, createdEventSignatories = Seq(user1))(
            store1.multiDomainAcsStore
          )
          // After a payment has been made to extend a subscription, the old state is archived and a new one is created
          _ <- dummyDomain.archive(state1)(store1.multiDomainAcsStore)
          _ <- dummyDomain.create(state2, createdEventSignatories = Seq(user1))(
            store1.multiDomainAcsStore
          )
        } yield {
          val actual = resultCids(store1)
          val expected = Seq(
            subscription1.contractId.contractId -> state2.contractId.contractId
          )
          actual should contain theSameElementsAs expected
        }
      }

      "return correct results after subscription is archived" in {
        val payData = subscriptionPayData()
        for {
          store1 <- mkStore(user1)
          (subscription1, state1) = subscriptionInIdleState(
            user1,
            provider1,
            payData,
            time(1),
          )
          _ <- dummyDomain.create(subscription1, createdEventSignatories = Seq(user1))(
            store1.multiDomainAcsStore
          )
          // If a subscription is expired because of a missed payment, all 3 contracts are archived
          _ <- dummyDomain.archive(state1)(store1.multiDomainAcsStore)
          _ <- dummyDomain.archive(subscription1)(store1.multiDomainAcsStore)
        } yield {
          val actual = resultCids(store1)
          actual should be(empty)
        }
      }
    }

    "listSubscriptionRequests and getSubscriptionRequest" should {

      "return correct results" in {
        val payData = subscriptionPayData()
        for {
          store1 <- mkStore(user1)
          store2 <- mkStore(user2)
          storeP <- mkStore(provider1)
          allAcsStores = Seq(
            store1.multiDomainAcsStore,
            store2.multiDomainAcsStore,
            storeP.multiDomainAcsStore,
          )
          reference1 = new subsCodegen.SubscriptionRequest.ContractId(nextCid())
          subscription1 = subscription(user1, provider1, reference1)
          request1 = subscriptionRequest(subscription1.payload.subscriptionData, payData)
          reference2 = new subsCodegen.SubscriptionRequest.ContractId(nextCid())
          subscription2 = subscription(user2, provider1, reference2)
          request2 = subscriptionRequest(subscription2.payload.subscriptionData, payData)
          _ <- dummyDomain.createMulti(subscription1, createdEventSignatories = Seq(user1))(
            allAcsStores
          )
          _ <- dummyDomain.createMulti(request1, createdEventSignatories = Seq(user1))(allAcsStores)
          _ <- dummyDomain.createMulti(subscription2, createdEventSignatories = Seq(user2))(
            allAcsStores
          )
          _ <- dummyDomain.createMulti(request2, createdEventSignatories = Seq(user2))(allAcsStores)
        } yield {
          // Listing - user only sees their own request
          val actual1 = store1.listSubscriptionRequests().futureValue.map(_.contractId)
          val expected1 = Seq(request1.contractId)
          actual1 should contain theSameElementsInOrderAs expected1

          // Listing - user only sees their own request
          val actual2 = store2.listSubscriptionRequests().futureValue.map(_.contractId)
          val expected2 = Seq(request2.contractId)
          actual2 should contain theSameElementsInOrderAs expected2

          // Listing - provider doesn't see any request
          val actualP = storeP.listSubscriptionRequests().futureValue.map(_.contractId)
          actualP should be(empty)

          // Pointwise lookup - only user1 store should see request1
          store1
            .getSubscriptionRequest(request1.contractId)
            .futureValue
            .contractId should be(
            request1.contractId
          )
          assertThrows[Throwable](store2.getSubscriptionRequest(request1.contractId).futureValue)
          assertThrows[Throwable](storeP.getSubscriptionRequest(request1.contractId).futureValue)
        }
      }

    }

    "listTransactions" should {

      // This helper is similar to the one in WalletTxLogTestUtil
      type CheckTxHistoryFn = PartialFunction[TxLogEntry, Assertion]
      def checkTxHistory(
          store: UserWalletStore,
          expected: Seq[CheckTxHistoryFn],
          previousEventId: Option[String] = None,
      ): Unit = {

        val actual =
          store
            .listTransactions(
              previousEventId,
              limit = PageLimit.tryCreate(Limit.DefaultMaxPageSize),
            )
            .futureValue
        actual should have length expected.size.toLong

        actual
          .zip(expected)
          .zipWithIndex
          .foreach { case ((entry, pf), i) =>
            clue(s"Entry at position $i") {
              inside(entry)(pf)
            }
          }

        clue("Paginated result should be equal to non-paginated result") {
          val paginatedResult = Iterator
            .unfold[Seq[TxLogEntry], Option[String]](previousEventId)(beginAfterId => {
              val entries =
                store.listTransactions(beginAfterId, limit = PageLimit.tryCreate(2)).futureValue
              if (entries.isEmpty)
                None
              else
                Some(entries -> Some(entries.last.eventId))
            })
            .toSeq
            .flatten

          paginatedResult should contain theSameElementsInOrderAs actual
        }
      }

      def mkMint(amount: Double)(offset: Long) = {
        mintTransaction(user1, amount, 1, 1)(offset)
      }

      "return entries in correct order" in {
        for {
          store <- mkStore(user1)
          _ <- dummyDomain.ingest(mkMint(1.0))(store.multiDomainAcsStore)
          _ <- dummyDomain.ingest(mkMint(2.0))(store.multiDomainAcsStore)
          _ <- dummyDomain.ingest(mkMint(3.0))(store.multiDomainAcsStore)
          _ <- dummyDomain.ingest(mkMint(4.0))(store.multiDomainAcsStore)
          _ <- dummyDomain.ingest(mkMint(5.0))(store.multiDomainAcsStore)
        } yield {
          checkTxHistory(
            store,
            // Note: transactions are returned in reverse chronological order
            Seq(
              { case logEntry: BalanceChangeTxLogEntry =>
                logEntry.subtype.value shouldBe TxLogEntry.BalanceChangeTransactionSubtype.Mint.toProto
                logEntry.amount shouldBe 5.0
              },
              { case logEntry: BalanceChangeTxLogEntry =>
                logEntry.subtype.value shouldBe TxLogEntry.BalanceChangeTransactionSubtype.Mint.toProto
                logEntry.amount shouldBe 4.0
              },
              { case logEntry: BalanceChangeTxLogEntry =>
                logEntry.subtype.value shouldBe TxLogEntry.BalanceChangeTransactionSubtype.Mint.toProto
                logEntry.amount shouldBe 3.0
              },
              { case logEntry: BalanceChangeTxLogEntry =>
                logEntry.subtype.value shouldBe TxLogEntry.BalanceChangeTransactionSubtype.Mint.toProto
                logEntry.amount shouldBe 2.0
              },
              { case logEntry: BalanceChangeTxLogEntry =>
                logEntry.subtype.value shouldBe TxLogEntry.BalanceChangeTransactionSubtype.Mint.toProto
                logEntry.amount shouldBe 1.0
              },
            ),
          )
          Succeeded
        }
      }

      "work across several migration ids when paginating" in {
        def paginate(
            store: UserWalletStore,
            after: Option[String],
            acc: Seq[TxLogEntry.TransactionHistoryTxLogEntry],
        ): Future[Seq[TxLogEntry.TransactionHistoryTxLogEntry]] = {
          store.listTransactions(after, limit = PageLimit.tryCreate(1)).flatMap { seq =>
            seq.lastOption match {
              case None =>
                Future.successful(acc)
              case Some(last) =>
                paginate(store, Some(last.eventId), acc ++ seq)
            }
          }
        }
        for {
          store1 <- mkStore(user1, migrationId = 1L)
          _ <- dummyDomain.ingest(mkMint(1.0))(store1.multiDomainAcsStore)
          store2 <- mkStore(user1, migrationId = 2L)
          _ <- dummyDomain.ingest(mkMint(2.0))(store2.multiDomainAcsStore)
          store3 <- mkStore(user1, migrationId = 3L)
          _ <- dummyDomain.ingest(mkMint(3.0))(store3.multiDomainAcsStore)
          result <- paginate(store3, None, Seq.empty)
        } yield result.collect { case logEntry: BalanceChangeTxLogEntry =>
          logEntry.amount
        } should be(Seq(3.0, 2.0, 1.0).map(BigDecimal(_)))
      }
    }

    "listAnsEntries" should {
      "return correct results" in {
        val payData = subscriptionPayData()
        for {
          store1 <- mkStore(user1)
          (subscription1, state1) = subscriptionInIdleState(
            user1,
            provider1,
            payData,
            time(1),
          )
          subscriptionRequest1 = subscription1.payload.reference
          ansEntryContext1 = ansEntryContext(
            user1,
            "user1",
            subscriptionRequest1,
          )
          ansEntry1 = ansEntry(
            user1,
            "user1",
            provider1,
          )
          _ <- dummyDomain.create(subscription1, createdEventSignatories = Seq(user1))(
            store1.multiDomainAcsStore
          )
          _ <- dummyDomain.create(state1, createdEventSignatories = Seq(user1))(
            store1.multiDomainAcsStore
          )
          _ <- dummyDomain.create(
            ansEntryContext1,
            createdEventSignatories = Seq(user1, provider1),
          )(
            store1.multiDomainAcsStore
          )
          _ <- dummyDomain.create(ansEntry1, createdEventSignatories = Seq(user1, provider1))(
            store1.multiDomainAcsStore
          )
        } yield {
          val actual = store1.listAnsEntries(time(0)).futureValue
          val expected = Seq(
            UserWalletStore.AnsEntryWithPayData(
              contractId = ansEntry1.contractId,
              expiresAt = ansEntry1.payload.expiresAt,
              entryName = ansEntry1.payload.name,
              amount = state1.payload.payData.paymentAmount.amount,
              unit = state1.payload.payData.paymentAmount.unit,
              paymentInterval = state1.payload.payData.paymentInterval,
              paymentDuration = state1.payload.payData.paymentDuration,
            )
          )
          actual should contain theSameElementsAs expected
        }
      }

    }
  }

  "getOutstandingTransferOffers" in {
    for {
      store <- mkStore(user1)
      offer1 = transferOffer(user1, user2, 10.0, paymentCodegen.Unit.AMULETUNIT, time(1))
      acceptedOffer = acceptedTransferOffer(
        user1,
        user2,
        10.0,
        paymentCodegen.Unit.AMULETUNIT,
        time(1),
      )
      _ <- dummyDomain.create(offer1, createdEventSignatories = Seq(user1))(
        store.multiDomainAcsStore
      )
      _ <- dummyDomain.create(acceptedOffer, createdEventSignatories = Seq(user1))(
        store.multiDomainAcsStore
      )
      (unfiltered, unfilteredAccepted) <- store.getOutstandingTransferOffers(
        None,
        None,
      )
      (filteredSenderMatch, filteredSenderMatchAccepted) <- store.getOutstandingTransferOffers(
        Some(user1),
        None,
      )
      (filteredSenderNoMatch, filteredSenderNoMatchAccepted) <- store.getOutstandingTransferOffers(
        Some(user2),
        None,
      )
      (filteredReceiverMatch, filteredReceiverMatchAccepted) <- store.getOutstandingTransferOffers(
        None,
        Some(user2),
      )
      (filteredReceiverNoMatch, filteredReceiverNoMatchAccepted) <- store
        .getOutstandingTransferOffers(
          None,
          Some(user1),
        )
    } yield {
      unfiltered.map(_.contractId) shouldBe Seq(offer1.contractId)
      unfilteredAccepted.map(_.contractId) shouldBe Seq(acceptedOffer.contractId)
      filteredSenderMatch.map(_.contractId) shouldBe Seq(offer1.contractId)
      filteredSenderMatchAccepted.map(_.contractId) shouldBe Seq(acceptedOffer.contractId)
      filteredSenderNoMatch.map(_.contractId) shouldBe Seq()
      filteredSenderNoMatchAccepted.map(_.contractId) shouldBe Seq()
      filteredReceiverMatch.map(_.contractId) shouldBe Seq(offer1.contractId)
      filteredReceiverMatchAccepted.map(_.contractId) shouldBe Seq(acceptedOffer.contractId)
      filteredReceiverNoMatch.map(_.contractId) shouldBe Seq()
      filteredReceiverNoMatchAccepted.map(_.contractId) shouldBe Seq()
    }
  }

  "lookupTransferPreapprovalProposal" should {
    "return correct results" in {
      for {
        store <- mkStore(user1)
        proposal1 = transferPreapprovalProposal(user1, validator)
        proposal2 = transferPreapprovalProposal(user2, validator)
        proposal3 = transferPreapprovalProposal(user1, provider1)
        _ <- dummyDomain.create(proposal1, createdEventSignatories = Seq(user1))(
          store.multiDomainAcsStore
        )
        _ <- dummyDomain.create(proposal2, createdEventSignatories = Seq(user2))(
          store.multiDomainAcsStore
        )
        _ <- dummyDomain.create(proposal3, createdEventSignatories = Seq(user1))(
          store.multiDomainAcsStore
        )
        result <- store.lookupTransferPreapprovalProposal(user1)
        resultDifferentUser <- store.lookupTransferPreapprovalProposal(validator)
      } yield {
        result.value.value shouldBe proposal1
        resultDifferentUser.value shouldBe None
      }
    }
  }

  "listDevelopmentFundCouponHistory" should {
    "return correct results" in {
      def mkAllocateDevelopmentFundCoupon(
          contractId: amuletCodegen.DevelopmentFundCoupon.ContractId,
          beneficiary: PartyId,
          fundManager: PartyId,
          amount: Double,
          expiresAt: CantonTimestamp,
          reason: String,
      )(offset: Long) = {
        mkAllocateDevelopmentFundCouponTx(
          offset,
          contractId,
          beneficiary,
          fundManager,
          amount,
          expiresAt,
          reason,
        )
      }

      def mkDevelopmentFundCouponDsoExpire(
          contractId: amuletCodegen.DevelopmentFundCoupon.ContractId
      )(offset: Long) = {
        mkDevelopmentFundCouponDsoExpireTx(
          offset,
          contractId.contractId,
        )
      }

      def ingestDevelopmentFundCouponDsoExpire(
          store: UserWalletStore,
          contractId: amuletCodegen.DevelopmentFundCoupon.ContractId,
      ) =
        dummyDomain.ingest(
          mkDevelopmentFundCouponDsoExpire(
            contractId
          )
        )(
          store.multiDomainAcsStore
        )

      def mkDevelopmentFundCouponWithdraw(
          contractId: amuletCodegen.DevelopmentFundCoupon.ContractId,
          reason: String,
      )(offset: Long) = {
        mkDevelopmentFundCouponWithdrawTx(
          offset,
          contractId.contractId,
          reason,
        )
      }

      def ingestDevelopmentFundCouponWithdraw(
          store: UserWalletStore,
          contractId: amuletCodegen.DevelopmentFundCoupon.ContractId,
          reason: String,
      ) =
        dummyDomain.ingest(
          mkDevelopmentFundCouponWithdraw(
            contractId,
            reason,
          )
        )(
          store.multiDomainAcsStore
        )

      def mkDevelopmentFundCouponReject(
          contractId: amuletCodegen.DevelopmentFundCoupon.ContractId,
          reason: String,
      )(offset: Long) = {
        mkDevelopmentFundCouponRejectTx(
          offset,
          contractId.contractId,
          reason,
        )
      }

      def ingestDevelopmentFundCouponReject(
          store: UserWalletStore,
          contractId: amuletCodegen.DevelopmentFundCoupon.ContractId,
          reason: String,
      ) =
        dummyDomain.ingest(
          mkDevelopmentFundCouponReject(
            contractId,
            reason,
          )
        )(
          store.multiDomainAcsStore
        )

      def mkTransferTransactionWithInputDevelopmentFundCoupon(
          contractId: amuletCodegen.DevelopmentFundCoupon.ContractId,
          beneficiary: PartyId,
      )(offset: Long) = {
        transferTransactionWithInputDevelopmentFundCouponTx(
          offset,
          contractId,
          beneficiary,
        )
      }

      def ingestTransferTransactionWithInputDevelopmentFundCoupon(
          store: UserWalletStore,
          contractId: amuletCodegen.DevelopmentFundCoupon.ContractId,
          beneficiary: PartyId,
      ) =
        dummyDomain.ingest(
          mkTransferTransactionWithInputDevelopmentFundCoupon(
            contractId,
            beneficiary,
          )
        )(
          store.multiDomainAcsStore
        )

      def ingestDevelopmentFundCoupon(
          store: UserWalletStore,
          contractId: amuletCodegen.DevelopmentFundCoupon.ContractId,
          beneficiary: PartyId,
          fundManager: PartyId,
          amount: Double,
          expiresAt: CantonTimestamp,
          reason: String,
      ) =
        dummyDomain.ingest(
          mkAllocateDevelopmentFundCoupon(
            contractId,
            beneficiary,
            fundManager,
            amount,
            expiresAt,
            reason,
          )
        )(
          store.multiDomainAcsStore
        )

      val beneficiary = user1
      val fundManager = user2
      for {
        fundManagerStore <- mkStore(fundManager)
        coupon1Cid = new DevelopmentFundCoupon.ContractId(nextCid())
        coupon2Cid = new DevelopmentFundCoupon.ContractId(nextCid())
        coupon3Cid = new DevelopmentFundCoupon.ContractId(nextCid())
        coupon4Cid = new DevelopmentFundCoupon.ContractId(nextCid())
        coupon5Cid = new DevelopmentFundCoupon.ContractId(nextCid())
        coupon6Cid = new DevelopmentFundCoupon.ContractId(nextCid())
        coupon7Cid = new DevelopmentFundCoupon.ContractId(nextCid())
        coupon8Cid = new DevelopmentFundCoupon.ContractId(nextCid())
        coupon9Cid = new DevelopmentFundCoupon.ContractId(nextCid())
        // Creation
        _ <- ingestDevelopmentFundCoupon(
          fundManagerStore,
          coupon1Cid,
          beneficiary,
          fundManager,
          10.0,
          time(1),
          "reason_1",
        )
        _ <- ingestDevelopmentFundCoupon(
          fundManagerStore,
          coupon2Cid,
          beneficiary,
          fundManager,
          20.0,
          time(2),
          "reason_2",
        )
        _ <- ingestDevelopmentFundCoupon(
          fundManagerStore,
          coupon3Cid,
          beneficiary,
          fundManager,
          30.0,
          time(3),
          "reason_3",
        )
        _ <- ingestDevelopmentFundCoupon(
          fundManagerStore,
          coupon4Cid,
          beneficiary,
          fundManager,
          40.0,
          time(4),
          "reason_4",
        )
        _ <- ingestDevelopmentFundCoupon(
          fundManagerStore,
          coupon5Cid,
          beneficiary,
          fundManager,
          50.0,
          time(5),
          "reason_5",
        )
        _ <- ingestDevelopmentFundCoupon(
          fundManagerStore,
          coupon6Cid,
          beneficiary,
          fundManager,
          60.0,
          time(6),
          "reason_6",
        )
        _ <- ingestDevelopmentFundCoupon(
          fundManagerStore,
          coupon7Cid,
          beneficiary,
          fundManager,
          70.0,
          time(7),
          "reason_7",
        )
        _ <- ingestDevelopmentFundCoupon(
          fundManagerStore,
          coupon8Cid,
          beneficiary,
          fundManager,
          80.0,
          time(8),
          "reason_8",
        )
        _ <- ingestDevelopmentFundCoupon(
          fundManagerStore,
          coupon9Cid,
          beneficiary,
          fundManager,
          90.0,
          time(9),
          "reason_9",
        )
        // Archival
        coupon2RejectionReason = "coupon_2_rejection_reason"
        _ <- ingestDevelopmentFundCouponReject(
          fundManagerStore,
          coupon2Cid,
          coupon2RejectionReason,
        )
        _ <- ingestTransferTransactionWithInputDevelopmentFundCoupon(
          fundManagerStore,
          coupon1Cid,
          beneficiary,
        )
        coupon3WithdrawalReason = "coupon_3_withdrawal_reason"
        _ <- ingestDevelopmentFundCouponWithdraw(
          fundManagerStore,
          coupon3Cid,
          coupon3WithdrawalReason,
        )
        _ <- ingestTransferTransactionWithInputDevelopmentFundCoupon(
          fundManagerStore,
          coupon5Cid,
          beneficiary,
        )
        _ <- ingestDevelopmentFundCouponDsoExpire(fundManagerStore, coupon4Cid)
        _ <- ingestTransferTransactionWithInputDevelopmentFundCoupon(
          fundManagerStore,
          coupon7Cid,
          beneficiary,
        )
        _ <- ingestTransferTransactionWithInputDevelopmentFundCoupon(
          fundManagerStore,
          coupon6Cid,
          beneficiary,
        )
        _ <- ingestTransferTransactionWithInputDevelopmentFundCoupon(
          fundManagerStore,
          coupon8Cid,
          beneficiary,
        )
      } yield {
        def listDevelopmentFundCouponHistory(after: Option[Long]) = fundManagerStore
          .listDevelopmentFundCouponHistory(after, PageLimit.tryCreate(3))(TraceContext.empty)
          .futureValue

        def assertPage(
            after: Option[Long],
            expected: Seq[(Double, DevelopmentFundCouponArchivedTxLogEntry.Status)],
        ) = {
          val page = listDevelopmentFundCouponHistory(after)
          page.resultsInPage.map { case (entry, status) =>
            entry.amount -> status
          } shouldBe expected
          page
        }

        val claimedStatus = DevelopmentFundCouponArchivedTxLogEntry.Status.Claimed(
          DevelopmentFundCouponStatusClaimed()
        )
        val expiredStatus = DevelopmentFundCouponArchivedTxLogEntry.Status.Expired(
          DevelopmentFundCouponStatusExpired()
        )
        def withdrawnStatus(reason: String) =
          DevelopmentFundCouponArchivedTxLogEntry.Status.Withdrawn(
            DevelopmentFundCouponStatusWithdrawn(reason)
          )
        def rejectedStatus(reason: String) =
          DevelopmentFundCouponArchivedTxLogEntry.Status.Rejected(
            DevelopmentFundCouponStatusRejected(reason)
          )

        // Archival order: 2, 1,  3, 5, 4,   7, 6, 8
        // Coupon 9 is not archived
        val page1 =
          assertPage(None, Seq((80.0, claimedStatus), (60.0, claimedStatus), (70.0, claimedStatus)))
        val page2 = assertPage(
          page1.nextPageToken,
          Seq(
            (40.0, expiredStatus),
            (50.0, claimedStatus),
            (30.0, withdrawnStatus(coupon3WithdrawalReason)),
          ),
        )
        val page3 =
          assertPage(
            page2.nextPageToken,
            Seq((10.0, claimedStatus), (20.0, rejectedStatus(coupon2RejectionReason))),
          )
        val page4 = listDevelopmentFundCouponHistory(page3.nextPageToken)
        page4.resultsInPage shouldBe empty
        page4.nextPageToken should not be defined
      }
    }
  }

  private lazy val provider1 = providerParty(1)
  private lazy val user1 = userParty(1)
  private lazy val user2 = userParty(2)
  private lazy val validator = mkPartyId(s"validator")
  private lazy val participantId = mkParticipantId("user-1")
  protected def storeKey(endUserParty: PartyId) = UserWalletStore.Key(
    dsoParty = dsoParty,
    validatorParty = validator,
    endUserParty = endUserParty,
  )

  private def walletInstall(endUserParty: PartyId) = {
    val templateId = installCodegen.WalletAppInstall.TEMPLATE_ID_WITH_PACKAGE_ID
    val template = new installCodegen.WalletAppInstall(
      dsoParty.toProtoPrimitive,
      validator.toProtoPrimitive,
      endUserParty.toProtoPrimitive,
      endUserParty.toProtoPrimitive,
    )
    contract(
      identifier = templateId,
      contractId = new installCodegen.WalletAppInstall.ContractId(nextCid()),
      payload = template,
    )
  }

  private def transferOffer(
      sender: PartyId,
      receiver: PartyId,
      amount: Double,
      unit: paymentCodegen.Unit,
      expiresAt: CantonTimestamp,
      trackingId: String = UUID.randomUUID().toString,
      contractId: transferOffersCodegen.TransferOffer.ContractId =
        new transferOffersCodegen.TransferOffer.ContractId(nextCid()),
  ) = {
    val templateId = transferOffersCodegen.TransferOffer.TEMPLATE_ID_WITH_PACKAGE_ID
    val template = new transferOffersCodegen.TransferOffer(
      sender.toProtoPrimitive,
      receiver.toProtoPrimitive,
      dsoParty.toProtoPrimitive,
      new paymentCodegen.PaymentAmount(new java.math.BigDecimal(amount), unit),
      s"Payment from $sender to $receiver for $amount $unit, expiring at $expiresAt",
      expiresAt.toInstant,
      trackingId,
    )
    contract(
      identifier = templateId,
      contractId = contractId,
      payload = template,
    )
  }

  private def acceptedTransferOffer(
      sender: PartyId,
      receiver: PartyId,
      amount: Double,
      unit: paymentCodegen.Unit,
      expiresAt: CantonTimestamp,
      trackingId: String = UUID.randomUUID().toString,
      cid: String = nextCid(),
  ) = {
    val templateId = transferOffersCodegen.AcceptedTransferOffer.TEMPLATE_ID_WITH_PACKAGE_ID
    val template = new transferOffersCodegen.AcceptedTransferOffer(
      sender.toProtoPrimitive,
      receiver.toProtoPrimitive,
      dsoParty.toProtoPrimitive,
      new paymentCodegen.PaymentAmount(new java.math.BigDecimal(amount), unit),
      expiresAt.toInstant,
      trackingId,
    )
    contract(
      identifier = templateId,
      contractId = new transferOffersCodegen.AcceptedTransferOffer.ContractId(cid),
      payload = template,
    )
  }

  private def buyTrafficRequest(
      buyer: PartyId,
      memberId: Member,
      synchronizerId: SynchronizerId,
      trafficAmount: Long,
      expiresAt: CantonTimestamp,
      trackingId: String,
      cid: trafficRequestCodegen.BuyTrafficRequest.ContractId,
  ) = {
    val templateId = trafficRequestCodegen.BuyTrafficRequest.TEMPLATE_ID_WITH_PACKAGE_ID
    val template = new trafficRequestCodegen.BuyTrafficRequest(
      dsoParty.toProtoPrimitive,
      buyer.toProtoPrimitive,
      expiresAt.toInstant,
      trackingId,
      trafficAmount,
      memberId.toProtoPrimitive,
      synchronizerId.toProtoPrimitive,
      domainMigrationId,
    )
    contract(
      identifier = templateId,
      contractId = cid,
      payload = template,
    )
  }

  private def appPaymentRequest(
      sender: PartyId,
      provider: PartyId,
      amount: Double,
      unit: paymentCodegen.Unit,
      expiresAt: CantonTimestamp,
      description: String,
  ) = {
    val templateId = paymentCodegen.AppPaymentRequest.TEMPLATE_ID_WITH_PACKAGE_ID
    val template = new paymentCodegen.AppPaymentRequest(
      sender.toProtoPrimitive,
      java.util.List.of(
        new paymentCodegen.ReceiverAmount(
          provider.toProtoPrimitive,
          new paymentCodegen.PaymentAmount(new java.math.BigDecimal(amount), unit),
        )
      ),
      provider.toProtoPrimitive,
      dsoParty.toProtoPrimitive,
      expiresAt.toInstant,
      description,
    )
    contract(
      identifier = templateId,
      contractId = new paymentCodegen.AppPaymentRequest.ContractId(nextCid()),
      payload = template,
    )
  }

  private def subscription(
      user: PartyId,
      provider: PartyId,
      reference: subsCodegen.SubscriptionRequest.ContractId,
  ) = {
    val templateId = subsCodegen.Subscription.TEMPLATE_ID_WITH_PACKAGE_ID
    val template = new subsCodegen.Subscription(
      new subsCodegen.SubscriptionData(
        user.toProtoPrimitive,
        provider.toProtoPrimitive,
        provider.toProtoPrimitive,
        dsoParty.toProtoPrimitive,
        s"Party $user subscribes to a service provided by party $provider",
      ),
      reference,
    )
    contract(
      identifier = templateId,
      contractId = new subsCodegen.Subscription.ContractId(nextCid()),
      payload = template,
    )
  }

  private def subscriptionIdleState(
      subscriptionContract: Contract[subsCodegen.Subscription.ContractId, subsCodegen.Subscription],
      payData: subsCodegen.SubscriptionPayData,
      nextPaymentDueAt: CantonTimestamp,
  ) = {
    val templateId = subsCodegen.SubscriptionIdleState.TEMPLATE_ID_WITH_PACKAGE_ID
    val template = new subsCodegen.SubscriptionIdleState(
      subscriptionContract.contractId,
      subscriptionContract.payload.subscriptionData,
      payData,
      nextPaymentDueAt.toInstant,
      subscriptionContract.payload.reference,
    )
    contract(
      identifier = templateId,
      contractId = new subsCodegen.SubscriptionIdleState.ContractId(nextCid()),
      payload = template,
    )
  }

  private def subscriptionPaymentState(
      subscription: Contract[subsCodegen.Subscription.ContractId, subsCodegen.Subscription],
      payData: subsCodegen.SubscriptionPayData,
      thisPaymentDueAt: CantonTimestamp,
      lockedAmuletCid: amuletCodegen.LockedAmulet.ContractId,
      round: Long,
  ) = {
    val templateId = subsCodegen.SubscriptionPayment.TEMPLATE_ID_WITH_PACKAGE_ID
    val template = new subsCodegen.SubscriptionPayment(
      subscription.contractId,
      subscription.payload.subscriptionData,
      payData,
      thisPaymentDueAt.toInstant,
      // Note: this targetAmount is only correct for CC payments.
      // USD payments would need to apply amulet price, but we don't care about the exact value.
      payData.paymentAmount.amount,
      lockedAmuletCid,
      new Round(round),
      subscription.payload.reference,
    )
    contract(
      identifier = templateId,
      contractId = new subsCodegen.SubscriptionPayment.ContractId(nextCid()),
      payload = template,
    )
  }

  private def subscriptionInIdleState(
      sender: PartyId,
      receiver: PartyId,
      payData: subsCodegen.SubscriptionPayData,
      nextPaymentDueAt: CantonTimestamp,
  ) = {
    val reference = new subsCodegen.SubscriptionRequest.ContractId(nextCid())
    val subscriptionContract = subscription(sender, receiver, reference)
    val idleStateContract = subscriptionIdleState(
      subscriptionContract,
      payData,
      nextPaymentDueAt,
    )
    (subscriptionContract, idleStateContract)
  }

  private def subscriptionInPaymentState(
      sender: PartyId,
      receiver: PartyId,
      payData: subsCodegen.SubscriptionPayData,
      thisPaymentDueAt: CantonTimestamp,
      lockedAmuletCid: amuletCodegen.LockedAmulet.ContractId,
      round: Long,
  ) = {
    val reference = new subsCodegen.SubscriptionRequest.ContractId(nextCid())
    val subscriptionContract = subscription(sender, receiver, reference)
    val paymentStateContract = subscriptionPaymentState(
      subscriptionContract,
      payData,
      thisPaymentDueAt,
      lockedAmuletCid,
      round,
    )
    (subscriptionContract, paymentStateContract)
  }

  private def subscriptionPayData(
      amount: Double = 10.0,
      unit: paymentCodegen.Unit = paymentCodegen.Unit.AMULETUNIT,
      paymentIntervalSeconds: Long = 60L,
      paymentDurationSeconds: Long = 1L,
  ): subsCodegen.SubscriptionPayData = {
    new subsCodegen.SubscriptionPayData(
      new paymentCodegen.PaymentAmount(new java.math.BigDecimal(amount).setScale(10), unit),
      new RelTime(paymentIntervalSeconds * Limit.DefaultMaxPageSize * Limit.DefaultMaxPageSize),
      new RelTime(paymentDurationSeconds * Limit.DefaultMaxPageSize * Limit.DefaultMaxPageSize),
    )
  }

  private def subscriptionRequest(
      subscriptionData: subsCodegen.SubscriptionData,
      payData: subsCodegen.SubscriptionPayData,
  ) = {
    val templateId = subsCodegen.SubscriptionRequest.TEMPLATE_ID_WITH_PACKAGE_ID
    val template = new subsCodegen.SubscriptionRequest(
      subscriptionData,
      payData,
    )
    contract(
      identifier = templateId,
      contractId = new subsCodegen.SubscriptionRequest.ContractId(nextCid()),
      payload = template,
    )
  }

  protected def ansEntry(
      user: PartyId,
      name: String,
      provider: PartyId = providerParty(0),
      entryUrl: String = "https://ans-entry-url.com",
      entryDescription: String = "Sample fake description",
  ) = {
    val templateId = ansCodegen.AnsEntry.TEMPLATE_ID_WITH_PACKAGE_ID
    val template = new ansCodegen.AnsEntry(
      user.toProtoPrimitive,
      provider.toProtoPrimitive,
      name,
      entryUrl,
      entryDescription,
      Instant.now().truncatedTo(ChronoUnit.MICROS).plusSeconds(3600),
    )
    contract(
      identifier = templateId,
      contractId = new ansCodegen.AnsEntry.ContractId(nextCid()),
      payload = template,
    )
  }

  protected def ansEntryContext(
      user: PartyId,
      name: String,
      subscriptionRequest: subsCodegen.SubscriptionRequest.ContractId,
      entryUrl: String = "https://ans-entry-url.com",
      entryDescription: String = "Sample fake description",
  ) = {
    val templateId = ansCodegen.AnsEntryContext.TEMPLATE_ID_WITH_PACKAGE_ID
    val template = new ansCodegen.AnsEntryContext(
      dsoParty.toProtoPrimitive,
      user.toProtoPrimitive,
      name,
      entryUrl,
      entryDescription,
      subscriptionRequest,
    )
    contract(
      identifier = templateId,
      contractId = new ansCodegen.AnsEntryContext.ContractId(nextCid()),
      payload = template,
    )
  }

  protected def transferPreapprovalProposal(receiver: PartyId, provider: PartyId) = {
    val templateId = preapprovalCodegen.TransferPreapprovalProposal.TEMPLATE_ID_WITH_PACKAGE_ID
    val template =
      new TransferPreapprovalProposal(
        receiver.toProtoPrimitive,
        provider.toProtoPrimitive,
        Optional.of(dsoParty.toProtoPrimitive),
      )
    contract(
      identifier = templateId,
      contractId = new preapprovalCodegen.TransferPreapprovalProposal.ContractId(nextCid()),
      payload = template,
    )
  }

  private def mkTransferOfferTx(
      offset: Long,
      trackingId: String,
      sender: PartyId,
      receiver: PartyId,
      transferOfferCid: String,
  ) = {
    val walletAppInstallCid = nextCid()
    val transferOfferTCid = new transferOffersCodegen.TransferOffer.ContractId(transferOfferCid)

    mkExerciseTx(
      offset,
      exercisedEvent(
        walletAppInstallCid,
        installCodegen.WalletAppInstall.TEMPLATE_ID_WITH_PACKAGE_ID,
        None,
        installCodegen.WalletAppInstall.CHOICE_WalletAppInstall_CreateTransferOffer.name,
        consuming = false,
        new installCodegen.WalletAppInstall_CreateTransferOffer(
          "receiver",
          new paymentCodegen.PaymentAmount(
            BigDecimal(1.0).bigDecimal,
            paymentCodegen.Unit.AMULETUNIT,
          ),
          "desc",
          Instant.now().truncatedTo(ChronoUnit.MICROS).plusSeconds(60),
          trackingId,
        ).toValue,
        new installCodegen.WalletAppInstall_CreateTransferOfferResult(
          transferOfferTCid
        ).toValue,
      ),
      Seq(
        toCreatedEvent(
          transferOffer(
            sender,
            receiver,
            1.0,
            paymentCodegen.Unit.AMULETUNIT,
            CantonTimestamp.now().plusSeconds(60),
            trackingId,
            transferOfferTCid,
          ),
          Seq(sender, receiver),
        )
      ),
      dummyDomain,
    )
  }

  private def mkAcceptTransferTx(
      offset: Long,
      trackingId: String,
      sender: PartyId,
      receiver: PartyId,
      acceptedTransferOfferCid: String,
  ) = {
    val walletAppInstallCid = nextCid()

    mkExerciseTx(
      offset,
      exercisedEvent(
        walletAppInstallCid,
        transferOffersCodegen.TransferOffer.TEMPLATE_ID_WITH_PACKAGE_ID,
        None,
        transferOffersCodegen.TransferOffer.CHOICE_TransferOffer_Accept.name,
        consuming = false,
        new transferOffersCodegen.TransferOffer_Accept().toValue,
        new transferOffersCodegen.TransferOffer_AcceptResult(
          new transferOffersCodegen.AcceptedTransferOffer.ContractId(acceptedTransferOfferCid)
        ).toValue,
      ),
      Seq(
        toCreatedEvent(
          acceptedTransferOffer(
            sender,
            receiver,
            1.0,
            paymentCodegen.Unit.AMULETUNIT,
            CantonTimestamp.now().plusSeconds(60),
            trackingId,
            acceptedTransferOfferCid,
          ),
          Seq(sender, receiver),
        )
      ),
      dummyDomain,
    )
  }

  private def mkBuyTrafficRequestTx(
      offset: Long,
      trackingId: String,
      buyer: PartyId,
      memberId: Member,
      synchronizerId: SynchronizerId,
      trafficRequestCid: trafficRequestCodegen.BuyTrafficRequest.ContractId,
      trafficAmount: Long = 1_000_000L,
  ) = {
    val walletAppInstallCid = nextCid()

    mkExerciseTx(
      offset,
      exercisedEvent(
        walletAppInstallCid,
        installCodegen.WalletAppInstall.TEMPLATE_ID_WITH_PACKAGE_ID,
        None,
        installCodegen.WalletAppInstall.CHOICE_WalletAppInstall_CreateBuyTrafficRequest.name,
        consuming = false,
        new WalletAppInstall_CreateBuyTrafficRequest(
          memberId.toProtoPrimitive,
          synchronizerId.toProtoPrimitive,
          domainMigrationId,
          trafficAmount,
          Instant.now().truncatedTo(ChronoUnit.MICROS).plusSeconds(60),
          trackingId,
        ).toValue,
        new install.WalletAppInstall_CreateBuyTrafficRequestResult(
          trafficRequestCid
        ).toValue,
      ),
      Seq(
        toCreatedEvent(
          buyTrafficRequest(
            buyer,
            memberId,
            synchronizerId,
            trafficAmount,
            CantonTimestamp.now().plusSeconds(60),
            trackingId,
            trafficRequestCid,
          ),
          Seq(buyer),
        )
      ),
      synchronizerId,
    )
  }

  private def mkCancelTrafficRequestTx(
      offset: Long,
      trackingId: String,
      buyer: PartyId,
      reason: String,
      requestCid: String,
  ) = {
    mkExerciseTx(
      offset,
      exercisedEvent(
        requestCid,
        trafficRequestCodegen.BuyTrafficRequest.TEMPLATE_ID_WITH_PACKAGE_ID,
        None,
        trafficRequestCodegen.BuyTrafficRequest.CHOICE_BuyTrafficRequest_Cancel.name,
        consuming = false,
        new trafficRequestCodegen.BuyTrafficRequest_Cancel(reason).toValue,
        new trafficRequestCodegen.BuyTrafficRequest_CancelResult(
          new trafficRequestCodegen.BuyTrafficRequestTrackingInfo(
            trackingId,
            buyer.toProtoPrimitive,
          )
        ).toValue,
      ),
      Seq(),
      dummyDomain,
    )
  }

  private def mkAllocateDevelopmentFundCouponTx(
      offset: Long,
      contractId: amuletCodegen.DevelopmentFundCoupon.ContractId,
      beneficiary: PartyId,
      fundManager: PartyId,
      amount: Double,
      expiresAt: CantonTimestamp,
      reason: String,
  ) = {
    val amuletRulesCid = nextCid()
    val unclaimedDevelopmentFundCouponCids = Seq(
      new amuletCodegen.UnclaimedDevelopmentFundCoupon.ContractId(nextCid())
    )

    mkExerciseTx(
      offset,
      exercisedEvent(
        amuletRulesCid,
        amuletRulesCodegen.AmuletRules.TEMPLATE_ID_WITH_PACKAGE_ID,
        None,
        amuletRulesCodegen.AmuletRules.CHOICE_AmuletRules_AllocateDevelopmentFundCoupon.name,
        consuming = false,
        new amuletRulesCodegen.AmuletRules_AllocateDevelopmentFundCoupon(
          unclaimedDevelopmentFundCouponCids.asJava,
          beneficiary.toProtoPrimitive,
          damlDecimal(amount),
          expiresAt.toInstant,
          reason,
          fundManager.toProtoPrimitive,
        ).toValue,
        new amuletRulesCodegen.AmuletRules_AllocateDevelopmentFundCouponResult(
          contractId,
          Optional.empty(),
        ).toValue,
      ),
      Seq(
        toCreatedEvent(
          developmentFundCoupon(contractId, beneficiary, fundManager, amount, expiresAt, reason),
          Seq(dsoParty),
          Seq(beneficiary, fundManager),
        )
      ),
      dummyDomain,
    )
  }

  private def developmentFundCoupon(
      contractId: amuletCodegen.DevelopmentFundCoupon.ContractId,
      beneficiary: PartyId,
      fundManager: PartyId,
      amount: Double,
      expiresAt: CantonTimestamp,
      reason: String,
  ) = {
    val templateId = amuletCodegen.DevelopmentFundCoupon.TEMPLATE_ID_WITH_PACKAGE_ID
    contract(
      identifier = templateId,
      contractId = contractId,
      payload = new amuletCodegen.DevelopmentFundCoupon(
        dsoParty.toProtoPrimitive,
        beneficiary.toProtoPrimitive,
        fundManager.toProtoPrimitive,
        damlDecimal(amount),
        expiresAt.toInstant,
        reason,
      ),
    )
  }

  private def mkDevelopmentFundCouponDsoExpireTx(
      offset: Long,
      developmentFundCouponCid: String,
  ) = {
    val unclaimedDevelopmentFundCouponCid =
      new amuletCodegen.UnclaimedDevelopmentFundCoupon.ContractId(nextCid())
    mkExerciseTx(
      offset,
      exercisedEvent(
        developmentFundCouponCid,
        amuletCodegen.DevelopmentFundCoupon.TEMPLATE_ID_WITH_PACKAGE_ID,
        None,
        amuletCodegen.DevelopmentFundCoupon.CHOICE_DevelopmentFundCoupon_DsoExpire.name,
        consuming = true,
        new amuletCodegen.DevelopmentFundCoupon_DsoExpire().toValue,
        new amuletCodegen.DevelopmentFundCoupon_DsoExpireResult(
          unclaimedDevelopmentFundCouponCid
        ).toValue,
      ),
      Seq(),
      dummyDomain,
    )
  }

  private def mkDevelopmentFundCouponWithdrawTx(
      offset: Long,
      developmentFundCouponCid: String,
      reason: String,
  ) = {
    val unclaimedDevelopmentFundCouponCid =
      new amuletCodegen.UnclaimedDevelopmentFundCoupon.ContractId(nextCid())
    mkExerciseTx(
      offset,
      exercisedEvent(
        developmentFundCouponCid,
        amuletCodegen.DevelopmentFundCoupon.TEMPLATE_ID_WITH_PACKAGE_ID,
        None,
        amuletCodegen.DevelopmentFundCoupon.CHOICE_DevelopmentFundCoupon_Withdraw.name,
        consuming = true,
        new amuletCodegen.DevelopmentFundCoupon_Withdraw(reason).toValue,
        new amuletCodegen.DevelopmentFundCoupon_WithdrawResult(
          unclaimedDevelopmentFundCouponCid
        ).toValue,
      ),
      Seq(),
      dummyDomain,
    )
  }

  private def mkDevelopmentFundCouponRejectTx(
      offset: Long,
      developmentFundCouponCid: String,
      reason: String,
  ) = {
    val unclaimedDevelopmentFundCouponCid =
      new amuletCodegen.UnclaimedDevelopmentFundCoupon.ContractId(nextCid())
    mkExerciseTx(
      offset,
      exercisedEvent(
        developmentFundCouponCid,
        amuletCodegen.DevelopmentFundCoupon.TEMPLATE_ID_WITH_PACKAGE_ID,
        None,
        amuletCodegen.DevelopmentFundCoupon.CHOICE_DevelopmentFundCoupon_Reject.name,
        consuming = true,
        new amuletCodegen.DevelopmentFundCoupon_Reject(reason).toValue,
        new amuletCodegen.DevelopmentFundCoupon_RejectResult(
          unclaimedDevelopmentFundCouponCid
        ).toValue,
      ),
      Seq(),
      dummyDomain,
    )
  }

  private def transferTransactionWithInputDevelopmentFundCouponTx(
      offset: Long,
      developmentFundCouponCid: amuletCodegen.DevelopmentFundCoupon.ContractId,
      beneficiary: PartyId,
  ) = {
    val amuletRulesCid = nextCid()
    val openMiningRoundCid = new roundCodegen.OpenMiningRound.ContractId(nextCid())

    mkExerciseTx(
      offset,
      // Note: Mocked transfer; it was not made fully consistent, as we only care about InputDevelopmentFundCoupon
      exercisedEvent(
        amuletRulesCid,
        amuletRulesCodegen.AmuletRules.TEMPLATE_ID_WITH_PACKAGE_ID,
        None,
        amuletRulesCodegen.AmuletRules.CHOICE_AmuletRules_Transfer.name,
        consuming = false,
        new amuletRulesCodegen.AmuletRules_Transfer(
          new amuletRulesCodegen.Transfer(
            beneficiary.toProtoPrimitive,
            beneficiary.toProtoPrimitive,
            Seq(
              new amuletRulesCodegen.transferinput.InputDevelopmentFundCoupon(
                developmentFundCouponCid
              ): TransferInput
            ).asJava,
            Seq().asJava,
            Optional.empty(),
          ),
          new amuletRulesCodegen.TransferContext(
            openMiningRoundCid,
            Map.empty[Round, roundCodegen.IssuingMiningRound.ContractId].asJava,
            Map.empty[String, amuletCodegen.ValidatorRight.ContractId].asJava,
            Optional.empty(),
          ),
          Optional.of(dsoParty.toProtoPrimitive),
        ).toValue,
        new amuletRulesCodegen.TransferResult(
          new Round(1),
          new amuletRulesCodegen.TransferSummary(
            damlDecimal(0),
            damlDecimal(0),
            damlDecimal(0),
            damlDecimal(0),
            Map.empty[String, amuletRulesCodegen.BalanceChange].asJava,
            damlDecimal(0.0),
            Seq().asJava,
            damlDecimal(0.0),
            damlDecimal(10.0),
            damlDecimal(0.15),
            Optional.empty(),
            Optional.empty(),
            Optional.of(damlDecimal(10.0)),
          ),
          Seq().asJava,
          Optional.empty(),
          Optional.empty(),
        ).toValue,
      ),
      Seq(),
      dummyDomain,
    )
  }

  protected def mkStore(
      endUserParty: PartyId,
      migrationId: Long = domainMigrationId,
  ): Future[UserWalletStore]

  override def mkTransferInputStore(partyId: PartyId): Future[TransferInputStore] = mkStore(partyId)

  protected lazy val acsOffset: Long = nextOffset()
  protected lazy val domain: String = dummyDomain.toProtoPrimitive
  protected lazy val synchronizerAlias: SynchronizerAlias = SynchronizerAlias.tryCreate(domain)
}

class DbUserWalletStoreTest
    extends UserWalletStoreTest
    with HasActorSystem
    with SplicePostgresTest
    with AcsJdbcTypes
    with AcsTables {

  override protected def mkStore(
      endUserParty: PartyId,
      migrationId: Long = domainMigrationId,
  ): Future[DbUserWalletStore] = {
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResources(
        DarResources.amulet.all ++
          DarResources.wallet.all ++
          DarResources.amuletNameService.all
      )
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)

    val store = new DbUserWalletStore(
      key = storeKey(endUserParty),
      storage = storage,
      loggerFactory = loggerFactory,
      retryProvider =
        RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
      DomainMigrationInfo(
        migrationId,
        None,
      ),
      participantId = mkParticipantId("UserWalletStoreTest"),
      IngestionConfig(),
    )
    for {
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
