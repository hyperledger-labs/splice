package com.daml.network.integration.tests

import com.daml.network.automation.Trigger
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.config.CNNodeConfigTransforms.updateAllValidatorConfigs
import com.daml.network.environment.{BaseLedgerConnection, CNNodeEnvironmentImpl}
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTestWithSharedEnvironment,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.CNNodeUtil.ccToDollars
import com.daml.network.util.{TriggerTestUtil, WalletTestUtil}
import com.daml.network.wallet.automation.AutoAcceptTransferOffersTrigger
import com.daml.network.wallet.config.{AutoAcceptTransfersConfig, WalletSweepConfig}
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.RequireTypes.NonNegativeNumeric
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.duration.DurationInt

class WalletSweepIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with WalletTestUtil
    with TriggerTestUtil {

  val maxBalanceUsd: BigDecimal = BigDecimal(10)
  val minBalanceUsd: BigDecimal = BigDecimal(2)
  val amuletPrice: BigDecimal = BigDecimal(1)

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) => {
        val aliceParticipant =
          CNNodeConfigTransforms
            .getParticipantIds(config.parameters.clock)("alice_validator_user")
        val aliceLedgerApiUser =
          config.validatorApps(InstanceName.tryCreate("aliceValidator")).ledgerApiUser
        val alicePartyId = PartyId
          .tryFromProtoPrimitive(
            s"${BaseLedgerConnection.sanitizeUserIdToPartyString(aliceLedgerApiUser)}::${aliceParticipant.split("::").last}"
          )
        val sv1Participant =
          CNNodeConfigTransforms
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
                c.validatorWalletUser.value -> WalletSweepConfig(
                  NonNegativeNumeric.tryCreate(maxBalanceUsd),
                  NonNegativeNumeric.tryCreate(minBalanceUsd),
                  alicePartyId.toProtoPrimitive,
                )
              )
            )
          } else if (name == "aliceValidator") {
            c.copy(
              autoAcceptTransfers = Map(
                c.validatorWalletUser.value ->
                  AutoAcceptTransfersConfig(fromParties = Seq(sv1PartyId.toProtoPrimitive))
              )
            )
          } else {
            c
          }
        }(config)
      })
      .withAmuletPrice(amuletPrice)
      .withSequencerConnectionsFromScanDisabled()

  "SV1's wallet" should {

    "not be swept if the balance is under the maximum allowed balance" in { implicit env =>
      onboardWalletUser(aliceValidatorWalletClient, aliceValidatorBackend)
      sv1WalletClient.tap(maxBalanceUsd - 5)
      val sv1BalanceAtStart = sv1WalletClient.balance().unlockedQty.longValue
      val aliceBalanceAtStart = aliceValidatorWalletClient.balance().unlockedQty.longValue
      clue("sv1 should keep its coins") {
        eventually() {
          sv1WalletClient.balance().unlockedQty.longValue shouldBe sv1BalanceAtStart
          aliceValidatorWalletClient.balance().unlockedQty.longValue shouldBe aliceBalanceAtStart
        }
      }
    }

    "be swept if the balance is above the maximum allowed balance" in { implicit env =>
      onboardWalletUser(aliceValidatorWalletClient, aliceValidatorBackend)
      sv1WalletClient.tap(maxBalanceUsd + 2)
      val aliceBalanceAtStart = aliceValidatorWalletClient.balance().unlockedQty.longValue
      clue(
        s"sv1 should transfer its coins to alice and still have a minimal balance of $minBalanceUsd"
      ) {
        eventually(40.seconds) {
          val newSv1Balance = BigDecimal(
            ccToDollars(
              sv1WalletClient.balance().unlockedQty.bigDecimal,
              amuletPrice.bigDecimal,
            )
          )
          newSv1Balance should beWithin(minBalanceUsd, maxBalanceUsd)
          aliceValidatorWalletClient.balance().unlockedQty.longValue should be > aliceBalanceAtStart
        }
      }
    }

    "check that the sweep is completed even if the first offer is not immediately accepted." in {
      implicit env =>
        onboardWalletUser(aliceValidatorWalletClient, aliceValidatorBackend)

        clue("There are no outstanding transfer offers to accept or complete") {
          eventually() {
            aliceValidatorWalletClient.listTransferOffers() shouldBe empty
            aliceValidatorWalletClient.listAcceptedTransferOffers() shouldBe empty
          }
        }
        val aliceBalanceAtStart = aliceValidatorWalletClient.balance().unlockedQty.longValue
        def sv1Balance() = BigDecimal(
          ccToDollars(
            sv1WalletClient.balance().unlockedQty.bigDecimal,
            amuletPrice.bigDecimal,
          )
        )

        setTriggersWithin(
          triggersToPauseAtStart = autoAcceptTransferOffersTriggers,
          Seq.empty,
        ) {
          val (_, firstTransferAmountUsd) = actAndCheck(
            "SV1 receives funds and exceeds the maximum balance",
            sv1WalletClient.tap(maxBalanceUsd + 2),
          )(
            "Alice sees exactly one transfer offer",
            { _ =>
              eventually() {
                sv1Balance() shouldBe >(maxBalanceUsd)
                val txOffers = aliceValidatorWalletClient.listTransferOffers()
                txOffers should have size 1
                aliceValidatorWalletClient.listAcceptedTransferOffers() shouldBe empty
                ccToDollars(txOffers.headOption.value.payload.amount.amount, amuletPrice.bigDecimal)
              }
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
                aliceValidatorWalletClient.listTransferOffers() should have size 2
                aliceValidatorWalletClient.listAcceptedTransferOffers() shouldBe empty
              }
            },
          )
        }
        clue("After resuming auto-accept, SV1 is drained and Alice receives funds") {
          eventually(40.seconds) {
            aliceValidatorWalletClient.listTransferOffers() shouldBe empty
            aliceValidatorWalletClient.listAcceptedTransferOffers() shouldBe empty
            sv1Balance() should beWithin(minBalanceUsd, maxBalanceUsd)
            aliceValidatorWalletClient
              .balance()
              .unlockedQty
              .longValue should be > aliceBalanceAtStart
          }
        }
    }
  }

  "check that the sweep is completed even if an offer is rejected" in { implicit env =>
    onboardWalletUser(aliceValidatorWalletClient, aliceValidatorBackend)

    clue("There are no outstanding transfer offers to accept or complete") {
      eventually() {
        aliceValidatorWalletClient.listTransferOffers() shouldBe empty
        aliceValidatorWalletClient.listAcceptedTransferOffers() shouldBe empty
      }
    }
    val aliceBalanceAtStart = aliceValidatorWalletClient.balance().unlockedQty.longValue

    setTriggersWithin(triggersToPauseAtStart = autoAcceptTransferOffersTriggers, Seq.empty) {
      actAndCheck(
        "SV1 receives funds and exceeds the maximum balance",
        sv1WalletClient.tap(maxBalanceUsd + 2),
      )(
        "Alice sees exactly one transfer offer",
        { _ =>
          eventually() {
            aliceValidatorWalletClient.listTransferOffers() should have size 1
            aliceValidatorWalletClient.listAcceptedTransferOffers() shouldBe empty
          }
        },
      )
      clue("Alice cancels the transfer offer") {
        aliceValidatorWalletClient.rejectTransferOffer(
          aliceValidatorWalletClient.listTransferOffers().head.contractId
        )
      }
    }
    clue("After resuming auto-accept, SV1 is drained and Alice receives funds") {
      eventually(40.seconds) {
        aliceValidatorWalletClient.listTransferOffers() shouldBe empty
        aliceValidatorWalletClient.listAcceptedTransferOffers() shouldBe empty
        val newSv1Balance = BigDecimal(
          ccToDollars(
            sv1WalletClient.balance().unlockedQty.bigDecimal,
            amuletPrice.bigDecimal,
          )
        )
        newSv1Balance should beWithin(minBalanceUsd, maxBalanceUsd)
        aliceValidatorWalletClient
          .balance()
          .unlockedQty
          .longValue should be > aliceBalanceAtStart
      }
    }
  }

  // triggers relevant to outstanding sweep transfer offers
  def autoAcceptTransferOffersTriggers(implicit
      environment: CNNodeTestConsoleEnvironment
  ): Seq[Trigger] = {
    val aliceUserName = aliceValidatorWalletClient.config.ledgerApiUser
    Seq(
      aliceValidatorBackend
        .userWalletAutomation(aliceUserName)
        .trigger[AutoAcceptTransferOffersTrigger]
    )
  }
}
