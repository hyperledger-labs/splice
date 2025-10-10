package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.RequireTypes.NonNegativeNumeric
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.PartyId
import io.grpc.{Status, StatusRuntimeException}
import org.lfdecentralizedtrust.splice.automation.Trigger
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAllValidatorConfigs,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.console.{
  ValidatorAppBackendReference,
  WalletAppClientReference,
}
import org.lfdecentralizedtrust.splice.environment.BaseLedgerConnection
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTestWithSharedEnvironment,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.util.SpliceUtil.ccToDollars
import org.lfdecentralizedtrust.splice.util.{SpliceUtil, TriggerTestUtil, WalletTestUtil}
import org.lfdecentralizedtrust.splice.wallet.automation.{
  AutoAcceptTransferOffersTrigger,
  WalletPreapprovalSweepTrigger,
}
import org.lfdecentralizedtrust.splice.wallet.config.{AutoAcceptTransfersConfig, WalletSweepConfig}
import org.slf4j.event.Level

import java.util.UUID
import scala.concurrent.duration.DurationInt

abstract class WalletSweepIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with WalletTestUtil
    with TriggerTestUtil {

  val maxBalanceUsd: BigDecimal = BigDecimal(10)
  val minBalanceUsd: BigDecimal = BigDecimal(2)
  val amuletPrice: BigDecimal = BigDecimal(1)

  val isSweepingToValidator: Boolean = true

  override def walletAmuletPrice = SpliceUtil.damlDecimal(amuletPrice.bigDecimal)

  def walletClient(implicit env: SpliceTestConsoleEnvironment): WalletAppClientReference
  def user(implicit env: SpliceTestConsoleEnvironment): String

  def onboard(
      walletClient: WalletAppClientReference,
      user: String,
      validatorBackend: ValidatorAppBackendReference,
  )(implicit env: SpliceTestConsoleEnvironment): PartyId

  "SV1's wallet" should {

    "not be swept if the balance is under the maximum allowed balance" in { implicit env =>
      onboard(walletClient, user, aliceValidatorBackend)
      sv1WalletClient.tap(maxBalanceUsd - 5)
      val sv1BalanceAtStart = sv1WalletClient.balance().unlockedQty.longValue
      val aliceBalanceAtStart = walletClient.balance().unlockedQty.longValue
      clue("sv1 should keep its amulets") {
        eventually() {
          sv1WalletClient.balance().unlockedQty.longValue shouldBe sv1BalanceAtStart
          walletClient.balance().unlockedQty.longValue shouldBe aliceBalanceAtStart
        }
      }
    }

    "be swept if the balance is above the maximum allowed balance" in { implicit env =>
      onboard(walletClient, user, aliceValidatorBackend)

      sv1WalletClient.tap(maxBalanceUsd + 2)
      val aliceBalanceAtStart = walletClient.balance().unlockedQty.longValue
      clue(
        s"sv1 should transfer its amulets to alice and still have a minimal balance of $minBalanceUsd"
      ) {
        eventually(40.seconds) {
          assertSweepCompleted()
          walletClient.balance().unlockedQty.longValue should be > aliceBalanceAtStart
        }
      }

    }

    "check that the sweep is completed even if the first offer is not immediately accepted." in {
      implicit env =>
        onboard(walletClient, user, aliceValidatorBackend)

        sweepAndTransfersAreIdle()
        val aliceBalanceAtStart = walletClient.balance().unlockedQty.longValue

        setTriggersWithin(
          triggersToPauseAtStart = autoAcceptTransferOffersTriggers,
          Seq.empty,
        ) {
          val firstTransferAmountUsd = loggerFactory.assertEventuallyLogsSeq(
            SuppressionRule.LevelAndAbove(Level.INFO) && SuppressionRule.LoggerNameContains(
              "WalletTransferOfferSweepTrigger"
            )
          )(
            {
              val (_, firstTransferAmountUsd) = actAndCheck(
                "SV1 receives funds and exceeds the maximum balance",
                sv1WalletClient.tap(maxBalanceUsd + 2),
              )(
                "Alice sees exactly one transfer offer",
                { _ =>
                  eventually() {
                    sv1Balance() shouldBe >(maxBalanceUsd)
                    val txOffers = walletClient.listTransferOffers()
                    txOffers should have size 1
                    walletClient.listAcceptedTransferOffers() shouldBe empty
                    ccToDollars(
                      txOffers.headOption.value.payload.amount.amount,
                      amuletPrice.bigDecimal,
                    )
                  }
                },
              )
              firstTransferAmountUsd
            },
            logs =>
              forAtLeast(1, logs) {
                _.infoMessage should include regex (
                  "but there are outstanding transfer offers with a value of 1\\d\\."
                )
              },
          )
          actAndCheck(
            "SV1 receives more funds and exceeds the maximum balance again",
            sv1WalletClient.tap(maxBalanceUsd + 2),
          )(
            "Alice sees a second transfer offer",
            { _ =>
              sv1Balance() - firstTransferAmountUsd shouldBe >(maxBalanceUsd)
              eventually() {
                walletClient.listTransferOffers() should have size 2
                walletClient.listAcceptedTransferOffers() shouldBe empty
              }
            },
          )
        }
        clue("After resuming auto-accept, SV1 is drained and Alice receives funds") {
          eventually(40.seconds) {
            walletClient.listTransferOffers() shouldBe empty
            walletClient.listAcceptedTransferOffers() shouldBe empty

            assertSweepCompleted()
            walletClient
              .balance()
              .unlockedQty
              .longValue should be > aliceBalanceAtStart
          }
        }
    }

  }

  "check that the sweep is completed even if an offer is rejected" in { implicit env =>
    onboard(walletClient, user, aliceValidatorBackend)

    sweepAndTransfersAreIdle()
    val aliceBalanceAtStart = walletClient.balance().unlockedQty.longValue

    setTriggersWithin(triggersToPauseAtStart = autoAcceptTransferOffersTriggers, Seq.empty) {
      actAndCheck(
        "SV1 receives funds and exceeds the maximum balance",
        sv1WalletClient.tap(maxBalanceUsd + 2),
      )(
        "Alice sees exactly one transfer offer",
        { _ =>
          eventually() {
            walletClient.listTransferOffers() should have size 1
            walletClient.listAcceptedTransferOffers() shouldBe empty
          }
        },
      )
      clue("Alice cancels the transfer offer") {
        walletClient.rejectTransferOffer(
          walletClient.listTransferOffers().head.contractId
        )
      }
    }
    clue("After resuming auto-accept, SV1 is drained and Alice receives funds") {
      eventually(40.seconds) {
        walletClient.listTransferOffers() shouldBe empty
        walletClient.listAcceptedTransferOffers() shouldBe empty
        assertSweepCompleted()
        walletClient
          .balance()
          .unlockedQty
          .longValue should be > aliceBalanceAtStart
      }
    }
  }

  "sweep through transfer preapproval should work" in { implicit env =>
    onboardWalletUser(bobValidatorWalletClient, bobValidatorBackend)
    val alicePartyId = onboard(walletClient, user, aliceValidatorBackend)

    inside(
      bobValidatorBackend
        .userWalletAutomation(bobValidatorWalletClient.config.ledgerApiUser)
        .futureValue
        .trigger[WalletPreapprovalSweepTrigger]
        .runOnce()
        .failed
        .futureValue
    ) { case ex: StatusRuntimeException =>
      ex.getStatus.getCode shouldBe Status.Code.INVALID_ARGUMENT
      ex.getStatus.getDescription() should include("No transfer preapproval for receiver")
    }
    aliceValidatorWalletClient.selfGrantFeaturedAppRight()
    actAndCheck(
      "Alice creates transfer preapproval", {
        aliceValidatorWalletClient.tap(
          50.0
        ) // validator needs to have some funds to create preapproval
        walletClient.tap(50.0)
        createTransferPreapprovalEnsuringItExists(walletClient, aliceValidatorBackend)
      },
    )(
      "Transfer preapproval is visible in scan",
      _ => sv1ScanBackend.lookupTransferPreapprovalByParty(alicePartyId) shouldBe a[Some[_]],
    )
    clue("Sweep no longer errors now that preapproval is created") {
      bobValidatorBackend
        .userWalletAutomation(bobValidatorWalletClient.config.ledgerApiUser)
        .futureValue
        .trigger[WalletPreapprovalSweepTrigger]
        .runOnce()
        .futureValue shouldBe false
    }
    actAndCheck(
      "Bob taps to have enough CC for sweep to kick in",
      bobValidatorWalletClient.tap(20),
    )(
      "bob's balance is updated",
      _ => {
        bobValidatorWalletClient.balance().unlockedQty should beAround(20.0)
      },
    )
    val previousBalance = walletClient.balance().unlockedQty.longValue
    actAndCheck(
      "Sweep kicks in",
      bobValidatorBackend
        .userWalletAutomation(bobValidatorWalletClient.config.ledgerApiUser)
        .futureValue
        .trigger[WalletPreapprovalSweepTrigger]
        .runOnce()
        .futureValue shouldBe true,
    )(
      "balances are updated",
      _ => {
        bobValidatorWalletClient.balance().unlockedQty should beAround(2)
        walletClient.balance().unlockedQty should beAround(previousBalance + 18)
      },
    )
    bobValidatorBackend
      .userWalletAutomation(bobValidatorWalletClient.config.ledgerApiUser)
      .futureValue
      .trigger[WalletPreapprovalSweepTrigger]
      .runOnce()
      .futureValue shouldBe false
  }

  private def sv1Balance()(implicit env: SpliceTestConsoleEnvironment) = BigDecimal(
    ccToDollars(
      sv1WalletClient.balance().unlockedQty.bigDecimal,
      amuletPrice.bigDecimal,
    )
  )

  private def sweepAndTransfersAreIdle()(implicit env: SpliceTestConsoleEnvironment) =
    clue("There are no outstanding transfer offers to accept or complete") {
      eventually() {
        sv1Balance() shouldBe <(maxBalanceUsd)
        walletClient.listTransferOffers() shouldBe empty
        walletClient.listAcceptedTransferOffers() shouldBe empty
      }
    }

  // triggers relevant to outstanding sweep transfer offers
  private def autoAcceptTransferOffersTriggers(implicit
      environment: SpliceTestConsoleEnvironment
  ): Seq[Trigger] = {
    val username = walletClient.config.ledgerApiUser
    Seq(
      aliceValidatorBackend
        .userWalletAutomation(username)
        .futureValue
        .trigger[AutoAcceptTransferOffersTrigger]
    )
  }

  private def assertSweepCompleted()(implicit env: SpliceTestConsoleEnvironment) = {
    // The merging of the two tapped amulets loses 1 x base transfer fee,
    // but may occur after the amount of the transfer offer is computed.
    // When that happens, we end up having slightly less than the minimum balance.
    sv1Balance() should beWithin(minBalanceUsd - smallAmount, minBalanceUsd + smallAmount)
  }

}

