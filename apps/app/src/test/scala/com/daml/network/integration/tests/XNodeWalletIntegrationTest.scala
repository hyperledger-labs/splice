package com.daml.network.integration.tests

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import com.digitalasset.canton.protocol.LfContractId
import com.digitalasset.canton.DomainAlias
import com.daml.network.auth.AuthUtil
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.http.v0.definitions.TapRequest
import com.daml.network.http.v0.wallet.WalletClient
import com.daml.network.integration.tests.CNNodeTests.CNNodeIntegrationTestWithSharedEnvironment
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.util.WalletTestUtil
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.participant.ledger.api.client.JavaDecodeUtil as DecodeUtil
import com.digitalasset.canton.{DiscardOps, HasExecutionContext}
import com.typesafe.config.ConfigFactory
import org.slf4j.event.Level

import scala.concurrent.Future
import scala.util.Try

class XNodeWalletIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with WalletTestUtil {

  override def environmentDefinition: CNNodeEnvironmentDefinition = {
    CNNodeEnvironmentDefinition
      .simpleTopologyX(this.getClass.getSimpleName)
      .addConfigTransform((_, config) =>
        config.copy(akkaConfig =
          Some(
            // these settings are needed for the batching tests to pass,
            // since they require a lot of open / queued requests
            ConfigFactory.parseString(
              """
            |akka.http.host-connection-pool {
            |  max-connections = 20
            |  min-connections = 20
            |  max-open-requests = 128
            |}
            |""".stripMargin
            )
          )
        )
      )
  }

  "A wallet" should {

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
          aliceValidator.participantClientWithAdminToken,
          aliceWallet.config.ledgerApiUser,
          alice,
        )._2
      aliceWallet.rejectAppPaymentRequest(request)

