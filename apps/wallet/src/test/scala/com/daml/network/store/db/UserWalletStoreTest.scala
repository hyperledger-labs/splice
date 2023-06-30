package com.daml.network.store.db

import com.daml.ledger.javaapi.data.ContractMetadata
import com.daml.network.codegen.java.cc.api.v1 as ccApiCodegen
import com.daml.network.codegen.java.cc.{
  coin as coinCodegen,
  fees as feesCodegen,
  round as roundCodegen,
}
import com.daml.network.codegen.java.cn.wallet.{
  install as installCodegen,
  payment as paymentCodegen,
  subscriptions as subsCodegen,
  transferoffer as transferOffersCodegen,
}
import com.daml.network.codegen.java.cn.scripts.testwallet as testWalletCodegen
import com.daml.network.codegen.java.cn.scripts.wallet.testsubscriptions as testSubscCodegen
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.wallet.store.{UserWalletStore, UserWalletTxLogParser}
import com.daml.network.wallet.store.db.DbUserWalletStore
import com.daml.network.wallet.store.memory.InMemoryUserWalletStore
import com.daml.network.environment.RetryProvider
import com.daml.network.store.StoreTest
import com.daml.network.store.TxLogStore.TransactionTreeSource
import com.daml.network.util.{Contract, ResourceTemplateDecoder, TemplateJsonDecoder}
import com.daml.network.wallet.store.UserWalletStore.{AppPaymentRequest, SubscriptionRequest}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.ledger.offset.Offset
import com.digitalasset.canton.protocol.LfContractId
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{DomainAlias, HasActorSystem, HasExecutionContext}
import com.google.protobuf
import org.scalatest.{Assertion, Succeeded}

import scala.concurrent.Future

abstract class UserWalletStoreTest extends StoreTest with HasExecutionContext {