class WalletSweepToValidatorOperatorIntegrationTest extends WalletSweepIntegrationTest {

  override def walletClient(implicit env: SpliceTestConsoleEnvironment): WalletAppClientReference =
    aliceValidatorWalletClient
  override def user(implicit env: SpliceTestConsoleEnvironment): String =
    aliceValidatorBackend.config.validatorPartyHint.value

  override def onboard(
      walletClient: WalletAppClientReference,
      user: String,
      validatorBackend: ValidatorAppBackendReference,
  )(implicit env: SpliceTestConsoleEnvironment): PartyId = {
    onboardWalletUser(walletClient, validatorBackend)
  }

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) => {
        val aliceParticipant =
          ConfigTransforms
            .getParticipantIds(config.parameters.clock)("alice_validator_user")
        val alicePartyHint =
          config.validatorApps(InstanceName.tryCreate("aliceValidator")).validatorPartyHint.value
        val alicePartyId = PartyId
          .tryFromProtoPrimitive(
            s"${alicePartyHint}::${aliceParticipant.split("::").last}"
          )
        val bobParticipant =
          ConfigTransforms
            .getParticipantIds(config.parameters.clock)("bob_validator_user")
        val bobPartyHint =
          config.validatorApps(InstanceName.tryCreate("bobValidator")).validatorPartyHint.value
        val bobPartyId = PartyId
          .tryFromProtoPrimitive(
            s"${bobPartyHint}::${bobParticipant.split("::").last}"
          )
        val sv1Participant =
          ConfigTransforms
            .getParticipantIds(config.parameters.clock)("sv1")
        val sv1LedgerApiUser =
          config.svApps(InstanceName.tryCreate("sv1")).svPartyHint.value
        val sv1PartyId = PartyId
          .tryFromProtoPrimitive(
            s"${BaseLedgerConnection
                .sanitizeUserIdToPartyString(sv1LedgerApiUser)}::${sv1Participant.split("::").last}"
          )
        updateAllValidatorConfigs { case (name, c) =>
          if (name == "sv1Validator") {
            c.copy(
              walletSweep = Map(
                sv1PartyId.toProtoPrimitive -> WalletSweepConfig(
                  NonNegativeNumeric.tryCreate(maxBalanceUsd),
                  NonNegativeNumeric.tryCreate(minBalanceUsd),
                  alicePartyId,
                )
              )
            )
          } else if (name == "aliceValidator") {
            c.copy(
              autoAcceptTransfers = Map(
                alicePartyId.toProtoPrimitive ->
                  AutoAcceptTransfersConfig(fromParties = Seq(sv1PartyId))
              )
            )
          } else if (name == "bobValidator") {
            c.copy(
              walletSweep = Map(
                bobPartyId.toProtoPrimitive -> WalletSweepConfig(
                  NonNegativeNumeric.tryCreate(maxBalanceUsd),
                  NonNegativeNumeric.tryCreate(minBalanceUsd),
                  alicePartyId,
                  useTransferPreapproval = true,
                )
              )
            )
          } else {
            c
          }
        }(config)
      })
      .withAmuletPrice(amuletPrice)
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Validator)(
          // Disable by default so we can explictly control when it runs
          _.withPausedTrigger[WalletPreapprovalSweepTrigger]
        )(config)
      )
      .withSequencerConnectionsFromScanDisabled()

}

