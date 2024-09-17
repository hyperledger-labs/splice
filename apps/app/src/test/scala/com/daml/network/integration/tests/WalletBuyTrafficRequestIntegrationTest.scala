package com.daml.network.integration.tests

import cats.syntax.traverse.*
import com.daml.network.console.{ValidatorAppBackendReference, WalletAppClientReference}
import com.daml.network.http.v0.definitions as d0
import com.daml.network.integration.EnvironmentDefinition
import com.daml.network.integration.tests.SpliceTests.BracketSynchronous.bracket
import com.daml.network.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import com.daml.network.util.{SynchronizerFeesTestUtil, WalletTestUtil}
import com.daml.network.validator.automation.TopupMemberTrafficTrigger
import com.daml.network.wallet.automation.{
  CompleteBuyTrafficRequestTrigger,
  ExpireBuyTrafficRequestsTrigger,
}
import com.daml.network.wallet.store.TxLogEntry
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.{DomainId, Member, PartyId}

import java.time.Duration
import scala.concurrent.Future
import scala.util.control.NonFatal
import scala.jdk.CollectionConverters.*

class WalletBuyTrafficRequestIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with WalletTxLogTestUtil
    with SynchronizerFeesTestUtil {

  override def environmentDefinition: EnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
  }

  "A wallet traffic request" should {

    "error on creation with bad inputs" when {

      "negative traffic amount" in { implicit env =>
        onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        aliceWalletClient.tap(100.0)

        val badTraffic = -1L
        val errorString = {
          s"HTTP 400 Bad Request POST at '/api/validator/v0/wallet/buy-traffic-requests' on 127.0.0.1:5503. " +
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
          s"HTTP 400 Bad Request POST at '/api/validator/v0/wallet/buy-traffic-requests' on 127.0.0.1:5503. " +
            s"Command failed, message: Could not find participant hosting $badPartyId on domain $activeSynchronizerId"
        }
        failCreatingInvalidTrafficRequest(aliceWalletClient, badPartyId, errorString)
      }

      "unknown domain id" in { implicit env =>
        onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        aliceWalletClient.tap(100.0)

        val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()
        val badDomainId = DomainId.tryFromString("dummy::domain")
        val errorString = {
          s"HTTP 400 Bad Request POST at '/api/validator/v0/wallet/buy-traffic-requests' on 127.0.0.1:5503. " +
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
            case d0.GetBuyTrafficRequestStatusResponse.members.BuyTrafficRequestFailedResponse(
                  d0.BuyTrafficRequestFailedResponse(
                    status,
                    failure,
                    Some(rejectionReason),
                  )
                ) =>
              status shouldBe TxLogEntry.Http.BuyTrafficRequestStatus.Failed
              failure shouldBe d0.BuyTrafficRequestFailedResponse.FailureReason.Rejected
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
            case d0.GetBuyTrafficRequestStatusResponse.members.BuyTrafficRequestFailedResponse(
                  d0.BuyTrafficRequestFailedResponse(
                    status,
                    failure,
                    Some(rejectionReason),
                  )
                ) =>
              status shouldBe TxLogEntry.Http.BuyTrafficRequestStatus.Failed
              failure shouldBe d0.BuyTrafficRequestFailedResponse.FailureReason.Rejected
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
      bracket(topupMemberTrafficTrigger.pause().futureValue, topupMemberTrafficTrigger.resume()) {
        // Since we run multiple suites against the same shared Canton instance, MemberTraffic contracts corresponding to
        // traffic purchases made in a previous suite and synced to the domain could be missing when this test is run.
        // This step creates a new MemberTraffic contract to reconcile these differences, if they exist.
        val initialPurchasedTraffic =
          ensureOnLedgerStateInSyncWithSequencerState(aliceValidatorBackend)

        createValidTrafficRequest(aliceWalletClient, aliceValidatorBackend, aliceValidatorBackend)
        clue("Alice sees the traffic request as completed") {
          eventually() {
            val response = aliceWalletClient.getTrafficRequestStatus(defaultTrackingId)
            inside(response) {
              case d0.GetBuyTrafficRequestStatusResponse.members.BuyTrafficRequestCompletedResponse(
                    d0.BuyTrafficRequestCompletedResponse(status, _)
                  ) =>
                status shouldBe TxLogEntry.Http.BuyTrafficRequestStatus.Completed
            }
            clue("Receiving validator's on-ledger traffic is updated") {
              val expectedTotalPurchasedTraffic = initialPurchasedTraffic + minTopupAmount
              getTotalPurchasedTraffic(
                aliceValidatorBackend.participantClient.id,
                activeSynchronizerId,
              ) shouldBe expectedTotalPurchasedTraffic
              // double-check that scan returns the same result
              val participantId = sv1ScanBackend.getPartyToParticipant(
                activeSynchronizerId,
                aliceValidatorBackend.getValidatorPartyId(),
              )
              eventually()(
                sv1ScanBackend
                  .getMemberTrafficStatus(
                    activeSynchronizerId,
                    participantId,
                  )
                  .target
                  .totalPurchased shouldBe expectedTotalPurchasedTraffic
              )
            }
          }
        }
        clue("Receiving validator's sequencer traffic limit is updated") {
          val trafficLimitOffset = getTrafficLimitOffset(aliceValidatorBackend.participantClient.id)
          val expectedTrafficLimit = initialPurchasedTraffic + minTopupAmount + trafficLimitOffset
          // Note that this check would fail if we do not sync the on-ledger and sequencer traffic states at the beginning of this test.
          withClue(
            s"Initial purchased: $initialPurchasedTraffic, min topup $minTopupAmount, offset $trafficLimitOffset"
          ) {
            eventually() {
              getTrafficState(
                aliceValidatorBackend,
                activeSynchronizerId,
              ).extraTrafficPurchased.value shouldBe expectedTrafficLimit
              // double-check that scan returns the same result
              val participantId = sv1ScanBackend.getPartyToParticipant(
                activeSynchronizerId,
                aliceValidatorBackend.getValidatorPartyId(),
              )
              sv1ScanBackend
                .getMemberTrafficStatus(
                  activeSynchronizerId,
                  participantId,
                )
                .actual
                .totalLimit shouldBe expectedTrafficLimit
            }
          }
        }
      }
    }

    "eventually expire" in { implicit env =>
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      aliceWalletClient.tap(100.0)

      val trackingId = "expiring"

      actAndCheck(
        "Create a traffic request that immediately expires", {

          def expireBuyTrafficRequestsTrigger = aliceValidatorBackend
            .userWalletAutomation(aliceWalletClient.config.ledgerApiUser)
            .futureValue
            .trigger[ExpireBuyTrafficRequestsTrigger]

          bracket(
            expireBuyTrafficRequestsTrigger.pause().futureValue,
            expireBuyTrafficRequestsTrigger.resume(),
          ) {
            createValidTrafficRequest(
              aliceWalletClient,
              aliceValidatorBackend,
              aliceValidatorBackend,
              trafficAmount = Some(minTopupAmount - 1),
              trackingId = Some(trackingId),
              expiresAt = Some(env.environment.clock.now.plus(Duration.ofNanos(1))),
            )
          }

        },
      )(
        "Alice sees the traffic request as Expired",
        _ => {
          inside(aliceWalletClient.getTrafficRequestStatus(trackingId)) {
            case d0.GetBuyTrafficRequestStatusResponse.members.BuyTrafficRequestFailedResponse(
                  d0.BuyTrafficRequestFailedResponse(status, failureReason, None)
                ) =>
              status shouldBe TxLogEntry.Http.BuyTrafficRequestStatus.Failed
              failureReason shouldBe d0.BuyTrafficRequestFailedResponse.FailureReason.Expired
          }
        },
      )
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
  )(implicit env: SpliceTests.SpliceTestConsoleEnvironment) = {
    clue(
      s"Ensuring the on-ledger traffic state for ${validatorApp.participantClient.id} is in sync with the sequencer state"
    ) {
      // on-ledger state of traffic for member on domain - sum of MemberTraffic contracts
      def purchasedTraffic =
        getTotalPurchasedTraffic(validatorApp.participantClient.id, activeSynchronizerId)
      // double-check that scan returns the same result
      val participantId =
        sv1ScanBackend.getPartyToParticipant(
          activeSynchronizerId,
          validatorApp.getValidatorPartyId(),
        )
      eventually()(
        sv1ScanBackend
          .getMemberTrafficStatus(activeSynchronizerId, participantId)
          .target
          .totalPurchased shouldBe purchasedTraffic
      )
      // topology state of traffic for member on domain
      def sequencerTrafficLimit =
        getTrafficState(validatorApp, activeSynchronizerId).extraTrafficPurchased.value

      // double-check that scan returns the same result
      sv1ScanBackend
        .getMemberTrafficStatus(activeSynchronizerId, participantId)
        .actual
        .totalLimit shouldBe sequencerTrafficLimit

      val trafficLimitOffset = getTrafficLimitOffset(participantId)
      val diff =
        sequencerTrafficLimit - purchasedTraffic - trafficLimitOffset
      withClue(
        s"Sequencer limit = $sequencerTrafficLimit, Purchased amount = $purchasedTraffic, offset = $trafficLimitOffset"
      ) {
        actAndCheck(
          "Create new MemberTraffic if needed", {
            if (diff > 0) {
              clue(s"Creating new MemberTraffic contract for amount $diff") {
                buyMemberTraffic(validatorApp, diff.longValue, env.environment.clock.now)
              }
            }
          },
        )(
          "See that total purchased traffic is reconciled with sequencer state",
          _ => {
            purchasedTraffic + trafficLimitOffset shouldBe sequencerTrafficLimit
          },
        )
      }
      purchasedTraffic
    }
  }

  // The traffic limit offset for a member is the amount of traffic that it consumed in previous test suites against the same Canton instance
  // that we effectively give back to the member at the beginning of this test. See ReconcileSequencerLimitWithMemberTrafficTrigger for details.
  private def getTrafficLimitOffset(
      memberId: Member
  )(implicit env: SpliceTests.SpliceTestConsoleEnvironment) = {
    sv1Backend
      .getDsoInfo()
      .dsoRules
      .payload
      .initialTrafficState
      .asScala
      .get(memberId.toProtoPrimitive)
      .fold(0L)(_.consumedTraffic)
  }

  private def createValidTrafficRequest(
      buyer: WalletAppClientReference,
      buyerValidator: ValidatorAppBackendReference,
      trafficRecipient: ValidatorAppBackendReference,
      trafficAmount: Option[Long] = None,
      domainId: Option[DomainId] = None,
      trackingId: Option[String] = None,
      expiresAt: Option[CantonTimestamp] = None,
  )(implicit env: SpliceTests.SpliceTestConsoleEnvironment) = {
    val now = env.environment.clock.now

    def completeBuyTrafficRequestTrigger = buyerValidator
      .userWalletAutomation(buyer.config.ledgerApiUser)
      .futureValue
      .trigger[CompleteBuyTrafficRequestTrigger]

    // Pause CompleteBuyTrafficRequestTrigger so the request doesn't immediately move from Created to Completed
    bracket(
      completeBuyTrafficRequestTrigger.pause().futureValue,
      completeBuyTrafficRequestTrigger.resume(),
    ) {
      actAndCheck(
        "Alice creates a buy traffic request",
        buyer.createBuyTrafficRequest(
          trafficRecipient.getValidatorPartyId(),
          domainId.getOrElse(activeSynchronizerId),
          trafficAmount.getOrElse(minTopupAmount),
          trackingId.getOrElse(defaultTrackingId),
          expiresAt.getOrElse(now.plus(Duration.ofMinutes(1))),
        ),
      )(
        "Alice sees the traffic request as created",
        _ => {
          val response =
            aliceWalletClient.getTrafficRequestStatus(trackingId.getOrElse(defaultTrackingId))
          inside(response) {
            case d0.GetBuyTrafficRequestStatusResponse.members
                  .BuyTrafficRequestCreatedResponse(d0.BuyTrafficRequestCreatedResponse(status)) =>
              status shouldBe TxLogEntry.Http.BuyTrafficRequestStatus.Created
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
  )(implicit env: SpliceTests.SpliceTestConsoleEnvironment) = {
    val now = env.environment.clock.now
    val tid = trackingId.getOrElse(defaultTrackingId)
    assertThrowsAndLogsCommandFailures(
      buyer.createBuyTrafficRequest(
        trafficRecipient,
        domainId.getOrElse(activeSynchronizerId),
        trafficAmount.getOrElse(minTopupAmount),
        tid,
        expiresAt.getOrElse(now.plus(Duration.ofMinutes(1))),
      ),
      _.message should include(errorString),
    )
    // getTrafficRequestStatus should return 404 in this case
    val notFoundError =
      s"HTTP 404 Not Found POST at '/api/validator/v0/wallet/buy-traffic-requests/$tid/status' on 127.0.0.1:5503. " +
        s"Command failed, message: Couldn't find buy traffic request with tracking id $tid"
    assertThrowsAndLogsCommandFailures(
      buyer.getTrafficRequestStatus(tid),
      _.message should include(notFoundError),
    )
  }

  private val defaultTrackingId = "myTrackingId"

  private def minTopupAmount(implicit env: SpliceTests.SpliceTestConsoleEnvironment) =
    sv1ScanBackend
      .getAmuletConfigAsOf(env.environment.clock.now)
      .decentralizedSynchronizer
      .fees
      .minTopupAmount
}
