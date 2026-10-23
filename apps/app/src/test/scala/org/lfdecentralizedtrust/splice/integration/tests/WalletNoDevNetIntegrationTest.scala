package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.admin.api.client.data.NodeStatus
import com.digitalasset.canton.config.RequireTypes.{NonNegativeLong, PositiveInt}
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.NonNegativeFiniteDuration
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
import org.lfdecentralizedtrust.splice.console.{
  ValidatorAppBackendReference,
  WalletAppClientReference,
}
import org.lfdecentralizedtrust.splice.environment.BaseLedgerConnection
import org.lfdecentralizedtrust.splice.http.v0.definitions as d0
import org.lfdecentralizedtrust.splice.http.v0.definitions.TransferInstructionResultOutput.members
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.{
  AdvanceOpenMiningRoundTrigger,
  ExpireIssuingMiningRoundTrigger,
}
import org.lfdecentralizedtrust.splice.util.{
  SvTestUtil,
  SynchronizerFeesTestUtil,
  TimeTestUtil,
  TriggerTestUtil,
  WalletTestUtil,
}
import org.lfdecentralizedtrust.splice.validator.automation.{
  ReceiveFaucetCouponTrigger,
  TopupMemberTrafficTrigger,
}
import org.lfdecentralizedtrust.splice.validator.config.AppInstance
import org.lfdecentralizedtrust.splice.wallet.admin.api.client.commands.HttpWalletAppClient.CreateTransferPreapprovalResponse
import org.lfdecentralizedtrust.splice.wallet.config.AutoAcceptTransfersConfig
import org.lfdecentralizedtrust.splice.wallet.store.TxLogEntry

import java.time.Duration
import scala.concurrent.duration.DurationInt
import java.util.UUID

