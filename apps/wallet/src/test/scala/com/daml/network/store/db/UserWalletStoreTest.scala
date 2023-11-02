package com.daml.network.store.db

import com.daml.network.codegen.java.cc.{coin as coinCodegen, round as roundCodegen}
import com.daml.network.codegen.java.cc.round.types.Round
import com.daml.network.codegen.java.cn.directory as dirCodegen
import com.daml.network.codegen.java.cn.wallet.{
  install as installCodegen,
  payment as paymentCodegen,
  subscriptions as subsCodegen,
  transferoffer as transferOffersCodegen,
}
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.wallet.store.{UserWalletStore, UserWalletTxLogParser}
import com.daml.network.wallet.store.db.DbUserWalletStore
import com.daml.network.wallet.store.memory.InMemoryUserWalletStore
import com.daml.network.environment.{DarResources, RetryProvider}
import com.daml.network.store.{Limit, PageLimit, StoreTest}
import com.daml.network.store.TxLogStore.TransactionTreeSource
import com.daml.network.util.{Contract, ResourceTemplateDecoder, TemplateJsonDecoder}
import com.daml.network.wallet.store.UserWalletTxLogParser.TxLogEntry.TransferOfferStatus
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.ledger.offset.Offset
import com.digitalasset.canton.metrics.MetricHandle.NoOpMetricsFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{DomainAlias, HasActorSystem, HasExecutionContext}
import org.scalatest.{Assertion, Succeeded}

import java.time.Instant
import java.util.UUID
import scala.concurrent.Future