class WalletSweepToEndUserIntegrationTest extends WalletSweepIntegrationTest {

  override val isSweepingToValidator = false

  override def walletClient(implicit env: SpliceTestConsoleEnvironment): WalletAppClientReference =
    aliceWalletClient

  val username = "alice-end-user-" + UUID.randomUUID().toString.take(5)
  override def user(implicit env: SpliceTestConsoleEnvironment): String = username

  override def onboard(
      walletClient: WalletAppClientReference,
      user: String,
      validatorBackend: ValidatorAppBackendReference,
  )(implicit env: SpliceTestConsoleEnvironment): PartyId = {
    val aliceParticipant =
      ConfigTransforms
        .getParticipantIds(env.environment.config.parameters.clock)("alice_validator_user")
    val aliceEndUserPartyId = PartyId
      .tryFromProtoPrimitive(
        s"$user::${aliceParticipant.split("::").last}"
      )
    if (
      !aliceValidatorBackend.participantClientWithAdminToken.ledger_api.parties
        .list()
        .exists(e => e.party == aliceEndUserPartyId)
    ) {
      aliceValidatorBackend.participantClientWithAdminToken.ledger_api.parties.allocate(user)
    }
    val newPartyId =
      aliceValidatorBackend.onboardUser(
        walletClient.config.ledgerApiUser,
        Some(aliceEndUserPartyId),
      )
    waitForWalletUser(walletClient)
    newPartyId
  }

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) => {
        val aliceParticipant =
          ConfigTransforms
            .getParticipantIds(config.parameters.clock)("alice_validator_user")
        val aliceUser = username
        val alicePartyId = PartyId
          .tryFromProtoPrimitive(
            s"$aliceUser::${aliceParticipant.split("::").last}"
          )
        val bobParticipant =
          ConfigTransforms
            .getParticipantIds(config.parameters.clock)("bob_validator_user")
        val bobPartyHint =
          config.validatorApps(InstanceName.tryCreate("bobValidator")).validatorPartyHint.value
        val bobPartyId = PartyId
          .tryFromProtoPrimitive(
            s"$bobPartyHint::${bobParticipant.split("::").last}"
          )
        val sv1Participant =
          ConfigTransforms
            .getParticipantIds(config.parameters.clock)("sv1")
        val sv1LedgerApiUser =
          config.svApps(InstanceName.tryCreate("sv1")).svPartyHint.value
        val sv1PartyId = PartyId
          .tryFromProtoPrimitive(
            s"${BaseLedgerConnection
                .sanitizeUserIdToPartyString(sv1LedgerApiUser)}::${sv1Participant.split("::").last}"
          )
        updateAllValidatorConfigs { case (name, c) =>
          if (name == "sv1Validator") {
            c.copy(
              walletSweep = Map(
                sv1PartyId.toProtoPrimitive -> WalletSweepConfig(
                  NonNegativeNumeric.tryCreate(maxBalanceUsd),
                  NonNegativeNumeric.tryCreate(minBalanceUsd),
                  alicePartyId,
                )
              )
            )
          } else if (name == "aliceValidator") {
            c.copy(
              autoAcceptTransfers = Map(
                alicePartyId.toProtoPrimitive ->
                  AutoAcceptTransfersConfig(fromParties = Seq(sv1PartyId))
              )
            )
          } else if (name == "bobValidator") {
            c.copy(
              walletSweep = Map(
                bobPartyId.toProtoPrimitive -> WalletSweepConfig(
                  NonNegativeNumeric.tryCreate(maxBalanceUsd),
                  NonNegativeNumeric.tryCreate(minBalanceUsd),
                  alicePartyId,
                  useTransferPreapproval = true,
                )
              )
            )
          } else {
            c
          }
        }(config)
      })
      .withAmuletPrice(amuletPrice)
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Validator)(
          // Disable by default so we can explictly control when it runs
          _.withPausedTrigger[WalletPreapprovalSweepTrigger]
          // .withPausedTrigger[WalletTransferOfferSweepTrigger]
        )(config)
      )
      .withSequencerConnectionsFromScanDisabled()

}
