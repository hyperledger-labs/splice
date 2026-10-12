package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.{
  transferinstructionv1,
  transferinstructionv2,
}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.http.v0.definitions as d0
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
import org.lfdecentralizedtrust.splice.validator.config.AppInstance
import org.lfdecentralizedtrust.splice.wallet.admin.api.client.commands.HttpWalletAppClient.CreateTransferPreapprovalResponse
import org.lfdecentralizedtrust.splice.wallet.store.TxLogEntry

import java.time.Duration
import java.util.UUID

class WalletNoDevNetTimeBasedIntegrationTest
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
      .addConfigTransforms((_, config) => {
        val aliceWalletUser =
          perTestCaseNameWithoutUnverified(
            config.walletAppClients(InstanceName.tryCreate("aliceWallet")).ledgerApiUser
          )
        ConfigTransforms.updateAllValidatorConfigs { case (name, c) =>
          if (name == "aliceValidator")
            c.copy(appInstances =
              c.appInstances +
                ("lowPriorityControl" -> AppInstance(
                  serviceUser = aliceWalletUser,
                  walletUser = None,
                  dars = Seq.empty,
                ))
            )
          else c
        }(config)
      })
  }

  "A validator wallet" should {
    "accept transfers with high priority when it cannot afford a top-up" in { implicit env =>
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
      val expiresAt = now.plus(Duration.ofMinutes(10))
      val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()
      val amount = BigDecimal(10)

      val (_, topupCostCc) =
        computeSynchronizerFees(getTopupParameters(aliceValidatorBackend, now).topupAmount)

      // Fails the test if it does not hold, so that an accept can never pass with Low priority.
      def checkCannotAffordTopupAndTrafficBelowReserve(): Unit =
        clue(
          "Precondition: aliceValidator cannot afford one top-up and its traffic is at or below the reserve"
        ) {
          aliceValidatorWalletClient.balance().unlockedQty should be < topupCostCc
          val reserved = aliceValidatorBackend.config.domains.global.reservedTraffic.value
          val remainder =
            getTrafficState(aliceValidatorBackend, activeSynchronizerId).extraTrafficRemainder
          remainder should be <= reserved
        }

      // 1. acceptTransferOffer
      val offerTrackingId = UUID.randomUUID.toString
      val (offerCid, _) = actAndCheck(
        "sv1 offers some CC to aliceValidator",
        sv1WalletClient.createTransferOffer(
          aliceValidatorParty,
          amount,
          "high priority test",
          expiresAt,
          offerTrackingId,
        ),
      )(
        "the offer shows up in aliceValidator's wallet",
        cid =>
          forExactly(1, aliceValidatorWalletClient.listTransferOffers())(
            _.contractId shouldBe cid
          ),
      )

      checkCannotAffordTopupAndTrafficBelowReserve()
      val balanceBeforeOffer = aliceValidatorWalletClient.balance().unlockedQty
      actAndCheck(
        "aliceValidator accepts the transfer offer (priority High)",
        aliceValidatorWalletClient.acceptTransferOffer(offerCid),
      )(
        "the offer completes and the balance goes up",
        _ => {
          inside(sv1WalletClient.getTransferOfferStatus(offerTrackingId)) {
            case d0.GetTransferOfferStatusResponse.members.TransferOfferCompletedResponse(r) =>
              r.status shouldBe TxLogEntry.Http.TransferOfferStatus.Completed
          }
          aliceValidatorWalletClient.balance().unlockedQty should be > balanceBeforeOffer
        },
      )

      // 2. acceptTokenStandardTransfer (V1)
      val (v1Response, _) = actAndCheck(
        "sv1 creates a token standard V1 transfer to aliceValidator",
        sv1WalletClient.createTokenStandardTransfer(
          aliceValidatorParty,
          amount,
          "high priority test (V1)",
          expiresAt,
          UUID.randomUUID.toString,
        ),
      )(
        "the V1 transfer shows up in aliceValidator's wallet",
        _ => aliceValidatorWalletClient.listTokenStandardTransfers() should have size 1,
      )
      val v1Cid = v1Response.output match {
        case members.TransferInstructionPending(value) =>
          new transferinstructionv1.TransferInstruction.ContractId(value.transferInstructionCid)
        case _ => fail("The V1 transfer was expected to be pending.")
      }

      checkCannotAffordTopupAndTrafficBelowReserve()
      val balanceBeforeV1 = aliceValidatorWalletClient.balance().unlockedQty
      actAndCheck(
        "aliceValidator accepts the V1 transfer (priority High)",
        aliceValidatorWalletClient.acceptTokenStandardTransfer(v1Cid),
      )(
        "the V1 transfer completes and the balance goes up",
        result => {
          inside(result.output) { case members.TransferInstructionCompleted(_) => () }
          aliceValidatorWalletClient.listTokenStandardTransfers() should be(empty)
          aliceValidatorWalletClient.balance().unlockedQty should be > balanceBeforeV1
        },
      )

      // 3. acceptTokenStandardTransferV2 (needs the same priority logic in the V2 handler)
      val (v2Response, _) = actAndCheck(
        "sv1 creates a token standard V2 transfer to aliceValidator",
        sv1WalletClient.createTokenStandardTransferV2(
          aliceValidatorParty,
          amount,
          "high priority test (V2)",
          expiresAt,
          UUID.randomUUID.toString,
        ),
      )(
        "the V2 transfer shows up in aliceValidator's wallet",
        _ => aliceValidatorWalletClient.listTokenStandardTransfers() should have size 1,
      )
      val v2Cid = v2Response.output match {
        case members.TransferInstructionPending(value) =>
          new transferinstructionv2.TransferInstruction.ContractId(value.transferInstructionCid)
        case _ => fail("The V2 transfer was expected to be pending.")
      }

      checkCannotAffordTopupAndTrafficBelowReserve()
      val balanceBeforeV2 = aliceValidatorWalletClient.balance().unlockedQty
      actAndCheck(
        "aliceValidator accepts the V2 transfer (priority High)",
        aliceValidatorWalletClient.acceptTokenStandardTransferV2(v2Cid),
      )(
        "the V2 transfer completes and the balance goes up",
        result => {
          inside(result.output) { case members.TransferInstructionCompleted(_) => () }
          aliceValidatorWalletClient.listTokenStandardTransfers() should be(empty)
          aliceValidatorWalletClient.balance().unlockedQty should be > balanceBeforeV2
        },
      )

      // 4. createTransferPreapproval: the wallet's proposal and the validator trigger's accept
      checkCannotAffordTopupAndTrafficBelowReserve()
      aliceValidatorBackend.lookupTransferPreapprovalByParty(aliceValidatorParty) shouldBe None

      actAndCheck(
        "aliceValidator creates a TransferPreapproval for itself (proposal and acceptance are high priority)",
        aliceValidatorWalletClient.createTransferPreapproval(),
      )(
        "the TransferPreapproval exists",
        response => {
          inside(response) { case CreateTransferPreapprovalResponse.Created(_) => () }
          aliceValidatorBackend.lookupTransferPreapprovalByParty(aliceValidatorParty) should be(
            defined
          )
        },
      )

      // Control: aliceWallet's user is a normal user, not the validator operator, so its priority is Low.
      waitForWalletUser(aliceWalletClient)
      val aliceUserParty = PartyId.tryFromProtoPrimitive(aliceWalletClient.userStatus().party)

      val (controlResponse, _) = actAndCheck(
        "sv1 creates a token standard V1 transfer to alice, a normal user hosted on aliceValidator",
        sv1WalletClient.createTokenStandardTransfer(
          aliceUserParty,
          amount,
          "low priority control",
          expiresAt,
          UUID.randomUUID.toString,
        ),
      )(
        "the transfer shows up in alice's wallet",
        _ => aliceWalletClient.listTokenStandardTransfers() should have size 1,
      )
      val controlCid = controlResponse.output match {
        case members.TransferInstructionPending(value) =>
          new transferinstructionv1.TransferInstruction.ContractId(value.transferInstructionCid)
        case _ => fail("The transfer was expected to be pending.")
      }

      checkCannotAffordTopupAndTrafficBelowReserve()
      clue("alice, a normal user, cannot accept it: the handler uses Low priority") {
        assertThrowsAndLogsCommandFailures(
          aliceWalletClient.acceptTokenStandardTransfer(controlCid),
          entry => entry.message should include("Traffic balance below reserved traffic amount"),
        )
      }
      clue("Nothing changed: the transfer is still pending") {
        aliceWalletClient.listTokenStandardTransfers() should have size 1
      }
    }
  }
}
