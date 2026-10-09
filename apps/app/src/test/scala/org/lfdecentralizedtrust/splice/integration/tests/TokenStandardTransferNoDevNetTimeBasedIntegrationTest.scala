package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.HasExecutionContext
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.transferinstructionv1.TransferInstruction
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.http.v0.definitions.TransferInstructionResultOutput.members
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.{
  SvTestUtil,
  SynchronizerFeesTestUtil,
  TimeTestUtil,
  WalletTestUtil,
}
import org.lfdecentralizedtrust.splice.validator.automation.{
  ReceiveFaucetCouponTrigger,
  TopupMemberTrafficTrigger,
}

import java.time.Duration
import java.util.UUID

class TokenStandardTransferNoDevNetTimeBasedIntegrationTest
    extends IntegrationTest
    with HasExecutionContext
    with WalletTestUtil
    with SynchronizerFeesTestUtil
    with TimeTestUtil
    with SvTestUtil {

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

    "accept a token standard transfer with high priority when it cannot afford a top-up" in {
      implicit env =>
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

        clue("Precondition: aliceValidator's traffic is at or below the reserve") {
          val reserved = aliceValidatorBackend.config.domains.global.reservedTraffic.value
          val remainder =
            getTrafficState(aliceValidatorBackend, activeSynchronizerId).extraTrafficRemainder
          remainder should be <= reserved
        }

        val balanceBefore = aliceValidatorWalletClient.balance().unlockedQty

        val (response, _) = actAndCheck(
          "sv1 creates a token standard transfer to aliceValidator",
          sv1WalletClient.createTokenStandardTransfer(
            aliceValidatorBackend.getValidatorPartyId(),
            BigDecimal(100),
            "high priority test - token standard transfer",
            now.plus(Duration.ofMinutes(10)),
            UUID.randomUUID().toString,
          ),
        )(
          "the transfer shows up in aliceValidator's wallet",
          _ => aliceValidatorWalletClient.listTokenStandardTransfers() should have size 1,
        )

        val transferCid = response.output match {
          case members.TransferInstructionPending(value) =>
            new TransferInstruction.ContractId(value.transferInstructionCid)
          case _ => fail("The transfer was expected to be pending.")
        }

        actAndCheck(
          "aliceValidator accepts the token standard transfer (priority High, so the reserve does not block it)",
          aliceValidatorWalletClient.acceptTokenStandardTransfer(transferCid),
        )(
          "the transfer completes and the balance goes up",
          result => {
            inside(result.output) { case members.TransferInstructionCompleted(_) => () }
            Seq(sv1WalletClient, aliceValidatorWalletClient).foreach(
              _.listTokenStandardTransfers() should be(empty)
            )
            aliceValidatorWalletClient.balance().unlockedQty should be > balanceBefore
          },
        )
    }
  }
}