  "UserWalletStore" should {

    "lookupInstall" should {

      "return the install of the user" in {
        for {
          store <- mkStore(user1)
          unwantedContract = walletInstall(user2)
          wantedContract = walletInstall(user1)
          _ <- dummyDomain.create(unwantedContract)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(wantedContract)(store.multiDomainAcsStore)
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
          _ <- dummyDomain.createMulti(install1)(allAcsStores)
          _ <- dummyDomain.createMulti(install2)(allAcsStores)
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
          _ <- dummyDomain.create(offer1)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(offer3)(store.multiDomainAcsStore)
        } yield {
          def cidsAt(t: CantonTimestamp) = store
            .listExpiredTransferOffers(t, 10)(TraceContext.empty)
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
          _ <- dummyDomain.create(offer1)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(offer3)(store.multiDomainAcsStore)
        } yield {
          def cidsAt(t: CantonTimestamp) = store
            .listExpiredAcceptedTransferOffers(t, 10)(TraceContext.empty)
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

    "listExpiredAppPaymentRequests" should {

      "return correct results" in {
        def paymentExpiringAt(t: Long) =
          appPaymentRequestWithOffer(user1, provider1, 10.0, paymentCodegen.Currency.CC, time(t))

        for {
          store <- mkStore(user1)
          (offer1, request1) = paymentExpiringAt(1)
          (offer3, request3) = paymentExpiringAt(3)
          _ <- dummyDomain.create(offer1)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(request1)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(offer3)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(request3)(store.multiDomainAcsStore)
        } yield {
          def cidsAt(t: CantonTimestamp) = store
            .listExpiredAppPaymentRequests(t, 10)(TraceContext.empty)
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
          store1 <- mkStore(user1)
          store2 <- mkStore(user2)
          storeP <- mkStore(provider1)
          allAcsStores = Seq(
            store1.multiDomainAcsStore,
            store2.multiDomainAcsStore,
            storeP.multiDomainAcsStore,
          )
          (offer1, request1) = appPaymentRequestWithOffer(
            user1,
            provider1,
            10.0,
            paymentCodegen.Currency.CC,
            time(1),
          )
          (offer2, request2) = appPaymentRequestWithOffer(
            user2,
            provider1,
            10.0,
            paymentCodegen.Currency.CC,
            time(1),
          )
          _ <- dummyDomain.createMulti(offer1)(allAcsStores)
          _ <- dummyDomain.createMulti(request1)(allAcsStores)
          _ <- dummyDomain.createMulti(offer2)(allAcsStores)
          _ <- dummyDomain.createMulti(request2)(allAcsStores)
        } yield {
          def resultCids(r: AppPaymentRequest) =
            r.deliveryOffer.contractId.contractId -> r.appPaymentRequest.contractId.contractId

          eventually() {
            // Listing - user only sees their own request
            val actual1 = store1.listAppPaymentRequests.futureValue.map(resultCids)
            val expected1 = Seq(
              offer1.contractId.contractId -> request1.contractId.contractId
            )
            actual1 should contain theSameElementsInOrderAs expected1

            // Listing - user only sees their own request
            val actual2 = store2.listAppPaymentRequests.futureValue.map(resultCids)
            val expected2 = Seq(
              offer2.contractId.contractId -> request2.contractId.contractId
            )
            actual2 should contain theSameElementsInOrderAs expected2

            // Listing - provider doesn't see any request
            val actualP = storeP.listAppPaymentRequests.futureValue.map(resultCids)
            actualP should be(empty)

            // Pointwise lookup - only user1 store should see request1
            store1.getAppPaymentRequest(request1.contractId).map(resultCids).futureValue should be(
              offer1.contractId.contractId -> request1.contractId.contractId
            )
            assertThrows[Throwable](store2.getAppPaymentRequest(request1.contractId).futureValue)
            assertThrows[Throwable](storeP.getAppPaymentRequest(request1.contractId).futureValue)
          }
        }
      }

      "return correct results if a request is archived" in {
        for {
          store1 <- mkStore(user1)
          (offer1, request1) = appPaymentRequestWithOffer(
            user1,
            provider1,
            10.0,
            paymentCodegen.Currency.CC,
            time(1),
          )
          (offer2, request2) = appPaymentRequestWithOffer(
            user1,
            provider1,
            20.0,
            paymentCodegen.Currency.CC,
            time(2),
          )
          (offer3, request3) = appPaymentRequestWithOffer(
            user1,
            provider1,
            30.0,
            paymentCodegen.Currency.CC,
            time(3),
          )
          _ <- dummyDomain.create(offer1)(store1.multiDomainAcsStore)
          _ <- dummyDomain.create(request1)(store1.multiDomainAcsStore)
          // Withdrawing or rejecting a request atomically archives both the offer and the request
          _ <- dummyDomain.create(offer2)(store1.multiDomainAcsStore)
          _ <- dummyDomain.create(request2)(store1.multiDomainAcsStore)
          _ <- dummyDomain.archive(offer2)(store1.multiDomainAcsStore)
          _ <- dummyDomain.archive(request2)(store1.multiDomainAcsStore)
          // Accepting a request archives the request, but not the offer
          _ <- dummyDomain.create(offer3)(store1.multiDomainAcsStore)
          _ <- dummyDomain.create(request3)(store1.multiDomainAcsStore)
          _ <- dummyDomain.archive(request3)(store1.multiDomainAcsStore)
        } yield {
          def resultCids(r: AppPaymentRequest) =
            r.deliveryOffer.contractId.contractId -> r.appPaymentRequest.contractId.contractId

          eventually() {
            // Listing - only request1 should be visible
            val actual = store1.listAppPaymentRequests.futureValue.map(resultCids)
            val expected = Seq(
              offer1.contractId.contractId -> request1.contractId.contractId
            )
            actual should contain theSameElementsInOrderAs expected

            // Pointwise lookup - only request1 should be visible
            store1.getAppPaymentRequest(request1.contractId).map(resultCids).futureValue should be(
              offer1.contractId.contractId -> request1.contractId.contractId
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
          (context2, subscription2, idleState2) = subscriptionDueAt(3, 1)
          (context1, subscription1, idleState1) = subscriptionDueAt(3, 2)
          _ <- dummyDomain.create(context2)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(subscription2)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(idleState2)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(context1)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(subscription1)(store.multiDomainAcsStore)
          _ <- dummyDomain.create(idleState1)(store.multiDomainAcsStore)
        } yield {
          def cidsAt(t: CantonTimestamp) = store
            .listSubscriptionStatesReadyForPayment(t, 10)(TraceContext.empty)
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
          (context1, subscription1, state1) = subscriptionInIdleState(
            user1,
            provider1,
            payData,
            time(1),
          )
          lockedCoinCid = new ccApiCodegen.coin.LockedCoin.ContractId(nextCid())
          (context2, subscription2, state2) = subscriptionInPaymentState(
            user1,
            provider1,
            payData,
            time(1),
            lockedCoinCid,
            1L,
          )
          _ <- dummyDomain.createMulti(context1)(allAcsStores)
          _ <- dummyDomain.createMulti(subscription1)(allAcsStores)
          _ <- dummyDomain.createMulti(state1)(allAcsStores)
          _ <- dummyDomain.createMulti(context2)(allAcsStores)
          _ <- dummyDomain.createMulti(subscription2)(allAcsStores)
          _ <- dummyDomain.createMulti(state2)(allAcsStores)
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
          (context1, subscription1, state1) = subscriptionInIdleState(
            user1,
            provider1,
            payData,
            time(1),
          )
          lockedCoinCid = new ccApiCodegen.coin.LockedCoin.ContractId(nextCid())
          state2 = subscriptionPaymentState(
            subscription1,
            payData,
            time(1),
            lockedCoinCid,
            1,
          )
          _ <- dummyDomain.create(context1)(store1.multiDomainAcsStore)
          _ <- dummyDomain.create(subscription1)(store1.multiDomainAcsStore)
          // After a payment has been made to extend a subscription, the old state is archived and a new one is created
          _ <- dummyDomain.archive(state1)(store1.multiDomainAcsStore)
          _ <- dummyDomain.create(state2)(store1.multiDomainAcsStore)
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
          (context1, subscription1, state1) = subscriptionInIdleState(
            user1,
            provider1,
            payData,
            time(1),
          )
          _ <- dummyDomain.create(context1)(store1.multiDomainAcsStore)
          _ <- dummyDomain.create(subscription1)(store1.multiDomainAcsStore)
          // If a subscription is expired because of a missed payment, all 3 contracts are archived
          _ <- dummyDomain.archive(state1)(store1.multiDomainAcsStore)
          _ <- dummyDomain.archive(subscription1)(store1.multiDomainAcsStore)
          _ <- dummyDomain.archive(context1)(store1.multiDomainAcsStore)
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
          context1 = subscriptionContext(user1, provider1)
          subscription1 = subscription(context1)
          request1 = subscriptionRequest(subscription1.payload, payData)
          context2 = subscriptionContext(user2, provider1)
          subscription2 = subscription(context2)
          request2 = subscriptionRequest(subscription2.payload, payData)
          _ <- dummyDomain.createMulti(context1)(allAcsStores)
          _ <- dummyDomain.createMulti(subscription1)(allAcsStores)
          _ <- dummyDomain.createMulti(request1)(allAcsStores)
          _ <- dummyDomain.createMulti(context2)(allAcsStores)
          _ <- dummyDomain.createMulti(subscription2)(allAcsStores)
          _ <- dummyDomain.createMulti(request2)(allAcsStores)
        } yield {
          def resultCids(r: SubscriptionRequest) =
            r.context.contractId.contractId -> r.subscription.contractId.contractId

          eventually() {
            // Listing - user only sees their own request
            val actual1 = store1.listSubscriptionRequests().futureValue.map(resultCids)
            val expected1 = Seq(
              context1.contractId.contractId -> request1.contractId.contractId
            )
            actual1 should contain theSameElementsInOrderAs expected1

            // Listing - user only sees their own request
            val actual2 = store2.listSubscriptionRequests().futureValue.map(resultCids)
            val expected2 = Seq(
              context2.contractId.contractId -> request2.contractId.contractId
            )
            actual2 should contain theSameElementsInOrderAs expected2

            // Listing - provider doesn't see any request
            val actualP = storeP.listSubscriptionRequests().futureValue.map(resultCids)
            actualP should be(empty)

            // Pointwise lookup - only user1 store should see request1
            store1
              .getSubscriptionRequest(request1.contractId)
              .map(resultCids)
              .futureValue should be(
              context1.contractId.contractId -> request1.contractId.contractId
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
            store.listSortedCoinsAndQuantity(3, round).futureValue.map(_._1.toDouble)

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

        val actual = store.listTransactions(previousEventId, limit = 100000).futureValue
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
                val entries = store.listTransactions(beginAfterId, limit = 2).futureValue
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
          eventually() {
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

  private var cIdCounter = 0
  private def nextCid() = {
    cIdCounter += 1
    // Note: contract ids that appear in contract payloads need to pass contract id validation,
    // otherwise JSON serialization will fail when storing contracts in the database.
    LfContractId.assertFromString("00" + f"$cIdCounter%064x").coid
  }

  private def walletInstall(endUserParty: PartyId) = {
    val templateId = installCodegen.WalletAppInstall.TEMPLATE_ID
    val template = new installCodegen.WalletAppInstall(
      svcParty.toProtoPrimitive,
      validator.toProtoPrimitive,
      endUserParty.toProtoPrimitive,
      endUserParty.toProtoPrimitive,
    )
    Contract(
      identifier = templateId,
      contractId = new installCodegen.WalletAppInstall.ContractId(nextCid()),
      payload = template,
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )
  }

  private def transferOffer(
      sender: PartyId,
      receiver: PartyId,
      amount: Double,
      currency: paymentCodegen.Currency,
      expiresAt: CantonTimestamp,
  ) = {
    val templateId = transferOffersCodegen.TransferOffer.TEMPLATE_ID
    val template = new transferOffersCodegen.TransferOffer(
      sender.toProtoPrimitive,
      receiver.toProtoPrimitive,
      svcParty.toProtoPrimitive,
      new paymentCodegen.PaymentAmount(new java.math.BigDecimal(amount), currency),
      s"Payment from $sender to $receiver for $amount $currency, expiring at $expiresAt",
      expiresAt.toInstant,
    )
    Contract(
      identifier = templateId,
      contractId = new transferOffersCodegen.TransferOffer.ContractId(nextCid()),
      payload = template,
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )
  }

  private def acceptedTransferOffer(
      sender: PartyId,
      receiver: PartyId,
      amount: Double,
      currency: paymentCodegen.Currency,
      expiresAt: CantonTimestamp,
  ) = {
    val templateId = transferOffersCodegen.AcceptedTransferOffer.TEMPLATE_ID
    val template = new transferOffersCodegen.AcceptedTransferOffer(
      sender.toProtoPrimitive,
      receiver.toProtoPrimitive,
      svcParty.toProtoPrimitive,
      new paymentCodegen.PaymentAmount(new java.math.BigDecimal(amount), currency),
      expiresAt.toInstant,
    )
    Contract(
      identifier = templateId,
      contractId = new transferOffersCodegen.AcceptedTransferOffer.ContractId(nextCid()),
      payload = template,
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )
  }

  private def deliveryOffer(
      sender: PartyId
  ) = {
    val templateId = testWalletCodegen.TestDeliveryOffer.TEMPLATE_ID
    val template = new testWalletCodegen.TestDeliveryOffer(
      svcParty.toProtoPrimitive,
      sender.toProtoPrimitive,
      s"Party $sender promises to deliver something",
    )
    Contract(
      identifier = templateId,
      contractId = new testWalletCodegen.TestDeliveryOffer.ContractId(nextCid()),
      payload = template,
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )
  }

  private def appPaymentRequest(
      sender: PartyId,
      provider: PartyId,
      amount: Double,
      currency: paymentCodegen.Currency,
      expiresAt: CantonTimestamp,
      deliveryOfferCid: testWalletCodegen.TestDeliveryOffer.ContractId,
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
      deliveryOfferCid.toInterface(paymentCodegen.DeliveryOffer.INTERFACE),
    )
    Contract(
      identifier = templateId,
      contractId = new paymentCodegen.AppPaymentRequest.ContractId(nextCid()),
      payload = template,
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )
  }

  private def appPaymentRequestWithOffer(
      sender: PartyId,
      provider: PartyId,
      amount: Double,
      currency: paymentCodegen.Currency,
      expiresAt: CantonTimestamp,
  ) = {
    val offer = deliveryOffer(sender)
    val request = appPaymentRequest(sender, provider, amount, currency, expiresAt, offer.contractId)
    (offer, request)
  }

  private def subscriptionContext(
      user: PartyId,
      service: PartyId,
  ) = {
    val templateId = testSubscCodegen.TestSubscriptionContext.TEMPLATE_ID
    val template = new testSubscCodegen.TestSubscriptionContext(
      svcParty.toProtoPrimitive,
      user.toProtoPrimitive,
      service.toProtoPrimitive,
      s"Party $user subscribes to a service provided by party $service",
    )
    Contract(
      identifier = templateId,
      contractId = new testSubscCodegen.TestSubscriptionContext.ContractId(nextCid()),
      payload = template,
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )
  }

  private def subscription(
      contextContract: Contract[
        testSubscCodegen.TestSubscriptionContext.ContractId,
        testSubscCodegen.TestSubscriptionContext,
      ]
  ) = {
    val templateId = subsCodegen.Subscription.TEMPLATE_ID
    val template = new subsCodegen.Subscription(
      contextContract.payload.user,
      contextContract.payload.service,
      contextContract.payload.service,
      svcParty.toProtoPrimitive,
      contextContract.contractId.toInterface(subsCodegen.SubscriptionContext.INTERFACE),
    )
    Contract(
      identifier = templateId,
      contractId = new subsCodegen.Subscription.ContractId(nextCid()),
      payload = template,
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
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
      subscriptionContract.payload,
      payData,
      nextPaymentDueAt.toInstant,
    )
    Contract(
      identifier = templateId,
      contractId = new subsCodegen.SubscriptionIdleState.ContractId(nextCid()),
      payload = template,
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )
  }

  private def subscriptionPaymentState(
      subscription: Contract[subsCodegen.Subscription.ContractId, subsCodegen.Subscription],
      payData: subsCodegen.SubscriptionPayData,
      thisPaymentDueAt: CantonTimestamp,
      lockedCoinCid: ccApiCodegen.coin.LockedCoin.ContractId,
      round: Long,
  ) = {
    val templateId = subsCodegen.SubscriptionPayment.TEMPLATE_ID
    val template = new subsCodegen.SubscriptionPayment(
      subscription.contractId,
      subscription.payload,
      payData,
      thisPaymentDueAt.toInstant,
      // Note: this targetAmount is only correct for CC payments.
      // USD payments would need to apply coin price, but we don't care about the exact value.
      payData.paymentAmount.amount,
      lockedCoinCid,
      new ccApiCodegen.round.Round(round),
    )
    Contract(
      identifier = templateId,
      contractId = new subsCodegen.SubscriptionPayment.ContractId(nextCid()),
      payload = template,
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )
  }

  private def subscriptionInIdleState(
      sender: PartyId,
      receiver: PartyId,
      payData: subsCodegen.SubscriptionPayData,
      nextPaymentDueAt: CantonTimestamp,
  ) = {
    val contextContract = subscriptionContext(user = sender, service = receiver)
    val subscriptionContract = subscription(contextContract)
    val idleStateContract = subscriptionIdleState(
      subscriptionContract,
      payData,
      nextPaymentDueAt,
    )
    (contextContract, subscriptionContract, idleStateContract)
  }

  private def subscriptionInPaymentState(
      sender: PartyId,
      receiver: PartyId,
      payData: subsCodegen.SubscriptionPayData,
      thisPaymentDueAt: CantonTimestamp,
      lockedCoinCid: ccApiCodegen.coin.LockedCoin.ContractId,
      round: Long,
  ) = {
    val contextContract = subscriptionContext(user = sender, service = receiver)
    val subscriptionContract = subscription(contextContract)
    val paymentStateContract = subscriptionPaymentState(
      subscriptionContract,
      payData,
      thisPaymentDueAt,
      lockedCoinCid,
      round,
    )
    (contextContract, subscriptionContract, paymentStateContract)
  }

  private def subscriptionPayData(
      amount: Double = 10.0,
      currency: paymentCodegen.Currency = paymentCodegen.Currency.CC,
      paymentIntervalSeconds: Long = 60L,
      paymentDurationSeconds: Long = 1L,
  ): subsCodegen.SubscriptionPayData = {
    new subsCodegen.SubscriptionPayData(
      new paymentCodegen.PaymentAmount(new java.math.BigDecimal(amount), currency),
      new RelTime(paymentIntervalSeconds * 1000L * 1000L),
      new RelTime(paymentDurationSeconds * 1000L * 1000L),
    )
  }

  private def subscriptionRequest(
      subscriptionData: subsCodegen.Subscription,
      payData: subsCodegen.SubscriptionPayData,
  ) = {
    val templateId = subsCodegen.SubscriptionRequest.TEMPLATE_ID
    val template = new subsCodegen.SubscriptionRequest(
      subscriptionData,
      payData,
    )
    Contract(
      identifier = templateId,
      contractId = new subsCodegen.SubscriptionRequest.ContractId(nextCid()),
      payload = template,
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )
  }

  private def coin(owner: PartyId, amount: Double, createdAt: Long, ratePerRound: Double) = {
    val templateId = coinCodegen.Coin.TEMPLATE_ID
    val template = new coinCodegen.Coin(
      svcParty.toProtoPrimitive,
      owner.toProtoPrimitive,
      new feesCodegen.ExpiringAmount(
        new java.math.BigDecimal(amount),
        new ccApiCodegen.round.Round(createdAt),
        new feesCodegen.RatePerRound(new java.math.BigDecimal(ratePerRound)),
      ),
    )
    Contract(
      identifier = templateId,
      contractId = new coinCodegen.Coin.ContractId(nextCid()),
      payload = template,
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
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
        new ccApiCodegen.coin.CoinCreateSummary[coinCodegen.Coin.ContractId](
          coinContract.contractId,
          new java.math.BigDecimal(coinPrice),
        )
          .toValue(_.toValue),
      ),
      Seq(toCreatedEvent(coinContract)),
    )
  }

  // Note: The TransactionTreeSource is only used to read TxLog entries. For most tests it is never used.
  protected def mkStore(
      endUserParty: PartyId,
      transactionTreeSource: TransactionTreeSource = TransactionTreeSource.Unused,
  ): Future[UserWalletStore]

  lazy val offset = Offset.fromByteArray(Array(1, 2, 3).map(_.toByte))
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
      defaultAcsDomain = domainAlias,
      loggerFactory = loggerFactory,
      transactionTreeSource = transactionTreeSource,
      retryProvider = RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop),
    )
    for {
      _ <- store.multiDomainAcsStore.ingestionSink.initialize()
      _ <- store.multiDomainAcsStore.ingestionSink
        .ingestAcs(offset.toHexString, Seq.empty, Seq.empty)
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
        Seq(
          "dar/canton-coin-0.1.0.dar",
          "dar/wallet-0.1.0.dar",
        )
      )
    implicit val templateJsonDecoder: TemplateJsonDecoder =
      new ResourceTemplateDecoder(packageSignatures, loggerFactory)

    val store = new DbUserWalletStore(
      key = storeKey(endUserParty),
      defaultAcsDomain = domainAlias,
      storage = storage,
      loggerFactory = loggerFactory,
      transactionTreeSource = transactionTreeSource,
      retryProvider = RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop),
    )
    for {
      _ <- store.multiDomainAcsStore.ingestionSink.initialize()
      _ <- store.multiDomainAcsStore.ingestionSink
        .ingestAcs(offset.toHexString, Seq.empty, Seq.empty)
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
