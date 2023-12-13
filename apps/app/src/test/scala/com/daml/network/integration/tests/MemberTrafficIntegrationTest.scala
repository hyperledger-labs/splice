package com.daml.network.integration.tests

import com.daml.network.codegen.java.cc.globaldomain.MemberTraffic
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTestWithSharedEnvironment,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.DomainFeesTestUtil
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.Member
import org.slf4j.event.Level

class MemberTrafficIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with DomainFeesTestUtil {

  override def environmentDefinition: CNNodeEnvironmentDefinition = {
    CNNodeEnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      // NOTE: automatic top-ups should be explicitly disabled for this test as currently written
      .withTrafficTopupsDisabled
  }

  "SV automation" should {

    "merge duplicate member traffic contracts" in { implicit env =>
      val now = env.environment.clock.now
      val domainFeesConfig = sv1ScanBackend.getCoinConfigAsOf(now).globalDomain.fees
      val trafficAmount = Math.max(domainFeesConfig.minTopupAmount.toLong, 1_000_000L)
      val participantId = aliceValidatorBackend.participantClient.id
      val trafficContractsMergeThreshold =
        sv1Backend.getSvcInfo().svcRules.payload.config.numMemberTrafficContractsThreshold

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
      val actualStateAsPerSequencer = getTrafficState(aliceValidatorBackend, activeDomainId)
      val statusAsPerScan = sv1ScanBackend.getMemberTrafficStatus(
        activeDomainId,
        aliceValidatorBackend.participantClient.id,
      )
      statusAsPerScan.actual.totalConsumed shouldBe actualStateAsPerSequencer.extraTrafficConsumed.value
      statusAsPerScan.actual.totalLimit shouldBe actualStateAsPerSequencer.extraTrafficLimit.value.value
    // TODO(#8882) Also test the target state
    }
  }

  private def listMemberTrafficContracts(
      memberId: Member
  )(implicit env: CNNodeTestConsoleEnvironment) = {
    sv1Backend.participantClient.ledger_api_extensions.acs.filterJava(MemberTraffic.COMPANION)(
      sv1Backend.getSvcInfo().svcParty,
      _.data.memberId == memberId.toProtoPrimitive,
    )
  }
}