      // The action is completed before the batch is skipped, so we need an eventuallyLogs here
      // to make sure we wait for the message.
      loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
        {
          def submitRequest() =
            try {
              // ... lookup on the payment request fails
              aliceWallet.acceptAppPaymentRequest(request)
            } catch {
              case _: CommandFailure =>
            }

          submitRequest()
        },
        entries => {
          forAtLeast(1, entries)(
            // .. and we see that the empty batch is skipped.
            _.message should include(
              "Coin operation batch was empty after filtering"
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
          (1 to 3).map(_ =>
            createSelfPaymentRequest(
              aliceValidator.participantClientWithAdminToken,
              aliceWallet.config.ledgerApiUser,
              alice,
            )._2
          )
        val offsetBefore =
          aliceValidator.participantClientWithAdminToken.ledger_api.transactions.end()
        // sending three commands in short succession to the idle wallet should lead to two transactions being executed
        // tx 1: first command that arrived is immediately executed
        // tx 2: other commands that arrived after the first command was started are executed in one batch
        requestIds.foreach(requestId =>
          Future(aliceWallet.acceptAppPaymentRequest(requestId)).discard
        )

        // Wait until 2 transactions have been received
        val txs = aliceValidator.participantClientWithAdminToken.ledger_api_extensions.transactions
          .treesJava(Set(alice), completeAfter = 2, beginOffset = offsetBefore)
        val createdCoinsInTx =
          txs.map(DecodeUtil.decodeAllCreatedTree(coinCodegen.Coin.COMPANION)(_).size)
        val createdLockedCoinsInTx =
          txs.map(DecodeUtil.decodeAllCreatedTree(coinCodegen.LockedCoin.COMPANION)(_).size)

        // in rare cases all 3 commands get batched in one transaction,
        // so we only check if the 3 commands are included in the 2 transactions

        // create change
        createdCoinsInTx.sum shouldBe 3
        // lock coin
        createdLockedCoinsInTx.sum shouldBe 3

        (createdCoinsInTx zip createdLockedCoinsInTx).foreach { case (cc, clc) => cc shouldBe clc }
      }

      "be batched up to `batchSize` concurrent coin-operations" in { implicit env =>
        val batchSize = aliceValidator.config.treasury.batchSize
        val alice = onboardWalletUser(aliceWallet, aliceValidator)
        aliceWallet.tap(1000)

        val requests =
          (0 to batchSize + 1).map(_ =>
            createSelfPaymentRequest(
              aliceValidator.participantClientWithAdminToken,
              aliceWallet.config.ledgerApiUser,
              alice,
            )._2
          )

        eventually() {
          aliceValidator.participantClientWithAdminToken.ledger_api_extensions.acs.filterJava(
            walletCodegen.AppPaymentRequest.COMPANION
          )(alice) should have size (batchSize.toLong + 2)
        }

        val offsetBefore =
          aliceValidator.participantClientWithAdminToken.ledger_api.transactions.end()

        requests.foreach(request => Future(aliceWallet.acceptAppPaymentRequest(request)).discard)

        // 3 txs; usually (but not always):
        // tx 1: initial transfer
        // tx 2: batchSize subsequent batched transfers
        // tx 3: single transfer that was not picked due to the batch size limit
        val txs = aliceValidator.participantClientWithAdminToken.ledger_api_extensions.transactions
          .treesJava(Set(alice), completeAfter = 3, beginOffset = offsetBefore)
        val createdCoinsInTx =
          txs.map(DecodeUtil.decodeAllCreatedTree(coinCodegen.Coin.COMPANION)(_).size)
        val createdLockedCoinsInTx =
          txs.map(DecodeUtil.decodeAllCreatedTree(coinCodegen.LockedCoin.COMPANION)(_).size)

        // all operations are contained in at most 3 transactions
        createdCoinsInTx.sum shouldBe (batchSize.toLong + 2)
        createdLockedCoinsInTx.sum shouldBe (batchSize.toLong + 2)

        // one transaction is "maxed out"
        createdCoinsInTx.exists(_ == batchSize.toLong)
        createdLockedCoinsInTx.exists(_ == batchSize.toLong)

        (createdCoinsInTx zip createdLockedCoinsInTx).foreach { case (cc, clc) => cc shouldBe clc }
      }

      "filter stale actions from batches, and complete the rest" in { implicit env =>
        val alice = onboardWalletUser(aliceWallet, aliceValidator)

        aliceWallet.tap(1)
        // creating payment request
        val request =
          createSelfPaymentRequest(
            aliceValidator.participantClientWithAdminToken,
            aliceWallet.config.ledgerApiUser,
            alice,
          )._2
        // Reject it so that we have a reference to an already archived app payment request
        aliceWallet.rejectAppPaymentRequest(request)

        loggerFactory.suppressErrors({

          val tapsBefore = Range(0, 3).map(_ => Future(Try(aliceWallet.tap(10))))

          // fails because we don't have a payment request - so removed from batch & error is reported back
          val failedAcceptF = Future(Try(aliceWallet.acceptAppPaymentRequest(request)))

          val tapsAfter = Range(0, 3).map(_ => Future(Try(aliceWallet.tap(10))))

          // Wait for all futures to complete
          val successfulTaps = (tapsBefore ++ tapsAfter).map(_.futureValue).count(_.isSuccess)
          if (failedAcceptF.futureValue.isSuccess)
            fail("The AcceptTransferOffer action unexpectedly succeeded")

          successfulTaps should be(
            (tapsBefore ++ tapsAfter).length
          ) withClue ("All taps should succeed")

          checkBalance(
            aliceWallet,
            1,
            (1 + successfulTaps * 10 - 1, 1 + successfulTaps * 10),
            exactly(0),
            exactly(0),
          )
        })
      }
    }

    "reject HS256 JWTs with invalid signatures" in { implicit env =>
      implicit val sys = env.actorSystem
      CoordinatedShutdown(sys).addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "cleanup") {
        () =>
          Http().shutdownAllConnectionPools().map(_ => Done)
      }
      import com.auth0.jwt.JWT
      import com.auth0.jwt.algorithms.Algorithm

      val invalidSignatureToken = JWT
        .create()
        .withAudience(aliceValidator.config.auth.audience)
        .withSubject(aliceWallet.config.ledgerApiUser)
        .sign(Algorithm.HMAC256("wrong-secret"))

      implicit val httpClient: HttpRequest => Future[HttpResponse] =
        request => Http().singleRequest(request = request)
      val walletClient = WalletClient(aliceWallet.httpClientConfig.url.toString())

      def tokenHeader(token: String) = List(Authorization(OAuth2BearerToken(token)))

      val responseForInvalidSignature =
        walletClient
          .tap(TapRequest(amount = "10.0"), headers = tokenHeader(invalidSignatureToken))
          .leftOrFail("should fail with unauthorized")
          .futureValue
          .value
      responseForInvalidSignature.status should be(StatusCodes.Unauthorized)
    }

    "reject HS256 JWTs with invalid audiences" in { implicit env =>
      implicit val sys = env.actorSystem
      CoordinatedShutdown(sys).addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "cleanup") {
        () =>
          Http().shutdownAllConnectionPools().map(_ => Done)
      }

