package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.http.v0.definitions as d0
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.{
  SvTestUtil,
  SynchronizerFeesTestUtil,
  WalletTestUtil,
  TimeTestUtil,
}
import org.lfdecentralizedtrust.splice.validator.automation.{
  ReceiveFaucetCouponTrigger,
  TopupMemberTrafficTrigger,
}
import org.lfdecentralizedtrust.splice.wallet.store.TxLogEntry
import com.digitalasset.canton.HasExecutionContext

import java.time.Duration
import java.util.UUID

class WalletNoDevNetTimeBasedIntegrationTest
    extends IntegrationTest
    with HasExecutionContext
    with WalletTestUtil
    with SynchronizerFeesTestUtil
    with SvTestUtil
    with TimeTestUtil {

  override def environmentDefinition: EnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .addConfigTransform((_, config) => ConfigTransforms.noDevNet(config))
      .withTrafficTopupsEnabled
      .addConfigTransform((_, config) =>
        updateAutomationConfig(ConfigurableApp.Validator)(
          _.withPausedTrigger[TopupMemberTrafficTrigger]
            .withPausedTrigger[ReceiveFaucetCouponTrigger]
        )(config)
      )
      .withTrafficBalanceCacheDisabled
  }

  "A validator wallet" should {

    "accept a transfer offer with high priority when it cannot afford a top-up" in { implicit env =>
      actAndCheck(
        "Advance enough rounds for SV1 to claim rewards", {
          (0 to 3).foreach { _ =>
            advanceTimeForRewardAutomationToRunForCurrentRound
            eventually() {
              ensureSvRewardCouponReceivedForCurrentRound(sv1ScanBackend, sv1WalletClient)
            }
            advanceRoundsToNextRoundOpening
          }
        },
      )(
        "Wait for SV rewards to be collected",
        _ => sv1WalletClient.balance().unlockedQty should be > BigDecimal(0),
      )

      val now = env.environment.clock.now

      clue("Precondition: aliceValidator cannot afford one top-up") {
        val topupAmount = getTopupParameters(aliceValidatorBackend, now).topupAmount
        val (_, topupCostCc) = computeSynchronizerFees(topupAmount)
        aliceValidatorWalletClient.balance().unlockedQty should be < topupCostCc
      }

      clue(
        "Precondition: aliceValidator's traffic is at or below the reserve (a Low priority command would be refused)"
      ) {
        val reserved = aliceValidatorBackend.config.domains.global.reservedTraffic.value
        val remainder =
          getTrafficState(aliceValidatorBackend, activeSynchronizerId).extraTrafficRemainder
        remainder should be <= reserved
      }

      val trackingId = UUID.randomUUID.toString
      val (offerCid, _) = actAndCheck(
        "sv1 offers some CC to aliceValidator",
        sv1WalletClient.createTransferOffer(
          aliceValidatorBackend.getValidatorPartyId(),
          BigDecimal(100),
          "high priority test - transfer offer",
          now.plus(Duration.ofMinutes(10)),
          trackingId,
        ),
      )(
        "the offer shows up in aliceValidator's wallet",
        cid =>
          forExactly(1, aliceValidatorWalletClient.listTransferOffers())(
            _.contractId shouldBe cid
          ),
      )

      actAndCheck(
        "aliceValidator accepts the offer (priority High, so the reserve does not block it)",
        aliceValidatorWalletClient.acceptTransferOffer(offerCid),
      )(
        "the offer completes and the balance goes up",
        _ => {
          inside(sv1WalletClient.getTransferOfferStatus(trackingId)) {
            case d0.GetTransferOfferStatusResponse.members.TransferOfferCompletedResponse(r) =>
              r.status shouldBe TxLogEntry.Http.TransferOfferStatus.Completed
          }
          aliceValidatorWalletClient.balance().unlockedQty should be > BigDecimal(0)
        },
      )
    }
  }
}
