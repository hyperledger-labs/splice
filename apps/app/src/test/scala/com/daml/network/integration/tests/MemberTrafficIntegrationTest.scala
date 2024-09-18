package com.daml.network.integration.tests

import com.daml.network.codegen.java.splice.decentralizedsynchronizer.MemberTraffic
import com.daml.network.http.v0.definitions as d0
import com.daml.network.integration.EnvironmentDefinition
import com.daml.network.integration.tests.SpliceTests.{
  IntegrationTestWithSharedEnvironment,
  SpliceTestConsoleEnvironment,
}
import com.daml.network.util.{SynchronizerFeesTestUtil, WalletTestUtil}
import com.daml.network.wallet.store.TxLogEntry.Http.BuyTrafficRequestStatus
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.Member
import org.slf4j.event.Level

class MemberTrafficIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with SynchronizerFeesTestUtil
    with WalletTestUtil {

  override def environmentDefinition: EnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      // NOTE: automatic top-ups should be explicitly disabled for this test as currently written
      .withTrafficTopupsDisabled
  }

  "SV automation" should {

    "handle contracts with an invalid member id" in { implicit env =>
      val now = env.environment.clock.now
      val decentralizedSynchronizerConfig =
        sv1ScanBackend.getAmuletConfigAsOf(now).decentralizedSynchronizer
      val trafficAmount =
        Math.max(decentralizedSynchronizerConfig.fees.minTopupAmount.toLong, 1_000_000L)
      val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      aliceWalletClient.tap(100)

      def purchaseTrafficForMember(memberId: String, trackingId: String): Unit = {
        clue(s"Purchase traffic for member ${memberId}") {
          createBuyTrafficRequest(
            aliceValidatorBackend,
            aliceParty,
            memberId,
            trafficAmount,
            trackingId,
          )
        }
        clue("Traffic request is ingested into wallet tx log") {
          // we use eventually succeeds to ensure error 404s are not logged
          eventuallySucceeds() {
            aliceWalletClient.getTrafficRequestStatus(trackingId)
          }
        }
        clue("Wallet automation completes the request") {
          eventually() {
            inside(aliceWalletClient.getTrafficRequestStatus(trackingId)) {
              case d0.GetBuyTrafficRequestStatusResponse.members.BuyTrafficRequestCompletedResponse(
                    d0.BuyTrafficRequestCompletedResponse(status, _)
                  ) =>
                status shouldBe BuyTrafficRequestStatus.Completed
            }
          }
        }
      }

      purchaseTrafficForMember("bad", "bad")

      // The "bad" contract should be ignored and not blow up the ACS ingestion pipeline or
      // the triggers that consume MemberTraffic contracts.
      // To test this, we try to create another MemberTraffic contract and see it succeed.

      purchaseTrafficForMember("PAR::validator::dummy", "good")

    }

    "merge duplicate member traffic contracts" in { implicit env =>
      val now = env.environment.clock.now
      val domainFeesConfig = sv1ScanBackend.getAmuletConfigAsOf(now).decentralizedSynchronizer.fees
      val trafficAmount = Math.max(domainFeesConfig.minTopupAmount.toLong, 1_000_000L)
      val participantId = aliceValidatorBackend.participantClient.id
      val trafficContractsMergeThreshold =
        sv1Backend.getDsoInfo().dsoRules.payload.config.numMemberTrafficContractsThreshold

      clue("No member traffic contracts for aliceValidator exist initially") {
        listMemberTrafficContracts(participantId) isEmpty
      }

      actAndCheck(
        "Buy traffic for aliceValidator 5 times",
        (1L to trafficContractsMergeThreshold).foreach(_ =>
          buyMemberTraffic(aliceValidatorBackend, trafficAmount, now)
        ),
      )(
        "5 new MemberTraffic contracts get created",
        _ =>
          listMemberTrafficContracts(participantId).foreach(traffic => {
            traffic.data.memberId shouldBe participantId.toProtoPrimitive
            traffic.data.totalPurchased shouldBe trafficAmount
          }),
      )
      clue("Buying traffic once more triggers the merging of contracts") {
        loggerFactory.assertEventuallyLogsSeq(SuppressionRule.Level(Level.INFO))(
          buyMemberTraffic(aliceValidatorBackend, trafficAmount, now),
          lines => {
            forExactly(1, lines)(
              _.message should include regex (s"Merged .* member traffic contracts for member $participantId")
            )
          },
        )
        eventually() {
          inside(listMemberTrafficContracts(participantId)) { case Seq(mergedTraffic) =>
            mergedTraffic.data.memberId shouldBe participantId.toProtoPrimitive
            mergedTraffic.data.totalPurchased shouldBe 6 * trafficAmount
          }
        }
      }

    }
  }

  "Scan" should {
    "serve a member's traffic status as reported by the sequencer" in { implicit env =>
      val memberId = aliceValidatorBackend.participantClient.id

      val actualStateAsPerSequencer = getTrafficState(aliceValidatorBackend, activeSynchronizerId)
      val actualTotalPurchasedAsPerDso =
        listMemberTrafficContracts(memberId).map(_.data.totalPurchased.toLong).sum

      val statusAsPerScan = sv1ScanBackend.getMemberTrafficStatus(activeSynchronizerId, memberId)

      statusAsPerScan.actual.totalConsumed shouldBe actualStateAsPerSequencer.extraTrafficConsumed.value
      statusAsPerScan.actual.totalLimit shouldBe actualStateAsPerSequencer.extraTrafficPurchased.value
      statusAsPerScan.target.totalPurchased shouldBe actualTotalPurchasedAsPerDso
    }
  }

  private def listMemberTrafficContracts(
      memberId: Member
  )(implicit env: SpliceTestConsoleEnvironment) = {
    sv1Backend.participantClient.ledger_api_extensions.acs.filterJava(MemberTraffic.COMPANION)(
      sv1Backend.getDsoInfo().dsoParty,
      _.data.memberId == memberId.toProtoPrimitive,
    )
  }
}
