package com.daml.network.integration.tests

import com.daml.network.auth.AuthUtil
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cn.wallet.{payment as walletCodegen}
import com.daml.network.integration.tests.CoinTests.CoinIntegrationTest
import com.daml.network.util.WalletTestUtil
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.participant.ledger.api.client.JavaDecodeUtil as DecodeUtil
import com.digitalasset.canton.{DiscardOps, HasExecutionContext}
import org.slf4j.event.Level

import scala.concurrent.Future

class WalletIntegrationTest
    extends CoinIntegrationTest
    with HasExecutionContext
    with WalletTestUtil {

  "A wallet" should {

    "restart cleanly" in { implicit env =>
      aliceWalletBackend.stop()
      aliceWalletBackend.startSync()
    }

    "allow two wallet app users to connect to one wallet backend and tap" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)

      aliceWallet.tap(50.0)
      checkWallet(aliceUserParty, aliceWallet, Seq((50, 50)))

      val charlieUserParty = onboardWalletUser(charlieWallet, aliceValidator)

      charlieWallet.tap(50.0)
      checkWallet(charlieUserParty, charlieWallet, Seq((50, 50)))
    }

    "skip empty batches in the treasury service" in { implicit env =>
      val alice = onboardWalletUser(aliceWallet, aliceValidator)
      aliceWallet.tap(49)
      // create and reject request such that...
      val request =
        createSelfPaymentRequest(
          aliceValidator.remoteParticipantWithAdminToken,
          aliceWallet.config.damlUser,
          alice,
        )._2
      aliceWallet.rejectAppPaymentRequest(request)

      loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
        {
          def submitRequest() =
            try {
              // ... lookup on the payment request fails
              aliceWallet.acceptAppPaymentRequest(request)
            } catch {
              case _: CommandFailure =>
            }
          submitRequest()
          // Without this second request, the walletBackend would shut down too quickly, and we would not see
          // the message about an empty batch in the logs.
          submitRequest()
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
          (1 to 3).map(i =>
            createSelfPaymentRequest(
              aliceValidator.remoteParticipantWithAdminToken,
              aliceWallet.config.damlUser,
              alice,
            )._2
          )
        val offsetBefore =
          aliceValidator.remoteParticipantWithAdminToken.ledger_api.transactions.end()
        // sending three commands in short succession to the idle wallet should lead to two transactions being executed
        // tx 1: first command that arrived is immediately executed
        // tx 2: other commands that arrived after the first command was started are executed in one batch
        requestIds.foreach(requestId =>
          Future(aliceWallet.acceptAppPaymentRequest(requestId)).discard
        )

        // Wait until 2 transactions have been received
        val txs = aliceValidator.remoteParticipantWithAdminToken.ledger_api.transactions
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
            createSelfPaymentRequest(
              aliceValidator.remoteParticipantWithAdminToken,
              aliceWallet.config.damlUser,
              alice,
            )._2
          )

        eventually() {
          aliceValidator.remoteParticipantWithAdminToken.ledger_api.acs.filterJava(
            walletCodegen.AppPaymentRequest.COMPANION
          )(alice) should have size (batchSize.toLong + 2)
        }

        val offsetBefore =
          aliceValidator.remoteParticipantWithAdminToken.ledger_api.transactions.end()

        requests.foreach(request => Future(aliceWallet.acceptAppPaymentRequest(request)).discard)

        // 3 txs;
        // tx 1: initial transfer
        // tx 2: batchSize subsequent batched transfers
        // tx 3: single transfer that was not picked due to the batch size limit
        val txs = aliceValidator.remoteParticipantWithAdminToken.ledger_api.transactions
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
          aliceValidator.remoteParticipantWithAdminToken.ledger_api.acs
            .awaitJava(coinCodegen.Coin.COMPANION)(alice)
          // creating payment request
          val request =
            createSelfPaymentRequest(
              aliceValidator.remoteParticipantWithAdminToken,
              aliceWallet.config.damlUser,
              alice,
            )._2
          // Reject it again
          aliceWallet.rejectAppPaymentRequest(request)
          // ... such that we don't grab the ledger offset when some init txs are still occurring
          val offsetBefore =
            aliceValidator.remoteParticipantWithAdminToken.ledger_api.transactions.end()

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
            val txs = aliceValidator.remoteParticipantWithAdminToken.ledger_api.transactions
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

        val request =
          createSelfPaymentRequest(
            aliceValidator.remoteParticipantWithAdminToken,
            aliceWallet.config.damlUser,
            alice,
          )._2
        eventually()(aliceWallet.listAppPaymentRequests() should have size 1)

        val rejectF = Future(aliceWallet.rejectAppPaymentRequest(request))

        loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
          {
            // to simulate contention, accept the payment request immediately after rejecting it
            // that the activeness lookup on the payment request still goes through but the lookup inside the ledger fails.
            // this leads to a retry..
            val _ =
              try aliceWallet.acceptAppPaymentRequest(request)
              catch { case _: CommandFailure => () }
            // Execute a successful tap here so that we see the recovery of the coin-operation batch executor.
            // Without that one, the shutdown would initiate before recovery starts.
            aliceWallet.tap(666)
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

        // eventually, the rejection goes through
        rejectF.futureValue
        eventually()(aliceWallet.listAppPaymentRequests() shouldBe empty)
        checkWallet(alice, aliceWallet, Seq((10, 10), (666, 666)))
      }
    }

    "rejects HS256 JWTs with invalid signatures" in { implicit env =>
      import com.auth0.jwt.JWT
      import com.auth0.jwt.algorithms.Algorithm
      import com.daml.network.auth.JwtCallCredential
      import com.daml.network.wallet.v0
      import io.grpc.ManagedChannelBuilder

      val token = JWT
        .create()
        .withAudience(aliceWalletBackend.config.auth.audience)
        .withSubject(aliceWallet.config.damlUser)
        .sign(Algorithm.HMAC256("wrong-secret"))

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

      error.getStatus.getCode.value shouldBe com.google.rpc.Code.UNAUTHENTICATED.getNumber

      channel.shutdown() // to avoid error about improperly shut down channel
    }

    "rejects HS256 JWTs with invalid audiences" in { implicit env =>
      import com.auth0.jwt.JWT
      import com.daml.network.auth.JwtCallCredential
      import com.daml.network.wallet.v0
      import io.grpc.ManagedChannelBuilder

      val token = JWT
        .create()
        .withAudience("wrong-audience")
        .withSubject(aliceWallet.config.damlUser)
        .sign(AuthUtil.testSignatureAlgorithm)

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

      error.getStatus.getCode.value shouldBe com.google.rpc.Code.UNAUTHENTICATED.getNumber

      channel.shutdown() // to avoid error about improperly shut down channel
    }
  }
}