      import com.auth0.jwt.JWT

      val invalidAudienceToken = JWT
        .create()
        .withAudience("wrong-audience")
        .withSubject(aliceWallet.config.ledgerApiUser)
        .sign(AuthUtil.testSignatureAlgorithm)

      implicit val httpClient: HttpRequest => Future[HttpResponse] =
        request => Http().singleRequest(request = request)
      val walletClient = WalletClient(aliceWallet.httpClientConfig.url.toString())

      def tokenHeader(token: String) = List(Authorization(OAuth2BearerToken(token)))

      val responseForInvalidSignature =
        walletClient
          .tap(TapRequest(amount = "10.0"), headers = tokenHeader(invalidAudienceToken))
          .leftOrFail("should fail with unauthorized")
          .futureValue
          .value

      responseForInvalidSignature.status should be(StatusCodes.Unauthorized)
    }

    "support featured app rewards" in { implicit env =>
      val splitwellProvider = onboardWalletUser(splitwellProviderWallet, splitwellValidator)
      splitwellProviderWallet.userStatus().hasFeaturedAppRight shouldBe false

      clue("Canceling a featured app right before getting it, nothing bad should happen")(
        splitwellProviderWallet.cancelFeaturedAppRight()
      )

      clue("grant a featured app right to splitwell provider") {
        eventually() {
          noException should be thrownBy svcClient.grantFeaturedAppRight(splitwellProvider)
        }
      }

      clue("splitwell provider is featured") {
        eventually() {
          inside(sv1Scan.listFeaturedAppRights()) { case Seq(r) =>
            r.payload.provider shouldBe splitwellProvider.toProtoPrimitive
          }
          splitwellProviderWallet.userStatus().hasFeaturedAppRight shouldBe true
        }
      }

      actAndCheck(
        "splitwell cancels its own featured app right",
        splitwellProviderWallet.cancelFeaturedAppRight(),
      )(
        "splitwell provider is no longer featured",
        { _ =>
          sv1Scan.listFeaturedAppRights() shouldBe empty
          splitwellProviderWallet.userStatus().hasFeaturedAppRight shouldBe false
        },
      )

      actAndCheck(
        "Splitwell provider grants itself a featured app right",
        // We need to retry as the command might failed due to inactive cached CoinRules contract
        // The failed command submission will triggers a cache invalidation
        retryCommandSubmission(splitwellProviderWallet.selfGrantFeaturedAppRight()),
      )(
        "splitwell provider is featured",
        { featuredAppRight =>
          {
            inside(sv1Scan.listFeaturedAppRights()) { case Seq(r) =>
              r.contractId shouldBe featuredAppRight
            }
            splitwellProviderWallet.userStatus().hasFeaturedAppRight shouldBe true
          }
        },
      )
    }

    "transfer AppPaymentRequest and DeliveryOffer to global domain" in { implicit env =>
      val splitwellDomainId = aliceValidator.participantClientWithAdminToken.domains.id_of(
        DomainAlias.tryCreate("splitwell")
      )
      val aliceParty = onboardWalletUser(aliceWallet, aliceValidator)
      aliceWallet.tap(50)
      val (_, (deliveryOfferId, requestId)) = actAndCheck(
        "Create payment request on private domain",
        createSelfPaymentRequest(
          aliceValidator.participantClientWithAdminToken,
          aliceWallet.config.ledgerApiUser,
          aliceParty,
          domainId = Some(splitwellDomainId),
        ),
      )(
        "request and delivery offer get transferred to global domain",
        { case (offer, request, _) =>
          val domains = aliceValidator.participantClientWithAdminToken.transfer
            .lookup_contract_domain(offer, request)
          domains shouldBe Map[LfContractId, String](
            javaToScalaContractId(request) -> "global",
            javaToScalaContractId(offer) -> "global",
          )
          (offer, request)
        },
      )
      val request = eventually() {
        inside(aliceWallet.listAppPaymentRequests()) { case Seq(req) =>
          req
        }
      }
      request.appPaymentRequest.contractId shouldBe requestId
      // Left is view, right is TestDeliveryOffer
      request.deliveryOffer.contractId.contractId shouldBe deliveryOfferId.contractId
      actAndCheck(
        "Accept payment request",
        aliceWallet.acceptAppPaymentRequest(request.appPaymentRequest.contractId),
      )(
        "wait for the accepted payment to appear",
        _ =>
          inside(aliceWallet.listAcceptedAppPayments()) { case Seq(accepted) =>
            accepted
          },
      )
    }
  }
}