abstract class UserWalletStoreTest extends StoreTest with HasExecutionContext {

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
          eventually() {
            store.getInstall().futureValue.contractId should be(
              wantedContract.contractId
            )
          }
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
          eventually() {
            store1.getInstall().futureValue.contractId should be(
              install1.contractId
            )
            store2.getInstall().futureValue.contractId should be(
              install2.contractId
            )
          }
        }
      }

    }

    "listExpiredTransferOffers" should {

      "return correct results" in {
        for {
          store <- mkStore(user1)
          offer1 = transferOffer(user1, user2, 10.0, paymentCodegen.Currency.CC, time(1))
          offer3 = transferOffer(user1, user2, 20.0, paymentCodegen.Currency.USD, time(3))
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

          eventually() {
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

    }

    "listExpiredAcceptedTransferOffers" should {

      "return correct results" in {
        for {
          store <- mkStore(user1)
          offer1 = acceptedTransferOffer(user1, user2, 10.0, paymentCodegen.Currency.CC, time(1))
          offer3 = acceptedTransferOffer(user1, user2, 20.0, paymentCodegen.Currency.USD, time(3))
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

          eventually() {
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

    }

    "getLatestTransferOfferEventByTrackingId" should {

      "return None when missing" in {
        for {
          store <- mkStore(user1)
          result <- store.getLatestTransferOfferEventByTrackingId("nope")
        } yield {
          result.offset should be(initialOffset.toHexString)
          result.value should be(None)
        }
      }

      "return None after ingesting unrelated entries only" in {
        val treeSource = TransactionTreeSource.ForTesting()
        def mkUnrelatedEntry()(offset: String) = {
          val result = mintTransaction(user1, 11.0, 1L, 1.0)(offset)
          treeSource.addTree(result)
          result
        }
        for {
          store <- mkStore(user1, treeSource)
          _ <- dummyDomain.ingest(mkUnrelatedEntry())(store.multiDomainAcsStore)
          result <- store.getLatestTransferOfferEventByTrackingId("nope")
        } yield {
          result.value should be(None)
        }
      }

      "return entry stored by multiple stores" in {
        val treeSource = TransactionTreeSource.ForTesting()

        def mkSharedTx()(offset: String) = {
          val result = mkTransferOfferTx(offset, "trackingId", user1, user2, nextCid())
          treeSource.addTree(result)
          result
        }

        for {
          store1 <- mkStore(user1, treeSource)
          store2 <- mkStore(user2, treeSource)
          allStores = List(store1.multiDomainAcsStore, store2.multiDomainAcsStore)
          tx <- dummyDomain.ingestMulti(mkSharedTx())(allStores)
          result1 <- store1.getLatestTransferOfferEventByTrackingId("trackingId")
          result2 <- store2.getLatestTransferOfferEventByTrackingId("trackingId")
        } yield {
          result1.value.value.indexRecord.eventId should be(tx.getRootEventIds.loneElement)
          result2.value.value.indexRecord.eventId should be(tx.getRootEventIds.loneElement)
        }
      }

      "return the latest entry" in {
        val treeSource = TransactionTreeSource.ForTesting()
        val goodTransferOfferCid = nextCid()
        val goodAcceptedTransferOfferCid = nextCid()
        val badTransferOfferCid = nextCid()

        def mkTransferOffer(trackingId: String, cid: String)(offset: String) = {
          val result = mkTransferOfferTx(offset, trackingId, user1, user2, cid)
          treeSource.addTree(result)
          result
        }
        def mkAcceptTransfer(trackingId: String, cid: String)(offset: String) = {
          val result =
            mkAcceptTransferTx(offset, trackingId, user1, user2, cid)
          treeSource.addTree(result)
          result
        }

        for {
          store <- mkStore(user1, treeSource)
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
              TransferOfferStatus.Accepted(
                new transferOffersCodegen.AcceptedTransferOffer.ContractId(
                  goodAcceptedTransferOfferCid
                ),
                acceptedTree.getTransactionId,
              )
            )
          )
        }
      }

    }

    "listExpiredAppPaymentRequests" should {

      "return correct results" in {
        def paymentExpiringAt(t: Long) =
          appPaymentRequest(
            user1,
            provider1,
            10.0,
            paymentCodegen.Currency.CC,
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

          eventually() {
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

    }

    "listAppPaymentRequests and getAppPaymentRequest" should {

      "return correct results" in {
        for {
          Seq(store1, store2, storeP) <- Future.traverse(Seq(user1, user2, provider1)) {
            endUserParty =>
              for {
                store <- mkStore(endUserParty)
                _ <- dummyDomain.create(
                  walletInstall(endUserParty),
                  createdEventSignatories = Seq(endUserParty, svcParty),
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
            paymentCodegen.Currency.CC,
            time(1),
            s"request for $user1",
          )
          request2 = appPaymentRequest(
            user2,
            provider1,
            10.0,
            paymentCodegen.Currency.CC,
            time(1),
            s"request for $user2",
          )
          _ <- dummyDomain.createMulti(request1, createdEventSignatories = Seq(user1))(allAcsStores)
          _ <- dummyDomain.createMulti(request2, createdEventSignatories = Seq(user2))(allAcsStores)
        } yield {
          def resultCids(
              r: Contract[
                paymentCodegen.AppPaymentRequest.ContractId,
                paymentCodegen.AppPaymentRequest,
              ]
          ) = r.contractId.contractId

          eventually() {
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
            store1.getAppPaymentRequest(request1.contractId).map(resultCids).futureValue should be(
              request1.contractId.contractId
            )
            assertThrows[Throwable](store2.getAppPaymentRequest(request1.contractId).futureValue)
            assertThrows[Throwable](storeP.getAppPaymentRequest(request1.contractId).futureValue)
          }
        }
      }

      "return correct results if a request is archived" in {
        for {
          store1 <- mkStore(user1)
          _ <- dummyDomain.create(
            walletInstall(user1),
            createdEventSignatories = Seq(user1, svcParty),
          )(store1.multiDomainAcsStore)
          request1 = appPaymentRequest(
            user1,
            provider1,
            10.0,
            paymentCodegen.Currency.CC,
            time(1),
            "request1",
          )
          request2 = appPaymentRequest(
            user1,
            provider1,
            20.0,
            paymentCodegen.Currency.CC,
            time(2),
            "request2",
          )
          request3 = appPaymentRequest(
            user1,
            provider1,
            30.0,
            paymentCodegen.Currency.CC,
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
              r: Contract[
                paymentCodegen.AppPaymentRequest.ContractId,
                paymentCodegen.AppPaymentRequest,
              ]
          ) =
            r.contractId.contractId

          eventually() {
            // Listing - only request1 should be visible
            val actual = store1.listAppPaymentRequests().futureValue.map(resultCids)
            val expected = Seq(
              request1.contractId.contractId
            )
            actual should contain theSameElementsInOrderAs expected

            // Pointwise lookup - only request1 should be visible
            store1.getAppPaymentRequest(request1.contractId).map(resultCids).futureValue should be(
              request1.contractId.contractId
            )
            assertThrows[Throwable](store1.getAppPaymentRequest(request2.contractId).futureValue)
            assertThrows[Throwable](store1.getAppPaymentRequest(request3.contractId).futureValue)
          }
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

          eventually() {
            cidsAt(time(1)) should be(empty)
            cidsAt(time(2)) should contain theSameElementsAs Seq(idleState1.contractId)
            cidsAt(time(3)) should contain theSameElementsAs Seq(
              idleState2.contractId,
              idleState1.contractId,
            )
          }
        }
      }

    }

    "listSubscriptions" should {

      def resultCids(store: UserWalletStore) = store
        .listSubscriptions()
        .futureValue
        .map(r =>
          r.subscription.contractId.contractId -> (r.state match {
            case s: UserWalletStore.SubscriptionIdleState => s.contract.contractId.contractId
            case s: UserWalletStore.SubscriptionPaymentState =>
              s.contract.contractId.contractId
          })
        )

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
          lockedCoinCid = new coinCodegen.LockedCoin.ContractId(nextCid())
          (subscription2, state2) = subscriptionInPaymentState(
            user1,
            provider1,
            payData,
            time(1),
            lockedCoinCid,
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
          eventually() {
            val actual = resultCids(store1)
            val expected = Seq(
              subscription1.contractId.contractId -> state1.contractId.contractId,
              subscription2.contractId.contractId -> state2.contractId.contractId,
            )
            actual should contain theSameElementsAs expected
          }
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
          lockedCoinCid = new coinCodegen.LockedCoin.ContractId(nextCid())
          state2 = subscriptionPaymentState(
            subscription1,
            payData,
            time(1),
            lockedCoinCid,
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
          eventually() {
            val actual = resultCids(store1)
            val expected = Seq(
              subscription1.contractId.contractId -> state2.contractId.contractId
            )
            actual should contain theSameElementsAs expected
          }
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
          eventually() {
            val actual = resultCids(store1)
            actual should be(empty)
          }
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
          eventually() {
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

    }

    "listSortedCoinsAndQuantity" should {

      "return correct results" in {
        for {
          store <- mkStore(user1)
          _ <- dummyDomain.ingest(mintTransaction(user1, 11.0, 1L, 1.0))(store.multiDomainAcsStore)
          _ <- dummyDomain.ingest(mintTransaction(user1, 12.0, 2L, 2.0))(store.multiDomainAcsStore)
          _ <- dummyDomain.ingest(mintTransaction(user1, 13.0, 3L, 4.0))(store.multiDomainAcsStore)
          _ <- dummyDomain.ingest(mintTransaction(user1, 10.0, 4L, 1.0))(store.multiDomainAcsStore)
        } yield {
          def top3At(round: Long): Seq[Double] =
            store
              .listSortedCoinsAndQuantity(round, PageLimit.tryCreate(3))
              .futureValue
              .map(_._1.toDouble)

          eventually() {
            // Values of the 4 coins by time:
            // 11 10 09 08 07 06 05 04 03 02
            //    12 10 08 06 04 02 00 00 00
            //       13 09 05 01 00 00 00 00
            //          10 09 08 07 06 05 04
            // Note: need to start at round 4, as listSortedCoinsAndQuantity() does not filter out coins
            // created after the given round
            top3At(4L) should contain theSameElementsAs Seq(10.0, 9.0, 8.0)
            top3At(5L) should contain theSameElementsAs Seq(9.0, 7.0, 6.0)
            top3At(6L) should contain theSameElementsAs Seq(8.0, 6.0, 4.0)
            top3At(7L) should contain theSameElementsAs Seq(7.0, 5.0, 2.0)
            top3At(8L) should contain theSameElementsAs Seq(6.0, 4.0)
          }
        }
      }

    }

    "listTransactions" should {

      // This helper is similar to the one in WalletTxLogTestUtil
      type CheckTxHistoryFn = PartialFunction[UserWalletTxLogParser.TxLogEntry, Assertion]
      def checkTxHistory(
          store: UserWalletStore,
          expected: Seq[CheckTxHistoryFn],
          previousEventId: Option[String] = None,
      ): Unit = {

        val actual =
          store
            .listTransactions(previousEventId, limit = PageLimit.tryCreate(Limit.MaxPageSize))
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
            .unfold[Seq[UserWalletTxLogParser.TxLogEntry], Option[String]](previousEventId)(
              beginAfterId => {
                val entries =
                  store.listTransactions(beginAfterId, limit = PageLimit.tryCreate(2)).futureValue
                if (entries.isEmpty)
                  None
                else
                  Some(entries -> Some(entries.last.indexRecord.eventId))
              }
            )
            .toSeq
            .flatten

          paginatedResult should contain theSameElementsInOrderAs actual
        }
      }

      "return entries in correct order" in {

        val treeSource = TransactionTreeSource.ForTesting()
        def mkMint(amount: Double)(offset: String) = {
          val result = mintTransaction(user1, amount, 1, 1)(offset)
          treeSource.addTree(result)
          result
        }

        for {
          store <- mkStore(user1, treeSource)
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
              { case logEntry: UserWalletTxLogParser.TxLogEntry.BalanceChange =>
                logEntry.transactionSubtype shouldBe UserWalletTxLogParser.TxLogEntry.BalanceChange.Mint
                logEntry.amount shouldBe 5.0
              },
              { case logEntry: UserWalletTxLogParser.TxLogEntry.BalanceChange =>
                logEntry.transactionSubtype shouldBe UserWalletTxLogParser.TxLogEntry.BalanceChange.Mint
                logEntry.amount shouldBe 4.0
              },
              { case logEntry: UserWalletTxLogParser.TxLogEntry.BalanceChange =>
                logEntry.transactionSubtype shouldBe UserWalletTxLogParser.TxLogEntry.BalanceChange.Mint
                logEntry.amount shouldBe 3.0
              },
              { case logEntry: UserWalletTxLogParser.TxLogEntry.BalanceChange =>
                logEntry.transactionSubtype shouldBe UserWalletTxLogParser.TxLogEntry.BalanceChange.Mint
                logEntry.amount shouldBe 2.0
              },
              { case logEntry: UserWalletTxLogParser.TxLogEntry.BalanceChange =>
                logEntry.transactionSubtype shouldBe UserWalletTxLogParser.TxLogEntry.BalanceChange.Mint
                logEntry.amount shouldBe 1.0
              },
            ),
          )
          Succeeded
        }
      }
    }

    "listDirectoryEntries" should {
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
          directoryEntryContext1 = directoryEntryContext(
            user1,
            "user1",
            subscriptionRequest1,
            provider1,
          )
          directoryEntry1 = directoryEntry(
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
            directoryEntryContext1,
            createdEventSignatories = Seq(user1, provider1),
          )(
            store1.multiDomainAcsStore
          )
          _ <- dummyDomain.create(directoryEntry1, createdEventSignatories = Seq(user1, provider1))(
            store1.multiDomainAcsStore
          )
        } yield {
          eventually() {
            val actual = store1.listDirectoryEntries().futureValue
            val expected = Seq(
              UserWalletStore.DirectoryEntryWithPayData(
                contractId = directoryEntry1.contractId,
                expiresAt = directoryEntry1.payload.expiresAt,
                entryName = directoryEntry1.payload.name,
                amount = state1.payload.payData.paymentAmount.amount,
                currency = state1.payload.payData.paymentAmount.currency,
                paymentInterval = state1.payload.payData.paymentInterval,
                paymentDuration = state1.payload.payData.paymentDuration,
              )
            )
            actual should contain theSameElementsAs expected
          }
        }
      }

    }
  }

  private lazy val provider1 = providerParty(1)
  private lazy val user1 = userParty(1)
  private lazy val user2 = userParty(2)
  private lazy val validator = mkPartyId(s"validator")
  protected def storeKey(endUserParty: PartyId) = UserWalletStore.Key(
    svcParty = svcParty,
    validatorParty = validator,
    endUserName = endUserParty.toProtoPrimitive,
    endUserParty = endUserParty,
  )

  private def time(n: Long) = CantonTimestamp.ofEpochSecond(n)

  private def walletInstall(endUserParty: PartyId) = {
    val templateId = installCodegen.WalletAppInstall.TEMPLATE_ID
    val template = new installCodegen.WalletAppInstall(
      svcParty.toProtoPrimitive,
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
      currency: paymentCodegen.Currency,
      expiresAt: CantonTimestamp,
      trackingId: String = UUID.randomUUID().toString,
  ) = {
    val templateId = transferOffersCodegen.TransferOffer.TEMPLATE_ID
    val template = new transferOffersCodegen.TransferOffer(
      sender.toProtoPrimitive,
      receiver.toProtoPrimitive,
      svcParty.toProtoPrimitive,
      new paymentCodegen.PaymentAmount(new java.math.BigDecimal(amount), currency),
      s"Payment from $sender to $receiver for $amount $currency, expiring at $expiresAt",
      expiresAt.toInstant,
      trackingId,
    )
    contract(
      identifier = templateId,
      contractId = new transferOffersCodegen.TransferOffer.ContractId(nextCid()),
      payload = template,
    )
  }

  private def acceptedTransferOffer(
      sender: PartyId,
      receiver: PartyId,
      amount: Double,
      currency: paymentCodegen.Currency,
      expiresAt: CantonTimestamp,
      trackingId: String = UUID.randomUUID().toString,
      cid: String = nextCid(),
  ) = {
    val templateId = transferOffersCodegen.AcceptedTransferOffer.TEMPLATE_ID
    val template = new transferOffersCodegen.AcceptedTransferOffer(
      sender.toProtoPrimitive,
      receiver.toProtoPrimitive,
      svcParty.toProtoPrimitive,
      new paymentCodegen.PaymentAmount(new java.math.BigDecimal(amount), currency),
      expiresAt.toInstant,
      trackingId,
    )
    contract(
      identifier = templateId,
      contractId = new transferOffersCodegen.AcceptedTransferOffer.ContractId(cid),
      payload = template,
    )
  }

  private def appPaymentRequest(
      sender: PartyId,
      provider: PartyId,
      amount: Double,
      currency: paymentCodegen.Currency,
      expiresAt: CantonTimestamp,
      description: String,
  ) = {
    val templateId = paymentCodegen.AppPaymentRequest.TEMPLATE_ID
    val template = new paymentCodegen.AppPaymentRequest(
      sender.toProtoPrimitive,
      java.util.List.of(
        new paymentCodegen.ReceiverAmount(
          provider.toProtoPrimitive,
          new paymentCodegen.PaymentAmount(new java.math.BigDecimal(amount), currency),
        )
      ),
      provider.toProtoPrimitive,
      svcParty.toProtoPrimitive,
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
    val templateId = subsCodegen.Subscription.TEMPLATE_ID
    val template = new subsCodegen.Subscription(
      new subsCodegen.SubscriptionData(
        user.toProtoPrimitive,
        provider.toProtoPrimitive,
        provider.toProtoPrimitive,
        svcParty.toProtoPrimitive,
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
    val templateId = subsCodegen.SubscriptionIdleState.TEMPLATE_ID
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
      lockedCoinCid: coinCodegen.LockedCoin.ContractId,
      round: Long,
  ) = {
    val templateId = subsCodegen.SubscriptionPayment.TEMPLATE_ID
    val template = new subsCodegen.SubscriptionPayment(
      subscription.contractId,
      subscription.payload.subscriptionData,
      payData,
      thisPaymentDueAt.toInstant,
      // Note: this targetAmount is only correct for CC payments.
      // USD payments would need to apply coin price, but we don't care about the exact value.
      payData.paymentAmount.amount,
      lockedCoinCid,
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
      lockedCoinCid: coinCodegen.LockedCoin.ContractId,
      round: Long,
  ) = {
    val reference = new subsCodegen.SubscriptionRequest.ContractId(nextCid())
    val subscriptionContract = subscription(sender, receiver, reference)
    val paymentStateContract = subscriptionPaymentState(
      subscriptionContract,
      payData,
      thisPaymentDueAt,
      lockedCoinCid,
      round,
    )
    (subscriptionContract, paymentStateContract)
  }

  private def subscriptionPayData(
      amount: Double = 10.0,
      currency: paymentCodegen.Currency = paymentCodegen.Currency.CC,
      paymentIntervalSeconds: Long = 60L,
      paymentDurationSeconds: Long = 1L,
  ): subsCodegen.SubscriptionPayData = {
    new subsCodegen.SubscriptionPayData(
      new paymentCodegen.PaymentAmount(new java.math.BigDecimal(amount).setScale(10), currency),
      new RelTime(paymentIntervalSeconds * Limit.MaxPageSize * Limit.MaxPageSize),
      new RelTime(paymentDurationSeconds * Limit.MaxPageSize * Limit.MaxPageSize),
    )
  }

  private def subscriptionRequest(
      subscriptionData: subsCodegen.SubscriptionData,
      payData: subsCodegen.SubscriptionPayData,
  ) = {
    val templateId = subsCodegen.SubscriptionRequest.TEMPLATE_ID
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

  protected def directoryEntry(
      user: PartyId,
      name: String,
      provider: PartyId = providerParty(0),
      entryUrl: String = "https://cns-entry-url.com",
      entryDescription: String = "Sample fake description",
  ) = {
    val templateId = dirCodegen.DirectoryEntry.TEMPLATE_ID
    val template = new dirCodegen.DirectoryEntry(
      user.toProtoPrimitive,
      provider.toProtoPrimitive,
      name,
      entryUrl,
      entryDescription,
      Instant.now().plusSeconds(3600),
    )
    contract(
      identifier = templateId,
      contractId = new dirCodegen.DirectoryEntry.ContractId(nextCid()),
      payload = template,
    )
  }

  protected def directoryEntryContext(
      user: PartyId,
      name: String,
      subscriptionRequest: subsCodegen.SubscriptionRequest.ContractId,
      provider: PartyId = providerParty(0),
      entryUrl: String = "https://cns-entry-url.com",
      entryDescription: String = "Sample fake description",
  ) = {
    val templateId = dirCodegen.DirectoryEntryContext.TEMPLATE_ID
    val template = new dirCodegen.DirectoryEntryContext(
      svcParty.toProtoPrimitive,
      provider.toProtoPrimitive,
      user.toProtoPrimitive,
      name,
      entryUrl,
      entryDescription,
      subscriptionRequest,
    )
    contract(
      identifier = templateId,
      contractId = new dirCodegen.DirectoryEntryContext.ContractId(nextCid()),
      payload = template,
    )
  }

  /** A CoinRules_Mint exercise event with one child Coin create event */
  private def mintTransaction(
      receiver: PartyId,
      amount: Double,
      round: Long,
      ratePerRound: Double,
      coinPrice: Double = 1.0,
  )(
      offset: String
  ) = {
    val coinContract = coin(receiver, amount, round, ratePerRound)

    // This is a non-consuming choice, the store should not mind that some of the referenced contracts don't exist
    val coinRulesCid = nextCid()
    val openMiningRoundCid = nextCid()

    mkExerciseTx(
      offset,
      exercisedEvent(
        coinRulesCid,
        coinCodegen.CoinRules.TEMPLATE_ID,
        None,
        coinCodegen.CoinRules.CHOICE_CoinRules_Mint.name,
        consuming = false,
        new coinCodegen.CoinRules_Mint(
          receiver.toProtoPrimitive,
          coinContract.payload.amount.initialAmount,
          new roundCodegen.OpenMiningRound.ContractId(openMiningRoundCid),
        ).toValue,
        new coinCodegen.CoinCreateSummary[coinCodegen.Coin.ContractId](
          coinContract.contractId,
          new java.math.BigDecimal(coinPrice),
          new roundCodegen.types.Round(round),
        )
          .toValue(_.toValue),
      ),
      Seq(toCreatedEvent(coinContract, Seq(receiver))),
    )
  }

  private def mkTransferOfferTx(
      offset: String,
      trackingId: String,
      sender: PartyId,
      receiver: PartyId,
      transferOfferCid: String,
  ) = {
    val walletAppInstallCid = nextCid()

    mkExerciseTx(
      offset,
      exercisedEvent(
        walletAppInstallCid,
        installCodegen.WalletAppInstall.TEMPLATE_ID,
        None,
        installCodegen.WalletAppInstall.CHOICE_WalletAppInstall_CreateTransferOffer.name,
        consuming = false,
        new installCodegen.WalletAppInstall_CreateTransferOffer(
          "receiver",
          new paymentCodegen.PaymentAmount(BigDecimal(1.0).bigDecimal, paymentCodegen.Currency.CC),
          "desc",
          Instant.now().plusSeconds(60),
          trackingId,
        ).toValue,
        new transferOffersCodegen.TransferOffer.ContractId(transferOfferCid).toValue,
      ),
      Seq(
        toCreatedEvent(
          transferOffer(
            sender,
            receiver,
            1.0,
            paymentCodegen.Currency.CC,
            CantonTimestamp.now().plusSeconds(60),
            trackingId,
          ),
          Seq(sender, receiver),
        )
      ),
    )
  }

  private def mkAcceptTransferTx(
      offset: String,
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
        transferOffersCodegen.TransferOffer.TEMPLATE_ID,
        None,
        transferOffersCodegen.TransferOffer.CHOICE_TransferOffer_Accept.name,
        consuming = false,
        new transferOffersCodegen.TransferOffer_Accept().toValue,
        new transferOffersCodegen.AcceptedTransferOffer.ContractId(acceptedTransferOfferCid).toValue,
      ),
      Seq(
        toCreatedEvent(
          acceptedTransferOffer(
            sender,
            receiver,
            1.0,
            paymentCodegen.Currency.CC,
            CantonTimestamp.now().plusSeconds(60),
            trackingId,
            acceptedTransferOfferCid,
          ),
          Seq(sender, receiver),
        )
      ),
    )
  }

  // Note: The TransactionTreeSource is only used to read TxLog entries. For most tests it is never used.
  protected def mkStore(
      endUserParty: PartyId,
      transactionTreeSource: TransactionTreeSource = TransactionTreeSource.Unused,
  ): Future[UserWalletStore]

  lazy val initialOffset = Offset.fromByteArray(Array(1, 2, 3).map(_.toByte))
  lazy val domain = dummyDomain.toProtoPrimitive
  lazy val domainAlias = DomainAlias.tryCreate(domain)
}

class InMemoryUserWalletStoreTest extends UserWalletStoreTest {
  override protected def mkStore(
      endUserParty: PartyId,
      transactionTreeSource: TransactionTreeSource,
  ): Future[InMemoryUserWalletStore] = {
    val store = new InMemoryUserWalletStore(
      key = storeKey(endUserParty),
      loggerFactory = loggerFactory,
      transactionTreeSource = transactionTreeSource,
      retryProvider =
        RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
    )
    for {
      _ <- store.multiDomainAcsStore.ingestionSink.initialize()
      _ <- store.multiDomainAcsStore.ingestionSink
        .ingestAcs(initialOffset.toHexString, Seq.empty, Seq.empty, Seq.empty)
      _ <- store.domains.ingestionSink.ingestConnectedDomains(
        Map(domainAlias -> dummyDomain)
      )
    } yield store
  }
}

class DbUserWalletStoreTest
    extends UserWalletStoreTest
    with HasActorSystem
    with CNPostgresTest
    with AcsJdbcTypes
    with AcsTables {

  override protected def mkStore(
      endUserParty: PartyId,
      transactionTreeSource: TransactionTreeSource,
  ): Future[DbUserWalletStore] = {
    val packageSignatures =
      ResourceTemplateDecoder.loadPackageSignaturesFromResources(
        DarResources.cantonCoin.all ++
          DarResources.wallet.all ++
          DarResources.directoryService.all
      )
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)

    val store = new DbUserWalletStore(
      key = storeKey(endUserParty),
      storage = storage,
      loggerFactory = loggerFactory,
      transactionTreeSource = transactionTreeSource,
      retryProvider =
        RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
    )
    for {
      _ <- store.multiDomainAcsStore.ingestionSink.initialize()
      _ <- store.multiDomainAcsStore.ingestionSink
        .ingestAcs(initialOffset.toHexString, Seq.empty, Seq.empty, Seq.empty)
      _ <- store.domains.ingestionSink.ingestConnectedDomains(
        Map(domainAlias -> dummyDomain)
      )
    } yield store
  }

  override protected def cleanDb(storage: DbStorage): Future[?] =
    for {
      _ <- resetAllCnAppTables(storage)
    } yield ()
}