class WalletNoDevNetIntegrationTest
    extends IntegrationTest
    with HasExecutionContext
    with WalletTestUtil
    with SynchronizerFeesTestUtil
    with TimeTestUtil
    with SvTestUtil
    with TriggerTestUtil {

  override def environmentDefinition: EnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName) // was simpleTopology1SvWithSimTime
      .addConfigTransform((_, config) => ConfigTransforms.noDevNet(config))
      // very short rounds; we advance them by hand
      .addConfigTransform((_, config) =>
        ConfigTransforms.updateInitialTickDuration(NonNegativeFiniteDuration.ofMillis(500))(config)
      )
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
          _.withPausedTrigger[AdvanceOpenMiningRoundTrigger]
            .withPausedTrigger[ExpireIssuingMiningRoundTrigger]
        )(config)
      )
      // Faucet trigger: pause on ALL validators
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Validator)(
          _.withPausedTrigger[ReceiveFaucetCouponTrigger]
        )(config)
      )
      // Top-up trigger: pause only on validators that actually have it (not the SV's validator)
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllValidatorConfigs { case (name, c) =>
          if (name.startsWith("sv")) c
          else c.copy(automation = c.automation.withPausedTrigger[TopupMemberTrafficTrigger])
        }(config)
      )
      // Read the traffic balance fresh on every command.
      .withTrafficBalanceCacheDisabled
      .addConfigTransforms((_, config) => {
        // The user behind aliceWalletClient. The framework appends "_tc<testCaseId>" to wallet client
        // users per test case, so this control user only matches in the FIRST (and only) test case ("_tc0").
        val aliceWalletUser =
          perTestCaseNameWithoutUnverified(
            config.walletAppClients(InstanceName.tryCreate("aliceWallet")).ledgerApiUser
          )

        // Party ids must be known before the apps start, so we build them by hand:
        // "<party name from the config>::<hex id of the participant it will live on>".
        // (Same approach as WalletSweepToValidatorOperatorIntegrationTest.)
        val bobParticipant =
          ConfigTransforms.getParticipantIds(config.parameters.clock)("bob_validator_user")
        val bobPartyHint =
          config.validatorApps(InstanceName.tryCreate("bobValidator")).validatorPartyHint.value
        val bobPartyId =
          PartyId.tryFromProtoPrimitive(s"$bobPartyHint::${bobParticipant.split("::").last}")

        val sv1Participant = ConfigTransforms.getParticipantIds(config.parameters.clock)("sv1")
        val sv1PartyHint = config.svApps(InstanceName.tryCreate("sv1")).svPartyHint.value
        val sv1PartyId = PartyId.tryFromProtoPrimitive(
          s"${BaseLedgerConnection
              .sanitizeUserIdToPartyString(sv1PartyHint)}::${sv1Participant.split("::").last}"
        )

        ConfigTransforms.updateAllValidatorConfigs { case (name, c) =>
          if (name == "aliceValidator")
            // Onboards a normal user (not the validator operator) on aliceValidator at start-up
            // (with High priority), for the Low-priority control at the end of the test.
            c.copy(appInstances =
              c.appInstances +
                ("lowPriorityControl" -> AppInstance(
                  serviceUser = aliceWalletUser,
                  walletUser = None, // None: the service user itself is onboarded to the wallet
                  dars = Seq.empty,
                ))
            )
          else if (name == "bobValidator")
            // bobValidator auto-accepts transfer offers from sv1. Only bob has this trigger,
            // so aliceValidator's offers are never auto-accepted.
            c.copy(autoAcceptTransfers =
              Map(
                bobPartyId.toProtoPrimitive ->
                  AutoAcceptTransfersConfig(fromParties = Seq(sv1PartyId))
              )
            )
          else c
        }(config)
      })
      .copy(
        preAppStart = implicit env => {
          // Reset traffic state for alice and bob such that extraTrafficRemainder = 0 to make sure we hit the cases where command priority matters.
          sv1Backend.sequencerClient.health.status match {
            case _: NodeStatus.Success[?] =>
              Seq(aliceValidatorBackend, bobValidatorBackend).foreach(
                setExtraTrafficRemainder(_, 0L)
              )
            case _ =>
          }
        }
      )
  }

  private def setExtraTrafficRemainder(
      validator: ValidatorAppBackendReference,
      remainder: Long,
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    val member = validator.participantClient.id.member
    sv1Backend.sequencerClient.traffic_control
      .traffic_state_of_members(Seq(member))
      .trafficStates
      .get(member)
      .foreach(ts =>
        sv1Backend.sequencerClient.traffic_control.set_traffic_balance(
          member,
          serial = ts.serial.fold(PositiveInt.one)(_ + PositiveInt.one),
          newBalance = NonNegativeLong.tryCreate(ts.extraTrafficConsumed.value + remainder),
        )
      )
  }

  "A validator wallet" should {

    "accept transfers with high priority when it cannot afford a top-up" in { implicit env =>
      // There is no tap outside DevNet, so sv1 earns CC through SV rewards.
      actAndCheck(
        "Advance enough rounds for SV1 to claim rewards", {
          (0 to 3).foreach { _ =>
            eventually() {
              ensureSvRewardCouponReceivedForCurrentRound(sv1ScanBackend, sv1WalletClient)
            }
            advanceRoundsByOneTickViaAutomation()
          }
        },
      )(
        "Wait for SV rewards to be collected",
        _ => sv1WalletClient.balance().unlockedQty should be > BigDecimal(0),
      )

      val now = env.environment.clock.now
      val expiresAt = now.plus(Duration.ofMinutes(10))
      val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()
      val bobValidatorParty = bobValidatorBackend.getValidatorPartyId()
      // Keep each transfer small: every accepted transfer raises the receiver's balance,
      // and it must stay below the cost of one top-up for the next accept to count.
      val amount = BigDecimal(10)
      val baseTrafficForHighPrioritySubmission = 15_000L

      // Fails the test if it does not hold, so that an accept can never pass with Low priority.
      def checkCannotAffordTopupAndTrafficBelowReserve(
          validator: ValidatorAppBackendReference,
          walletClient: WalletAppClientReference,
      ): Unit = {
        clue(
          s"${validator.name} has enough base traffic for a high priority submission"
        ) {
          eventually(timeUntilSuccess = 2.minutes, maxPollInterval = 1.second) {
            getTrafficState(
              validator,
              activeSynchronizerId,
            ).baseTrafficRemainder.value should be >= baseTrafficForHighPrioritySubmission
          }
        }
        clue(
          s"Precondition: ${validator.name} cannot afford one top-up and its traffic is at or below the reserve"
        ) {
          val (_, topupCostCc) =
            computeSynchronizerFees(getTopupParameters(validator, now).topupAmount)
          walletClient.balance().unlockedQty should be < topupCostCc
          val reserved = validator.config.domains.global.reservedTraffic.value
          val remainder =
            getTrafficState(validator, activeSynchronizerId).extraTrafficRemainder
          remainder should be <= reserved
        }
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

      checkCannotAffordTopupAndTrafficBelowReserve(
        aliceValidatorBackend,
        aliceValidatorWalletClient,
      )
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

      checkCannotAffordTopupAndTrafficBelowReserve(
        aliceValidatorBackend,
        aliceValidatorWalletClient,
      )
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

      // 3. acceptTokenStandardTransferV2
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

      checkCannotAffordTopupAndTrafficBelowReserve(
        aliceValidatorBackend,
        aliceValidatorWalletClient,
      )
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
      checkCannotAffordTopupAndTrafficBelowReserve(
        aliceValidatorBackend,
        aliceValidatorWalletClient,
      )
      aliceValidatorBackend.lookupTransferPreapprovalByParty(aliceValidatorParty) shouldBe None

      actAndCheck(
        "aliceValidator creates a TransferPreapproval for itself (proposal and acceptance are high priority)",
        aliceValidatorWalletClient.createTransferPreapproval(),
      )(
        "the TransferPreapproval exists",
        response => {
          inside(response) { case CreateTransferPreapprovalResponse.Created(_) => () }
          aliceValidatorBackend
            .lookupTransferPreapprovalByParty(aliceValidatorParty) should be(defined)
        },
      )

      // 5. AutoAcceptTransferOffersTrigger: bobValidator accepts sv1's offer by itself
      checkCannotAffordTopupAndTrafficBelowReserve(bobValidatorBackend, bobValidatorWalletClient)
      val autoTrackingId = UUID.randomUUID.toString
      val balanceBeforeAuto = bobValidatorWalletClient.balance().unlockedQty
      actAndCheck(
        "sv1 offers some CC to bobValidator, which auto-accepts it",
        sv1WalletClient.createTransferOffer(
          bobValidatorParty,
          amount,
          "auto accept test",
          expiresAt,
          autoTrackingId,
        ),
      )(
        "the trigger accepts the offer (priority High) and the balance goes up",
        _ => {
          inside(sv1WalletClient.getTransferOfferStatus(autoTrackingId)) {
            case d0.GetTransferOfferStatusResponse.members.TransferOfferCompletedResponse(r) =>
              r.status shouldBe TxLogEntry.Http.TransferOfferStatus.Completed
          }
          bobValidatorWalletClient.balance().unlockedQty should be > balanceBeforeAuto
        },
      )

      // Control: aliceWallet's user is a normal user, not the validator operator, so its priority
      // is Low. The traffic condition is the same as above, so its accept must be refused.
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

      checkCannotAffordTopupAndTrafficBelowReserve(
        aliceValidatorBackend,
        aliceValidatorWalletClient,
      )
      clue("alice, a normal user, cannot accept it: the handler uses Low priority") {
        assertThrowsAndLogsCommandFailures(
          aliceWalletClient.acceptTokenStandardTransfer(controlCid),
          entry => entry.message should include("Traffic balance below reserved traffic amount"),
        )
      }
      clue("Nothing changed: the transfer is still pending") {
        aliceWalletClient.listTokenStandardTransfers() should have size 1
      }

      clue("Give alice and bob one top-up of extra traffic back for the suites that follow") {
        Seq(aliceValidatorBackend, bobValidatorBackend).foreach { validator =>
          setExtraTrafficRemainder(validator, getTopupParameters(validator, now).topupAmount)
        }
      }
    }
  }
}
