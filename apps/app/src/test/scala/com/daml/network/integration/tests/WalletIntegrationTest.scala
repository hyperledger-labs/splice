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
import com.daml.network.integration.tests.CoinTests.CoinIntegrationTestWithSharedEnvironment
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.util.WalletTestUtil
import com.daml.network.wallet.admin.api.client.commands.HttpWalletAppClient
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.participant.ledger.api.client.JavaDecodeUtil as DecodeUtil
import com.digitalasset.canton.{DiscardOps, HasExecutionContext}
import com.typesafe.config.ConfigFactory
import org.slf4j.event.Level

import scala.concurrent.Future

class WalletIntegrationTest
    extends CoinIntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with WalletTestUtil {

  override def environmentDefinition: CoinEnvironmentDefinition = {
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
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
          aliceWallet.config.ledgerApiUser,
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
          (1 to 3).map(i =>
            createSelfPaymentRequest(
              aliceValidator.remoteParticipantWithAdminToken,
              aliceWallet.config.ledgerApiUser,
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
        val txs = aliceValidator.remoteParticipantWithAdminToken.ledger_api_extensions.transactions
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

        createdCoinsInTx(0) shouldBe createdLockedCoinsInTx(0)
        createdCoinsInTx(1) shouldBe createdLockedCoinsInTx(1)
      }

      "be batched up to `batchSize` concurrent coin-operations" in { implicit env =>
        val batchSize = aliceWalletBackend.config.treasury.batchSize
        val alice = onboardWalletUser(aliceWallet, aliceValidator)
        aliceWallet.tap(1000)

        val requests =
          (0 to batchSize + 1).map(_ =>
            createSelfPaymentRequest(
              aliceValidator.remoteParticipantWithAdminToken,
              aliceWallet.config.ledgerApiUser,
              alice,
            )._2
          )

        eventually() {
          aliceValidator.remoteParticipantWithAdminToken.ledger_api_extensions.acs.filterJava(
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
        val txs = aliceValidator.remoteParticipantWithAdminToken.ledger_api_extensions.transactions
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
          aliceValidator.remoteParticipantWithAdminToken.ledger_api_extensions.acs
            .awaitJava(coinCodegen.Coin.COMPANION)(alice)
          // creating payment request
          val request =
            createSelfPaymentRequest(
              aliceValidator.remoteParticipantWithAdminToken,
              aliceWallet.config.ledgerApiUser,
              alice,
            )._2
          // Reject it so that we have a reference to an already archived app payment request
          aliceWallet.rejectAppPaymentRequest(request)

          // solo tap will kick off batch, which usually contains only one coin operation
          val tap1F = Future(aliceWallet.tap(10))

          // following three commands will usually end up in one batch
          val tap2F = Future(aliceWallet.tap(10))
          // fails because we don't have a payment request - so removed from batch & error is reported back
          val failedAcceptF = Future(
            loggerFactory.assertThrowsAndLogs[CommandFailure](
              aliceWallet.acceptAppPaymentRequest(request),
              _.errorMessage should include regex ("acceptAppPaymentRequest: contract_id not found.*"),
              _.errorMessage should include regex (HttpWalletAppClient.Err.AcceptAppPaymentRequestResponse.NotFound.value),
            )
          )
          val tap3F = Future(aliceWallet.tap(10))
          // Wait for all futures to complete
          tap1F.futureValue
          tap2F.futureValue
          tap3F.futureValue

          failedAcceptF.futureValue

          eventually() {
            // all four taps went through
            // but no money was deducted as the app payment failed
            checkBalance(aliceWallet, 1, (39, 40), exactly(0), exactly(0))
          }
      }

      "retry a batch if it fails due to contention" in { implicit env =>
        val alice = onboardWalletUser(aliceWallet, aliceValidator)
        aliceWallet.tap(10)

        val request =
          createSelfPaymentRequest(
            aliceValidator.remoteParticipantWithAdminToken,
            aliceWallet.config.ledgerApiUser,
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
              catch {
                case _: CommandFailure => ()
              }
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
            forAtLeast(1, entries)( // fails in HttpWalletHandler
              _.message should include regex (
                "contract_id not found.*"
              )
            )
            forAtLeast(1, entries)( // fails in HttpWalletAppClient
              _.message should include regex (
                "AcceptAppPayment request not found.*"
              )
            )
          },
        )

        // eventually, the rejection goes through
        rejectF.futureValue
        eventually()(aliceWallet.listAppPaymentRequests() shouldBe empty)
        checkBalance(aliceWallet, 1, (675, 676), exactly(0), exactly(0))
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
        .withAudience(aliceWalletBackend.config.auth.audience)
        .withSubject(aliceWallet.config.ledgerApiUser)
        .sign(Algorithm.HMAC256("wrong-secret"))

      implicit val httpClient: HttpRequest => Future[HttpResponse] =
        request => Http().singleRequest(request = request)
      val walletClient = WalletClient(aliceWallet.httpClientConfig.url)

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
      val walletClient = WalletClient(aliceWallet.httpClientConfig.url)

      def tokenHeader(token: String) = List(Authorization(OAuth2BearerToken(token)))

      val responseForInvalidSignature =
        walletClient
          .tap(TapRequest(amount = "10.0"), headers = tokenHeader(invalidAudienceToken))
          .leftOrFail("should fail with unauthorized")
          .futureValue
          .value

      responseForInvalidSignature.status should be(StatusCodes.Unauthorized)
    }

    "list one connected domain" in { implicit env =>
      eventually() {
        providerSplitwellBackend.listConnectedDomains().keySet shouldBe Set("global", "splitwell")
      }
    }

    "support featured app rewards" in { implicit env =>
      val splitwellProvider = onboardWalletUser(splitwellProviderWallet, splitwellValidator)
      splitwellProviderWallet.userStatus().hasFeaturedAppRight shouldBe false

      clue("Canceling a featured app right before getting it, nothing bad should happen")(
        splitwellProviderWallet.cancelFeaturedAppRight()
      )

      actAndCheck(
        "grant a featured app right to splitwell provider", {
          svcClient.grantFeaturedAppRight(splitwellProvider)
        },
      )(
        "splitwell provider is featured",
        { _ =>
          inside(scan.listFeaturedAppRights()) { case Seq(r) =>
            r.payload.provider shouldBe splitwellProvider.toProtoPrimitive
          }
          splitwellProviderWallet.userStatus().hasFeaturedAppRight shouldBe true
        },
      )

      actAndCheck(
        "splitwell cancels its own featured app right",
        splitwellProviderWallet.cancelFeaturedAppRight(),
      )(
        "splitwell provider is no longer featured",
        { _ =>
          scan.listFeaturedAppRights() shouldBe empty
          splitwellProviderWallet.userStatus().hasFeaturedAppRight shouldBe false
        },
      )

      actAndCheck(
        "Splitwell provider grants itself a featured app right",
        splitwellProviderWallet.selfGrantFeaturedAppRight(),
      )(
        "splitwell provider is featured",
        { featuredAppRight =>
          {
            inside(scan.listFeaturedAppRights()) { case Seq(r) =>
              r.contractId shouldBe featuredAppRight
            }
            splitwellProviderWallet.userStatus().hasFeaturedAppRight shouldBe true
          }
        },
      )
    }

    "transfer AppPaymentRequest and DeliveryOffer to global domain" in { implicit env =>
      val splitwellDomainId = aliceValidator.remoteParticipantWithAdminToken.domains.id_of(
        DomainAlias.tryCreate("splitwell")
      )
      val aliceParty = onboardWalletUser(aliceWallet, aliceValidator)
      aliceWallet.tap(50)
      val (_, (_, requestId)) = actAndCheck(
        "Create payment request on private domain",
        createSelfPaymentRequest(
          aliceValidator.remoteParticipantWithAdminToken,
          aliceWallet.config.ledgerApiUser,
          aliceParty,
          domainId = Some(splitwellDomainId),
        ),
      )(
        "request and delivery offer get transferred to public domain",
        { case (offer, request, _) =>
          val domains = aliceValidator.remoteParticipantWithAdminToken.transfer
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
      request.contractId shouldBe requestId
      actAndCheck(
        "Accept payment request",
        aliceWallet.acceptAppPaymentRequest(request.contractId),
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
