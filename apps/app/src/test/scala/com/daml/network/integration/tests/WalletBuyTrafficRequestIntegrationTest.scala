package com.daml.network.integration.tests

import cats.syntax.traverse.*
import com.daml.network.console.{ValidatorAppBackendReference, WalletAppClientReference}
import com.daml.network.http.v0.definitions as d0
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.BracketSynchronous.bracket
import com.daml.network.integration.tests.CNNodeTests.CNNodeIntegrationTestWithSharedEnvironment
import com.daml.network.util.{DomainFeesTestUtil, WalletTestUtil}
import com.daml.network.validator.automation.TopupMemberTrafficTrigger
import com.daml.network.wallet.automation.BuyTrafficRequestTrigger
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.{DomainId, PartyId}

import java.time.Duration
import scala.concurrent.Future
import scala.util.control.NonFatal

class WalletBuyTrafficRequestIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with WalletTxLogTestUtil
    with DomainFeesTestUtil {

  override def environmentDefinition: CNNodeEnvironmentDefinition = {
    CNNodeEnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
  }

  "A wallet traffic request" should {

    "error on creation with bad inputs" when {

      "negative traffic amount" in { implicit env =>
        onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        aliceWalletClient.tap(100.0)

        val badTraffic = -1L
        val errorString = {
          s"HTTP 400 Bad Request POST at '/api/validator/v0/wallet/buy-traffic-requests'. " +
            s"Command failed, message: trafficAmount must be positive"
        }
        failCreatingInvalidTrafficRequest(
          aliceWalletClient,
          aliceValidatorBackend.getValidatorPartyId(),
          errorString,
          trafficAmount = Some(badTraffic),
        )
      }

      "non-existent receiver party" in { implicit env =>
        onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        aliceWalletClient.tap(100.0)

        val badPartyId = PartyId.tryFromProtoPrimitive("badValidator::dummy")
        val errorString = {
          s"HTTP 400 Bad Request POST at '/api/validator/v0/wallet/buy-traffic-requests'. " +
            s"Command failed, message: Could not find participant hosting $badPartyId on domain $activeDomainId"
        }
        failCreatingInvalidTrafficRequest(aliceWalletClient, badPartyId, errorString)
      }

      "unknown domain id" in { implicit env =>
        onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        aliceWalletClient.tap(100.0)

        val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()
        val badDomainId = DomainId.tryFromString("dummy::domain")
        val errorString = {
          s"HTTP 400 Bad Request POST at '/api/validator/v0/wallet/buy-traffic-requests'. " +
            s"Command failed, message: Could not find participant hosting $aliceValidatorParty on domain $badDomainId"
        }
        failCreatingInvalidTrafficRequest(
          aliceWalletClient,
          aliceValidatorParty,
          errorString,
          domainId = Some(badDomainId),
        )
      }
    }

    "be rejected due to lack of funds" in { implicit env =>
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      // No tap here

      createValidTrafficRequest(aliceWalletClient, aliceValidatorBackend, aliceValidatorBackend)

      clue("Alice sees the traffic request as rejected due to lack of funds") {
        eventually() {
          val response = aliceWalletClient.getTrafficRequestStatus(defaultTrackingId)
          inside(response) {
            case d0.GetBuyTrafficRequestStatusResponse(
                  status,
                  _,
                  Some(failure),
                  Some(rejectionReason),
                ) =>
              status shouldBe d0.GetBuyTrafficRequestStatusResponse.Status.Failed
              failure shouldBe d0.GetBuyTrafficRequestStatusResponse.FailureReason.Rejected
              rejectionReason should startWith("out of funds")
          }
        }
      }
    }

    "be rejected if too little traffic is requested" in { implicit env =>
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      aliceWalletClient.tap(100.0)

      createValidTrafficRequest(
        aliceWalletClient,
        aliceValidatorBackend,
        aliceValidatorBackend,
        trafficAmount = Some(minTopupAmount - 1),
      )

      clue("Alice sees the traffic request as rejected due to not enough traffic requested") {
        eventually() {
          val response = aliceWalletClient.getTrafficRequestStatus(defaultTrackingId)
          inside(response) {
            case d0.GetBuyTrafficRequestStatusResponse(
                  status,
                  _,
                  Some(failure),
                  Some(rejectionReason),
                ) =>
              status shouldBe d0.GetBuyTrafficRequestStatusResponse.Status.Failed
              failure shouldBe d0.GetBuyTrafficRequestStatusResponse.FailureReason.Rejected
              rejectionReason should startWith("not enough traffic requested")
          }
        }
      }
    }

    "be completed successfully" in { implicit env =>
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      aliceWalletClient.tap(100.0)

      def topupMemberTrafficTrigger =
        aliceValidatorBackend.appState.automation.trigger[TopupMemberTrafficTrigger]

      // Pause TopupMemberTrafficTrigger to prevent automatic top-ups from interfering with the top-up(s) done here
      bracket(topupMemberTrafficTrigger.pause(), topupMemberTrafficTrigger.resume()) {
        // Since we run multiple suites against the same shared Canton instance, MemberTraffic contracts corresponding to
        // traffic purchases made in a previous suite and synced to the domain could be missing when this test is run.
        // This step creates a new MemberTraffic contract to reconcile these differences, if they exist.
        val initialTrafficAmount =
          ensureOnLedgerStateInSyncWithSequencerState(aliceValidatorBackend)

        createValidTrafficRequest(aliceWalletClient, aliceValidatorBackend, aliceValidatorBackend)
        clue("Alice sees the traffic request as completed") {
          eventually() {
            val response = aliceWalletClient.getTrafficRequestStatus(defaultTrackingId)
            inside(response) { case d0.GetBuyTrafficRequestStatusResponse(status, _, None, None) =>
              status shouldBe d0.GetBuyTrafficRequestStatusResponse.Status.Completed
            }
            clue("Receiving validator's on-ledger traffic is updated") {
              val expectedTotalPurchasedTraffic = (initialTrafficAmount + minTopupAmount)
              getTotalPurchasedTraffic(
                aliceValidatorBackend.participantClient.id,
                activeDomainId,
              ) shouldBe expectedTotalPurchasedTraffic
              // double-check that scan returns the same result
              eventually()(
                sv1ScanBackend
                  .getMemberTrafficStatus(
                    activeDomainId,
                    aliceValidatorBackend.participantClient.id,
                  )
                  .target
                  .totalPurchased shouldBe expectedTotalPurchasedTraffic
              )
            }
          }
        }
        clue("Receiving validator's sequencer traffic limit is updated") {
          // Note that this check would fail if we do not sync the on-ledger and sequencer traffic states at the beginning of this test.
          val expectedTrafficLimit = initialTrafficAmount + minTopupAmount
          eventually() {
            getSequencerTrafficLimit(
              aliceValidatorBackend,
              activeDomainId,
            ) shouldBe expectedTrafficLimit
            // double-check that scan returns the same result
            sv1ScanBackend
              .getMemberTrafficStatus(
                activeDomainId,
                aliceValidatorBackend.participantClient.id,
              )
              .actual
              .totalLimit shouldBe expectedTrafficLimit
          }
        }
      }
    }

    "be deduplicated" in { implicit env =>
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      aliceWalletClient.tap(100.0)

      val successes = loggerFactory.assertLoggedWarningsAndErrorsSeq(
        {
          (1 to 10).toList
            .traverse(_ =>
              Future {
                try {
                  createValidTrafficRequest(
                    aliceWalletClient,
                    aliceValidatorBackend,
                    aliceValidatorBackend,
                    trackingId = Some("dedup"),
                  )
                  1
                } catch {
                  case NonFatal(_) =>
                    0
                }
              }
            )
            .map(_.sum)
            .futureValue
        },
        lines =>
          forAll(lines)(
            _.message should (include(
              "HTTP 409 Conflict"
            ) or include(
              "HTTP 429 Too Many Requests"
            ) or include(
              "Failed clue"
            ))
          ),
      )
      successes shouldBe 1
    }
  }

  private def ensureOnLedgerStateInSyncWithSequencerState(
      validatorApp: ValidatorAppBackendReference
  )(implicit env: CNNodeTests.CNNodeTestConsoleEnvironment) = {
    clue(
      s"Ensuring the on-ledger traffic state for ${validatorApp.participantClient.id} is in sync with the sequencer state"
    ) {
      // on-ledger state of traffic for member on domain - sum of MemberTraffic contracts
      def purchasedTraffic =
        getTotalPurchasedTraffic(validatorApp.participantClient.id, activeDomainId)
      // double-check that scan returns the same result
      eventually()(
        sv1ScanBackend
          .getMemberTrafficStatus(activeDomainId, validatorApp.participantClient.id)
          .target
          .totalPurchased shouldBe purchasedTraffic
      )
      // topology state of traffic for member on domain
      def sequencerTrafficLimit = getSequencerTrafficLimit(validatorApp, activeDomainId)
      // double-check that scan returns the same result
      sv1ScanBackend
        .getMemberTrafficStatus(activeDomainId, validatorApp.participantClient.id)
        .actual
        .totalLimit shouldBe sequencerTrafficLimit

      actAndCheck(
        "Create new MemberTraffic if needed", {
          val diff = sequencerTrafficLimit - purchasedTraffic
          if (diff > 0) {
            buyMemberTraffic(validatorApp, diff, env.environment.clock.now)
          }
        },
      )(
        "See that total purchased traffic is reconciled with sequencer state",
        _ => {
          purchasedTraffic shouldBe sequencerTrafficLimit
        },
      )
      sequencerTrafficLimit
    }
  }

  private def createValidTrafficRequest(
      buyer: WalletAppClientReference,
      buyerValidator: ValidatorAppBackendReference,
      trafficRecipient: ValidatorAppBackendReference,
      trafficAmount: Option[Long] = None,
      domainId: Option[DomainId] = None,
      trackingId: Option[String] = None,
      expiresAt: Option[CantonTimestamp] = None,
  )(implicit env: CNNodeTests.CNNodeTestConsoleEnvironment) = {
    val now = env.environment.clock.now

    def buyTrafficRequestTrigger = buyerValidator
      .userWalletAutomation(buyer.config.ledgerApiUser)
      .trigger[BuyTrafficRequestTrigger]

    // Pause BuyTrafficRequestTrigger so the request doesn't immediately move from Created to Completed
    bracket(buyTrafficRequestTrigger.pause(), buyTrafficRequestTrigger.resume()) {
      actAndCheck(
        "Alice creates a buy traffic request",
        buyer.createBuyTrafficRequest(
          trafficRecipient.getValidatorPartyId(),
          domainId.getOrElse(activeDomainId),
          trafficAmount.getOrElse(minTopupAmount),
          trackingId.getOrElse(defaultTrackingId),
          expiresAt.getOrElse(now.plus(Duration.ofMinutes(1))),
        ),
      )(
        "Alice sees the traffic request as created",
        _ => {
          val response =
            aliceWalletClient.getTrafficRequestStatus(trackingId.getOrElse(defaultTrackingId))
          inside(response) { case d0.GetBuyTrafficRequestStatusResponse(status, None, None, None) =>
            status shouldBe d0.GetBuyTrafficRequestStatusResponse.Status.Created
          }
        },
      )
    }
  }

  private def failCreatingInvalidTrafficRequest(
      buyer: WalletAppClientReference,
      trafficRecipient: PartyId,
      errorString: String,
      trafficAmount: Option[Long] = None,
      domainId: Option[DomainId] = None,
      trackingId: Option[String] = None,
      expiresAt: Option[CantonTimestamp] = None,
  )(implicit env: CNNodeTests.CNNodeTestConsoleEnvironment) = {
    val now = env.environment.clock.now
    val tid = trackingId.getOrElse(defaultTrackingId)
    assertThrowsAndLogsCommandFailures(
      buyer.createBuyTrafficRequest(
        trafficRecipient,
        domainId.getOrElse(activeDomainId),
        trafficAmount.getOrElse(minTopupAmount),
        tid,
        expiresAt.getOrElse(now.plus(Duration.ofMinutes(1))),
      ),
      _.message should include(errorString),
    )
    // getTrafficRequestStatus should return 404 in this case
    val notFoundError =
      s"HTTP 404 Not Found POST at '/api/validator/v0/wallet/buy-traffic-requests/$tid/status'. " +
        s"Command failed, message: Couldn't find transfer offer with tracking id $tid"
    assertThrowsAndLogsCommandFailures(
      buyer.getTrafficRequestStatus(tid),
      _.message should include(notFoundError),
    )
  }

  private val defaultTrackingId = "myTrackingId"

  private def minTopupAmount(implicit env: CNNodeTests.CNNodeTestConsoleEnvironment) =
    sv1ScanBackend.getCoinConfigAsOf(env.environment.clock.now).globalDomain.fees.minTopupAmount
}
