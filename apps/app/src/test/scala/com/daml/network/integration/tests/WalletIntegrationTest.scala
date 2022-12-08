package com.daml.network.integration.tests

import com.daml.ledger.client.binding.Primitive
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cn.scripts.wallet.testsubscriptions as testSubsCodegen
import com.daml.network.codegen.java.cn.wallet.{
  payment as walletCodegen,
  subscriptions as subsCodegen,
}
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
}
import com.daml.network.util.WalletTestUtil
import com.daml.network.wallet.admin.api.client.commands.GrpcWalletAppClient
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.participant.ledger.api.client.JavaDecodeUtil as DecodeUtil
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.{DiscardOps, HasExecutionContext}
import org.slf4j.event.Level

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*
import scala.util.Try

class WalletIntegrationTest
    extends CoinIntegrationTest
    with HasExecutionContext
    with WalletTestUtil {

  "A wallet" should {

    "restart cleanly" in { implicit env =>
      aliceWalletBackend.stop()
      aliceWalletBackend.startSync()
    }

    "shutdown cleanly with lots of coin operations in flight" in { implicit env =>
      onboardWalletUser(aliceWallet, aliceValidator)

      loggerFactory.assertLoggedWarningsAndErrorsSeq(
        {
          for (_ <- Range(1, 500)) {
            val _ = Future {
              try {
                aliceWallet.tap(13)
              } catch {
                case scala.util.control.NonFatal(ex) =>
                  logger.info("Ignoring exception when executing tap", ex)
              }
            }
          }

          clue(
            "Waiting for two taps to succeed, so that the other requests have time to reach the server"
          ) {
            // wrapped in eventually, as it can fail due to the queue for coin operations being full
            eventually() {
              Try(aliceWallet.tap(20)).isSuccess shouldBe true
            }
            eventually() {
              Try(aliceWallet.tap(30)).isSuccess shouldBe true
            }
          }
          clue("Stopping alice's wallet") {
            // In manual runs, we've observed that sometimes the gRPC server reports a slow shutdown.
            // We accept that for now.
            aliceWalletBackend.stop()
          }
        },
        lines =>
          forAll(lines) { line =>
            val errorRegex = Seq(
              "Request failed for aliceWallet",
              "(ABORTED|UNAVAILABLE|CANCELLED|UNIMPLEMENTED)",
            ).mkString("(.|\\n|\\r)*")
            if (line.level == Level.ERROR) {
              line.message should include regex errorRegex
            } else if (line.level == Level.WARN) {
              // If the coin operation buffer is large or batch execution is slow, then shutdown is not quick enough.
              // We accept that for now, as it is non-trivial to propagate the shutdown signal from the server to the
              // coin operation batch executor.
              line.message should include regex ("NettyServer.*shutdown did not complete gracefully")
            } else {
              fail("unexpected warning or error")
            }
          },
      )
    }

    "allow a user to list, and reject app payment requests" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)

      clue("Check that no payment requests exist") {
        aliceWallet.listAppPaymentRequests() shouldBe empty
      }

      val (_, _, reqC) =
        createSelfPaymentRequest(aliceWalletBackend.remoteParticipant, aliceUserParty)

      val reqFound = clue("Check that we can see the created payment request") {
        val reqFound = eventually() {
          aliceWallet.listAppPaymentRequests().headOption.value
        }
        reqFound.payload shouldBe reqC
        reqFound
      }

      clue("Reject the payment request") {
        aliceWallet.rejectAppPaymentRequest(reqFound.contractId)
      }

      clue("Check that there are no more payment requests") {
        val requests2 = aliceWallet.listAppPaymentRequests()
        requests2 shouldBe empty
      }
    }

    "allow a user to list and accept app payment requests" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)

      val (referenceId, _, reqC) =
        createSelfPaymentRequest(aliceWalletBackend.remoteParticipant, aliceUserParty)

      val cid = eventually() {
        inside(aliceWallet.listAppPaymentRequests()) { case Seq(r) =>
          r.payload shouldBe reqC
          r.contractId
        }
      }

      clue("Tap 50 coins") {
        aliceWallet.tap(50)
        eventually() {
          aliceWallet.list().coins should not be empty
        }
      }

      clue("Accept payment request") {
        val acceptedPaymentId = aliceWallet.acceptAppPaymentRequest(cid)
        aliceWallet.listAppPaymentRequests() shouldBe empty
        inside(aliceWallet.listAcceptedAppPayments()) { case Seq(r) =>
          r.contractId shouldBe acceptedPaymentId
          r.payload shouldBe new walletCodegen.AcceptedAppPayment(
            aliceUserParty.toProtoPrimitive,
            Seq(
              new walletCodegen.ReceiverCCQuantity(
                aliceUserParty.toProtoPrimitive,
                BigDecimal(10).bigDecimal.setScale(10),
              )
            ).asJava,
            aliceUserParty.toProtoPrimitive,
            svcParty.toProtoPrimitive,
            r.payload.lockedCoin,
            referenceId.toInterface(walletCodegen.DeliveryOffer.INTERFACE),
          )
        }
      }
    }

    "correctly select coins for payments" in { implicit env =>
      val (alice, bob) = onboardAliceAndBob()

      clue("Alice gets some coins") {
        // Note: it would be great if we could add coins with different holding fees,
        // to test whether the wallet selects the most expensive ones for the transfer.
        aliceWallet.tap(10)
        aliceWallet.tap(40)
        aliceWallet.tap(20)
        checkWallet(alice, aliceWallet, Seq((10, 10), (20, 20), (40, 40)))
      }

      clue("Alice transfers 39") {
        p2pTransfer(aliceWallet, bobWallet, bob, 39)
        checkWallet(alice, aliceWallet, Seq((30, 31)))
      }
      clue("Alice transfers 19") {
        p2pTransfer(aliceWallet, bobWallet, bob, 19)
        checkWallet(alice, aliceWallet, Seq((11, 12)))
      }
    }

    "allow a user to list and reject subscription requests" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)

      aliceWallet.listSubscriptionRequests() shouldBe empty

      val request = createSelfSubscriptionRequest(aliceUserParty);

      val requestId = clue("List subscription requests to find out request ID") {
        eventually() {
          inside(aliceWallet.listSubscriptionRequests()) { case Seq(r) =>
            r.payload shouldBe request
            r.contractId
          }
        }
      }
      clue("Reject the subscription request") {
        aliceWallet.rejectSubscriptionRequest(requestId)
        aliceWallet.listSubscriptionRequests() shouldBe empty
      }
    }

    // We put all of this in one test because assembling valid subscription instances
    // is cumbersome and it's easier to just reuse the results of the "accept" flow.
    "allow a user to list and accept subscription requests, " +
      "to list idle subscriptions, to initiate subscription payments, " +
      "and to cancel a subscription" in { implicit env =>
        val transferContext = scan.getAppTransferContext()
        val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)
        val aliceValidatorParty = aliceValidator.getValidatorPartyId()

        aliceWallet.listSubscriptionRequests() shouldBe empty
        aliceWallet.listSubscriptions() shouldBe empty

        val (request, requestId) = actAndCheck(
          "Create self-subscription request",
          createSelfSubscriptionRequest(aliceUserParty),
        )(
          "the created subscription request is listed correctly",
          request =>
            inside(aliceWallet.listSubscriptionRequests()) { case Seq(r) =>
              r.payload shouldBe request
              r.contractId
            },
        )
        clue("Alice gets some coins") {
          aliceWallet.tap(50)
        }

        val (initialPaymentId, _) = actAndCheck(
          "Accept the subscription request, which initiates the first subscription payment",
          aliceWallet.acceptSubscriptionRequest(requestId),
        )(
          "initial subscription payment is listed correctly",
          initialPaymentId => {
            aliceWallet.listSubscriptionRequests() shouldBe empty
            inside(aliceWallet.listSubscriptionInitialPayments()) { case Seq(r) =>
              r.contractId shouldBe initialPaymentId
              r.payload.subscriptionData should equal(request.subscriptionData)
              r.payload.payData should equal(request.payData)
            }
          },
        )

        val (_, paymentId) = actAndCheck(
          "Collect the initial payment (as the receiver), which creates the subscription", {
            val collectCommand = initialPaymentId
              .exerciseSubscriptionInitialPayment_Collect(transferContext)
              .commands
              .asScala
              .toSeq
            aliceWalletBackend.remoteParticipant.ledger_api.commands.submitJava(
              actAs = Seq(aliceUserParty),
              readAs = Seq(aliceValidatorParty),
              optTimeout = None,
              commands = collectCommand,
            )
          },
        )(
          // note that because this test sets paymentDuration = paymentInterval,
          // the wallet backend can make the second payment immediately
          "an automated subscription payment is eventually initiated by the wallet",
          _ =>
            inside(aliceWallet.listSubscriptions()) { case Seq(sub) =>
              sub.main.payload should equal(request.subscriptionData)
              inside(sub.state) { case GrpcWalletAppClient.SubscriptionPayment(state) =>
                state.payload.subscription shouldBe sub.main.contractId
                state.payload.payData should equal(request.payData)
                state.contractId
              }
            },
        )

        val (_, subscriptionStateId2) = actAndCheck(
          "Collect the second payment (as the receiver), which sets the subscription back to idle", {
            val collectCommand2 = paymentId
              .exerciseSubscriptionPayment_Collect(transferContext)
              .commands
              .asScala
              .toSeq
            aliceWalletBackend.remoteParticipant.ledger_api.commands.submitJava(
              actAs = Seq(aliceUserParty),
              readAs = Seq(aliceValidatorParty),
              optTimeout = None,
              commands = collectCommand2,
            )
          },
        )(
          "the subscription is back in idle state",
          _ =>
            inside(aliceWallet.listSubscriptions()) { case Seq(sub) =>
              sub.main.payload should equal(request.subscriptionData)
              inside(sub.state) { case GrpcWalletAppClient.SubscriptionIdleState(state) =>
                state.payload.subscription should equal(sub.main.contractId)
                state.payload.payData should equal(request.payData)
                state.contractId
              }
            },
        )

        actAndCheck(
          "Cancel the subscription",
          aliceWallet.cancelSubscription(subscriptionStateId2),
        )("no more subscriptions exist", _ => aliceWallet.listSubscriptions() shouldBe empty)
      }

    "allow two users to make direct transfers between them" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)
      val bobUserParty = onboardWalletUser(bobWallet, bobValidator)
      aliceWallet.tap(100.0)

      val expiration = Primitive.Timestamp
        .discardNanos(Instant.now().plus(1, ChronoUnit.MINUTES))
        .getOrElse(fail("Failed to convert timestamp"))

      val offer =
        aliceWallet.createTransferOffer(bobUserParty, 1.0, "direct transfer test", expiration)
      val offer2 =
        aliceWallet.createTransferOffer(bobUserParty, 2.0, "to be rejected", expiration)
      val offer3 =
        aliceWallet.createTransferOffer(bobUserParty, 3.0, "to be withdrawn", expiration)

      eventually() {
        aliceWallet.listTransferOffers() should have length 3
        bobWallet.listTransferOffers() should have length 3
      }

      actAndCheck("Bob accepts one offer", bobWallet.acceptTransferOffer(offer))(
        "Accepted offer gets paid",
        _ => {
          aliceWallet.listTransferOffers() should have length 2
          aliceWallet.listAcceptedTransferOffers() should have length 0
          bobWallet.listTransferOffers() should have length 2
          bobWallet.listAcceptedTransferOffers() should have length 0
          checkWallet(aliceUserParty, aliceWallet, Seq((98.8, 99.0)))
          checkWallet(bobUserParty, bobWallet, Seq((1.0, 1.0)))
        },
      )

      actAndCheck(
        "Bob rejects one offer, alice withdraws the other", {
          bobWallet.rejectTransferOffer(offer2)
          aliceWallet.withdrawTransferOffer(offer3)
        },
      )(
        "No more offers listed",
        _ => {
          aliceWallet.listTransferOffers() should have length 0
          aliceWallet.listAcceptedTransferOffers() should have length 0
          bobWallet.listTransferOffers() should have length 0
          bobWallet.listAcceptedTransferOffers() should have length 0
        },
      )

      // TODO(#1870): consider making this a time-based test instead
      val shortExpiration = Primitive.Timestamp
        .discardNanos(Instant.now().plus(2, ChronoUnit.SECONDS))
        .getOrElse(fail("Failed to convert timestamp"))

      val (offer4, _) = actAndCheck(
        "Alice creates two short-lived transfer offer", {
          aliceWallet.createTransferOffer(
            bobUserParty,
            1.0,
            "should expire before accepted",
            shortExpiration,
          )
          aliceWallet
            .createTransferOffer(
              bobUserParty,
              150.0,
              "should expire after accepted",
              shortExpiration,
            )
        },
      )(
        "Wait for new offer to be ingested",
        _ => {
          aliceWallet.listTransferOffers() should have length 2
          bobWallet.listTransferOffers() should have length 2
        },
      )
      clue("Bob accepts an offer that alice will not afford")(
        bobWallet.acceptTransferOffer(offer4)
      )

      clue("Wait for both offers to expire")(eventually() {
        aliceWallet.listTransferOffers() should have length 0
      })

      val (offer5, _) = actAndCheck(
        "Create a transfer offer that alice cannot yet afford",
        aliceWallet.createTransferOffer(
          bobUserParty,
          quantity = 150.0,
          description = "not rich enough yet",
          expiration,
        ),
      )("offer is ingested", _ => aliceWallet.listTransferOffers() should have length 1)
      actAndCheck("Bob accepts the offer", bobWallet.acceptTransferOffer(offer5))(
        "Accepted offer is ingested",
        _ => aliceWallet.listAcceptedTransferOffers() should have length 1,
      )
      clue("Sleeping for a while, to make sure a few retries fail first")(
        // TODO(#1870): consider making this a time-based test, and advancing time gradually to trigger the failing automation instead
        Threading.sleep(4000)
      )
      clue("Tapping more coin, to afford the accepted transfer offer")(
        aliceWallet.tap(200.0)
      )
      clue("Checking final balances")(
        eventually() {
          checkWallet(aliceUserParty, aliceWallet, Seq((147.5, 148.0)))
          checkWallet(bobUserParty, bobWallet, Seq((1.0, 1.0), (150.0, 150.0)))
        }
      )
    }

    "allow two wallet app users to connect to one wallet backend and tap" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)

      aliceWallet.tap(50.0)
      checkWallet(aliceUserParty, aliceWallet, Seq((50, 50)))

      val charlieDamlUser = charlieWallet.config.damlUser
      val charlieUserParty = onboardWalletUser(charlieWallet, aliceValidator)

      charlieWallet.tap(50.0)
      checkWallet(charlieUserParty, charlieWallet, Seq((50, 50)))

      aliceWalletBackend.setWalletContext(charlieDamlUser)
      checkWallet(charlieUserParty, aliceWalletBackend, Seq((50, 50)))
    }

    "skip empty batches in the treasury service" in { implicit env =>
      val alice = onboardWalletUser(aliceWallet, aliceValidator)
      aliceWallet.tap(49)
      // create and reject request such that...
      val request = createSelfPaymentRequest(aliceValidator.remoteParticipant, alice)._2
      aliceWallet.rejectAppPaymentRequest(request)

      loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
        {
          try {
            // ... lookup on the payment request fails
            aliceWallet.acceptAppPaymentRequest(request)
          } catch {
            case _: CommandFailure =>
          }
        },
        entries => {
          forAtLeast(1, entries)(
            // .. and we see that the empty batch is skipped.
            _.message should include(
              "Found no valid coin operations after running lookups"
            )
          )
        },
      )
    }

    "concurrent coin-operations" should {
      "be batched" in { implicit env =>
        val alice = onboardWalletUser(aliceWallet, aliceValidator)
        aliceWallet.tap(50)
        val requestIds =
          (1 to 3).map(i => createSelfPaymentRequest(aliceValidator.remoteParticipant, alice)._2)
        val offsetBefore = aliceValidator.remoteParticipant.ledger_api.transactions.end()
        // sending three commands in short succession to the idle wallet should lead to two transactions being executed
        // tx 1: first command that arrived is immediately executed
        // tx 2: other commands that arrived after the first command was started are executed in one batch
        requestIds.foreach(requestId =>
          Future(aliceWallet.acceptAppPaymentRequest(requestId)).discard
        )

        // Wait until 2 transactions have been received
        val txs = aliceValidator.remoteParticipant.ledger_api.transactions
          .treesJava(Set(alice), completeAfter = 2, beginOffset = offsetBefore)
        val createdCoinsInTx =
          txs.map(DecodeUtil.decodeAllCreatedTree(coinCodegen.Coin.COMPANION)(_))
        val createdLockedCoinsInTx =
          txs.map(DecodeUtil.decodeAllCreatedTree(coinCodegen.LockedCoin.COMPANION)(_))

        // create change
        createdCoinsInTx(0) should have size 1
        // lock coin
        createdLockedCoinsInTx(0) should have size 1
        // create change x2
        createdCoinsInTx(1) should have size 2
        // lock coin x2
        createdLockedCoinsInTx(1) should have size 2
      }

      "be batched up to `batchSize` concurrent coin-operations" in { implicit env =>
        val batchSize = aliceWalletBackend.config.treasury.batchSize
        val alice = onboardWalletUser(aliceWallet, aliceValidator)
        aliceWallet.tap(1000)

        val requests =
          (0 to batchSize + 1).map(_ =>
            createSelfPaymentRequest(aliceValidator.remoteParticipant, alice)._2
          )

        eventually() {
          aliceValidator.remoteParticipant.ledger_api.acs.filterJava(
            walletCodegen.AppPaymentRequest.COMPANION
          )(alice) should have size (batchSize.toLong + 2)
        }

        val offsetBefore = aliceValidator.remoteParticipant.ledger_api.transactions.end()

        requests.foreach(request => Future(aliceWallet.acceptAppPaymentRequest(request)).discard)

        // 3 txs;
        // tx 1: initial transfer
        // tx 2: batchSize subsequent batched transfers
        // tx 3: single transfer that was not picked due to the batch size limit
        val txs = aliceValidator.remoteParticipant.ledger_api.transactions
          .treesJava(Set(alice), completeAfter = 3, beginOffset = offsetBefore)
        val createdCoinsInTx =
          txs.map(DecodeUtil.decodeAllCreatedTree(coinCodegen.Coin.COMPANION)(_))
        val createdLockedCoinsInTx =
          txs.map(DecodeUtil.decodeAllCreatedTree(coinCodegen.LockedCoin.COMPANION)(_))

        // create change
        createdCoinsInTx(0) should have size 1
        // lock coin
        createdLockedCoinsInTx(0) should have size 1
        // create change x batchSize
        createdCoinsInTx(1) should have size batchSize.toLong
        // lock coin x batchSize
        createdLockedCoinsInTx(1) should have size batchSize.toLong
        // create change
        createdCoinsInTx(2) should have size 1
        // lock coin
        createdLockedCoinsInTx(2) should have size 1
      }

      "fail operations early and independently that don't pass the activeness lookup checks" in {
        implicit env =>
          val alice = onboardWalletUser(aliceWallet, aliceValidator)

          // tapping some coin & waiting for it to appear as a way to synchronize on the initialization of the apps.
          aliceWallet.tap(10)
          aliceValidator.remoteParticipant.ledger_api.acs
            .awaitJava(coinCodegen.Coin.COMPANION)(alice)
          // creating payment request
          val request = createSelfPaymentRequest(aliceValidator.remoteParticipant, alice)._2
          // Reject it again
          aliceWallet.rejectAppPaymentRequest(request)
          // ... such that we don't grab the ledger offset when some init txs are still occurring
          val offsetBefore = aliceValidator.remoteParticipant.ledger_api.transactions.end()

          // solo tap will kick off batch with only one coin operation
          val transfer1F = Future(aliceWallet.tap(10))

          // following three commands will be in one batch
          val transfer2F = Future(aliceWallet.tap(10))
          // fails because we don't have a payment request - so removed from batch & error is reported back
          val failedAcceptF = Future(
            loggerFactory.assertThrowsAndLogs[CommandFailure](
              aliceWallet.acceptAppPaymentRequest(request),
              _.errorMessage should include regex ("NOT_FOUND/.*AppPaymentRequest"),
            )
          )
          val transfer3F = Future(aliceWallet.tap(10))
          // Wait for all futures to complete
          transfer1F.futureValue
          transfer2F.futureValue
          transfer3F.futureValue
          failedAcceptF.futureValue

          eventually() {
            val coins = aliceWallet.list().coins
            // all four taps went through
            coins should have size 4
            // but no money was deducted due to the transfer
            checkWallet(alice, aliceWallet, Seq((9, 10), (9, 10), (9, 10), (9, 10)))
          }
          eventually() {
            val txs = aliceValidator.remoteParticipant.ledger_api.transactions
              .treesJava(Set(alice), completeAfter = 2, beginOffset = offsetBefore)
            val createdCoinsInTx =
              txs.map(DecodeUtil.decodeAllCreatedTree(coinCodegen.Coin.COMPANION)(_))
            createdCoinsInTx(0) should have size 1
            // two taps went through, even though transfer in same batch failed.
            createdCoinsInTx(1) should have size 2
          }
      }

      "retry a batch if it fails due to contention" in { implicit env =>
        val alice = onboardWalletUser(aliceWallet, aliceValidator)
        aliceWallet.tap(10)

        val request = createSelfPaymentRequest(aliceValidator.remoteParticipant, alice)._2
        eventually()(aliceWallet.listAppPaymentRequests() should have size 1)

        val cancelF = Future(aliceWallet.rejectAppPaymentRequest(request))

        val acceptedF = loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
          // to simulate contention, accept the payment request immediately after rejecting it
          // that the activeness lookup on the payment request still goes through but the lookup inside the ledger fails.
          // this leads to a retry..
          Future(aliceWallet.acceptAppPaymentRequest(request).discard)
            // that fails on the second execution
            .recover { case _: CommandFailure =>
              // need to recover as logs are only checked for successful futures
              ()
            },
          entries => {
            forAtLeast(1, entries)( // however, before failing, we see one retry in the logs
              _.message should include(
                "The operation 'execute coin operation batch' has failed with an exception. Retrying after 200 milliseconds."
              )
            )
            forAtLeast(1, entries)( // fails
              _.message should include regex (
                "GrpcRequestRefusedByServer: NOT_FOUND/.*AppPaymentRequest"
              )
            )
          },
        )

        // eventually, the cancel goes through
        cancelF.futureValue
        eventually()(aliceWallet.listAppPaymentRequests() shouldBe empty)

        // such that on the retry, the acceptance fails on the activeness check.
        acceptedF.futureValue
        checkWallet(alice, aliceWallet, Seq((10, 10)))

      }
    }

    "rejects HS256 JWTs with invalid signatures" in { implicit env =>
      import com.auth0.jwt.JWT
      import com.auth0.jwt.algorithms.Algorithm
      import com.daml.network.auth.JwtCallCredential
      import com.daml.network.wallet.v0
      import io.grpc.ManagedChannelBuilder

      val aliceDamlUser = aliceWallet.config.damlUser
      val token = JWT.create().withSubject(aliceDamlUser).sign(Algorithm.HMAC256("wrong-secret"))

      // using grpc client directly rather than console refs, as token handling isn't threaded through to console command layer (yet)
      val channel =
        ManagedChannelBuilder
          .forAddress("localhost", aliceWalletBackend.config.adminApi.port.unwrap)
          .usePlaintext()
          .build()

      val client =
        v0.WalletServiceGrpc
          .blockingStub(channel)
          .withCallCredentials(new JwtCallCredential(token))

      val error = intercept[io.grpc.StatusRuntimeException] {
        client.tap(new v0.TapRequest("10.0"))
      }

      assert(
        error.getStatus.getCode.value == com.google.rpc.Code.UNAUTHENTICATED.getNumber
      )

      channel.shutdown() // to avoid error about improperly shut down channel
    }

    def createSelfSubscriptionRequest(aliceUserParty: PartyId)(implicit
        env: CoinTestConsoleEnvironment
    ): subsCodegen.SubscriptionRequest = {
      val contextId = clue("Create a subscription context") {
        aliceWalletBackend.remoteParticipant.ledger_api.commands.submitJava(
          Seq(aliceUserParty),
          optTimeout = None,
          commands = new testSubsCodegen.TestSubscriptionContext(
            scan.getSvcPartyId().toProtoPrimitive,
            aliceUserParty.toProtoPrimitive,
            aliceUserParty.toProtoPrimitive,
            "description",
          ).create.commands.asScala.toSeq,
        )
        aliceWalletBackend.remoteParticipant.ledger_api.acs
          .awaitJava(testSubsCodegen.TestSubscriptionContext.COMPANION)(aliceUserParty)
          .id
      }
      clue("Create a subscription request to self") {
        val subscriptionData = new subsCodegen.Subscription(
          aliceUserParty.toProtoPrimitive,
          aliceUserParty.toProtoPrimitive,
          aliceUserParty.toProtoPrimitive,
          svcParty.toProtoPrimitive,
          contextId.toInterface(subsCodegen.SubscriptionContext.INTERFACE),
        )
        val payData = new subsCodegen.SubscriptionPayData(
          new walletCodegen.PaymentQuantity(
            BigDecimal(10).bigDecimal.setScale(10),
            walletCodegen.Currency.CC,
          ),
          new RelTime(60 * 60 * 1000000L),
          new RelTime(60 * 60 * 1000000L),
          new RelTime(60 * 1000000L),
        ) // paymentDuration == paymenInterval, so we can make a second payment immediately,
        // without having to mess with time
        val request = new subsCodegen.SubscriptionRequest(
          subscriptionData,
          payData,
        )
        aliceWalletBackend.remoteParticipant.ledger_api.commands.submitJava(
          actAs = Seq(aliceUserParty),
          optTimeout = None,
          commands = request.create.commands.asScala.toSeq,
        )
        request
      }
    }
  }
}
