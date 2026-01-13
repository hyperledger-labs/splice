package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet as amuletCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.{
  ExternalPartySetupProposal,
  TransferPreapproval,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.externalpartyamuletrules.TransferCommand
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.IssuingMiningRound
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.install.amuletoperation.CO_CreateExternalPartySetupProposal
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.install.amuletoperationoutcome.COO_CreateExternalPartySetupProposal
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.install.{
  AmuletOperation,
  WalletAppInstall,
}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.http.v0.definitions
import definitions.DamlValueEncoding.members.CompactJson
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.{
  AdvanceOpenMiningRoundTrigger,
  ExpireIssuingMiningRoundTrigger,
  ExpireTransferPreapprovalsTrigger,
}
import org.lfdecentralizedtrust.splice.util.{DisclosedContracts, TriggerTestUtil, WalletTestUtil}
import org.lfdecentralizedtrust.splice.validator.automation.{
  RenewTransferPreapprovalTrigger,
  TransferCommandSendTrigger,
}
import com.daml.ledger.javaapi.data.codegen.json.JsonLfReader
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.crypto.*
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.util.HexString
import monocle.macros.syntax.lens.*

import java.time.{Duration, Instant}
import java.util.UUID
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

// TODO(DACH-NY/canton-network-node#14568) Merge this into ExternallySignedPartyOnboardingTest
class ExternalPartySetupProposalIntegrationTest
    extends IntegrationTest
    with HasExecutionContext
    with WalletTestUtil
    with TriggerTestUtil
    with ExternallySignedPartyTestUtil {

  override lazy val sanityChecksIgnoredRootExercises = Seq(
    (TransferPreapproval.TEMPLATE_ID_WITH_PACKAGE_ID, "Archive")
  )

  override lazy val sanityChecksIgnoredRootCreates = Seq(
    TransferPreapproval.TEMPLATE_ID_WITH_PACKAGE_ID,
    amuletCodegen.AppRewardCoupon.TEMPLATE_ID_WITH_PACKAGE_ID,
    amuletCodegen.ValidatorRewardCoupon.TEMPLATE_ID_WITH_PACKAGE_ID,
  )

  override def environmentDefinition: EnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransforms(
        // set renewal duration to be same as pre-approval lifetime to ensure renewal
        // gets triggered immediately
        (_, config) =>
          ConfigTransforms.updateAllValidatorConfigs_(
            _.focus(_.transferPreapproval)
              .modify(c => c.copy(renewalDuration = c.preapprovalLifetime))
          )(config),
        // Disable renewal trigger till required in the test
        (_, config) =>
          ConfigTransforms.updateAutomationConfig(ConfigTransforms.ConfigurableApp.Validator)(
            _.withPausedTrigger[RenewTransferPreapprovalTrigger]
          )(config),
        (_, config) =>
          updateAutomationConfig(ConfigurableApp.Sv)(
            _.withPausedTrigger[AdvanceOpenMiningRoundTrigger]
              .withPausedTrigger[ExpireIssuingMiningRoundTrigger]
          )(config),
        (_, config) =>
          ConfigTransforms.updateInitialTickDuration(
            NonNegativeFiniteDuration.ofMillis(500)
          )(config),
      )

  }

  "createExternalPartySetupProposal fails if the validator has insufficient funds" in {
    implicit env =>
      val OnboardingResult(party, _, _) = onboardExternalParty(aliceValidatorBackend)
      assertThrowsAndLogsCommandFailures(
        aliceValidatorBackend.createExternalPartySetupProposal(party),
        _.errorMessage should include regex ("400 Bad Request .* Insufficient funds"),
      )
  }

  "createExternalPartySetupProposal fails if a proposal already exists" in { implicit env =>
    val OnboardingResult(party, _, _) = onboardExternalParty(aliceValidatorBackend)
    aliceValidatorWalletClient.tap(10.0)
    aliceValidatorBackend.createExternalPartySetupProposal(party)
    assertThrowsAndLogsCommandFailures(
      aliceValidatorBackend.createExternalPartySetupProposal(party),
      _.errorMessage should include regex ("409 Conflict .* ExternalPartySetupProposal contract already exists"),
    )
  }

  "createExternalPartySetupProposal fails if a preapproval already exists" in { implicit env =>
    val onboarding @ OnboardingResult(party, _, _) = onboardExternalParty(aliceValidatorBackend)
    aliceValidatorWalletClient.tap(10.0)
    createAndAcceptExternalPartySetupProposal(aliceValidatorBackend, onboarding)
    assertThrowsAndLogsCommandFailures(
      aliceValidatorBackend.createExternalPartySetupProposal(party),
      _.errorMessage should include regex ("409 Conflict .* TransferPreapproval contract already exists"),
    )
  }

  "listExternalPartySetupProposals returns an empty array if no contracts exist" in {
    implicit env =>
      aliceValidatorBackend
        .listExternalPartySetupProposals() shouldBe empty
  }

  "listTransferPreapprovals returns an empty array if no contracts exist" in { implicit env =>
    aliceValidatorBackend
      .listTransferPreapprovals() shouldBe empty
  }

  "lookupTransferPreapprovalByParty returns None if no contracts exist" in { implicit env =>
    aliceValidatorBackend
      .lookupTransferPreapprovalByParty(aliceValidatorBackend.getValidatorPartyId()) shouldBe None
  }

  "TransferPreapproval allows to transfer between externally signed parties" taggedAs (org.lfdecentralizedtrust.splice.util.Tags.SpliceAmulet_0_1_9) in {
    implicit env =>
      // Onboard and Create/Accept ExternalPartySetupProposal for Alice
      val onboardingAlice @ OnboardingResult(aliceParty, alicePublicKey, alicePrivateKey) =
        onboardExternalParty(aliceValidatorBackend, Some("aliceExternal"))
      aliceValidatorBackend.participantClient.parties
        .hosted(filterParty = aliceParty.filterString) should not be empty
      aliceValidatorWalletClient.tap(50.0)
      createAndAcceptExternalPartySetupProposal(
        aliceValidatorBackend,
        onboardingAlice,
        verboseHashing = true,
      )
      eventually() {
        aliceValidatorBackend.lookupTransferPreapprovalByParty(aliceParty) should not be empty
        aliceValidatorBackend.scanProxy.lookupTransferPreapprovalByParty(
          aliceParty
        ) should not be empty
      }

      // Transfer 2000.0 to Alice
      aliceValidatorBackend
        .getExternalPartyBalance(aliceParty)
        .totalUnlockedCoin shouldBe "0.0000000000"
      aliceValidatorWalletClient.transferPreapprovalSend(
        aliceParty,
        2000.0,
        UUID.randomUUID.toString,
      )
      eventually() {
        aliceValidatorBackend
          .getExternalPartyBalance(aliceParty)
          .totalUnlockedCoin shouldBe "2000.0000000000"
      }

      // Onboard and Create/Accept ExternalPartySetupProposal for Bob
      val onboardingBob @ OnboardingResult(bobParty, _, _) =
        onboardExternalParty(bobValidatorBackend, Some("bobExternal"))
      bobValidatorBackend.participantClient.parties
        .hosted(filterParty = bobParty.filterString) should not be empty
      bobValidatorWalletClient.tap(50.0)
      val (cidBob, _) =
        createAndAcceptExternalPartySetupProposal(
          bobValidatorBackend,
          onboardingBob,
          verboseHashing = true,
        )
      eventually() {
        bobValidatorBackend.lookupTransferPreapprovalByParty(bobParty) should not be empty
        bobValidatorBackend.scanProxy.lookupTransferPreapprovalByParty(bobParty) should not be empty
      }
      bobValidatorBackend
        .listTransferPreapprovals()
        .map(tp => tp.contract.contractId) contains cidBob

      // Lookup transfer command counter before any transfer command
      aliceValidatorBackend.scanProxy.lookupTransferCommandCounterByParty(aliceParty) shouldBe None

      // Lookup transfer command that does not exist
      aliceValidatorBackend.scanProxy.lookupTransferCommandStatus(
        aliceParty,
        0L,
      ) shouldBe None

      val (_, issuingRound) = actAndCheck(
        s"Advance rounds until there is at least one issuing round", {
          advanceRoundsByOneTickViaAutomation()
        },
      )(
        s"There is at least one issuing round",
        _ => {
          val (_, issuingRounds) = sv1ScanBackend.getOpenAndIssuingMiningRounds()
          issuingRounds.toList.headOption.value.payload
        },
      )

      val appRewardAmount = BigDecimal(10.0)

      actAndCheck(
        s"Create AppRewardCoupon for round ${issuingRound.round} through bare create",
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands.submitWithResult(
          userId = sv1Backend.config.ledgerApiUser,
          actAs = Seq(dsoParty),
          readAs = Seq.empty,
          update = new amuletCodegen.AppRewardCoupon(
            dsoParty.toProtoPrimitive,
            aliceParty.toProtoPrimitive,
            false,
            appRewardAmount.bigDecimal,
            issuingRound.round,
            java.util.Optional.empty(),
          ).create,
        ),
      )(
        "AppRewardCoupon is visible",
        _ =>
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(amuletCodegen.AppRewardCoupon.COMPANION)(
              aliceParty,
              c => c.data.provider == aliceParty.toProtoPrimitive,
            ) should have length (1),
      )

      // Transfer 1000.0 from Alice to Bob
      val prepareSend =
        aliceValidatorBackend.prepareTransferPreapprovalSend(
          aliceParty,
          bobParty,
          BigDecimal(1000.0),
          CantonTimestamp.now().plus(Duration.ofHours(24)),
          0L,
          Some("transfer-command-description"),
          verboseHashing = true,
        )
      prepareSend.hashingDetails should not be empty
      val (updateId, _) = actAndCheck(
        "Submit signed TransferCommand creation",
        aliceValidatorBackend.submitTransferPreapprovalSend(
          aliceParty,
          prepareSend.transaction,
          HexString.toHexString(
            crypto
              .signBytes(
                HexString.parseToByteString(prepareSend.txHash).value,
                alicePrivateKey.asInstanceOf[SigningPrivateKey],
                usage = SigningKeyUsage.ProtocolOnly,
              )
              .value
              .toProtoV30
              .signature
          ),
          publicKeyAsHexString(alicePublicKey),
        ),
      )(
        "validator automation completes transfer",
        _ => {
          BigDecimal(
            aliceValidatorBackend
              .getExternalPartyBalance(aliceParty)
              .totalUnlockedCoin
          ) should be(
            BigDecimal(1000) + BigDecimal(
              issuingRound.issuancePerUnfeaturedAppRewardCoupon
            ) * appRewardAmount
          )
          bobValidatorBackend
            .getExternalPartyBalance(bobParty)
            .totalUnlockedCoin shouldBe "1000.0000000000"
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(amuletCodegen.AppRewardCoupon.COMPANION)(
              aliceParty,
              c => c.data.provider == aliceParty.toProtoPrimitive,
            ) shouldBe empty
          // Transfer command counter gets created/incremented
          aliceValidatorBackend.scanProxy
            .lookupTransferCommandCounterByParty(aliceParty)
            .value
            .payload
            .nextNonce shouldBe 1
          val result = aliceValidatorBackend.scanProxy
            .lookupTransferCommandStatus(
              aliceParty,
              0L,
            )
            .value
          result.transferCommandsByContractId.values.loneElement.status shouldBe definitions.TransferCommandContractStatus.members
            .TransferCommandSentResponse(
              definitions.TransferCommandSentResponse(status = "sent")
            )
          result.transferCommandsByContractId.keys.loneElement should startWith(
            prepareSend.transferCommandContractIdPrefix
          )
          val payload = TransferCommand
            .jsonDecoder()
            .decode(
              new JsonLfReader(
                result.transferCommandsByContractId.values.loneElement.contract.payload.noSpaces
              )
            )
          payload shouldBe new TransferCommand(
            dsoParty.toProtoPrimitive,
            aliceParty.toProtoPrimitive,
            bobParty.toProtoPrimitive,
            aliceValidatorBackend.getValidatorPartyId().toProtoPrimitive,
            BigDecimal(1000.0).bigDecimal,
            payload.expiresAt,
            0L,
            java.util.Optional.of("transfer-command-description"),
          )
        },
      )
      val update = eventuallySucceeds() {
        sv1ScanBackend.getUpdate(updateId, encoding = CompactJson)
      }
      // Create a validator reward to test reward minting
      val (_, rewardRound) = actAndCheck(
        "Create validator reward",
        createRewards(
          appRewards = Seq.empty,
          validatorRewards = Seq((aliceParty, BigDecimal(1.0))),
        ),
      )(
        "reward is observable",
        _ =>
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(amuletCodegen.ValidatorRewardCoupon.COMPANION)(
              aliceParty,
              c => c.data.user == aliceParty.toProtoPrimitive,
            )
            .loneElement
            .data
            .round
            .number,
      )

      actAndCheck(
        s"Advance rounds until $rewardRound is issuing", {
          advanceRoundsByOneTickViaAutomation()
          advanceRoundsByOneTickViaAutomation()
          advanceRoundsByOneTickViaAutomation()
        },
      )(
        s"Round $rewardRound is issuing",
        _ => {
          val (_, issuingRounds) = sv1ScanBackend.getOpenAndIssuingMiningRounds()
          issuingRounds.map(_.payload.round.number) should contain(rewardRound)
        },
      )
      clue("ValidatorRewardCoupon gets collected") {
        eventually() {
          // Just checking for archival. Checking the tx history or something for collection is a bit annoying since there
          // are multiple things resulting in validator rewards and we only get a total sum.
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(amuletCodegen.ValidatorRewardCoupon.COMPANION)(
              aliceParty,
              c => c.data.user == aliceParty.toProtoPrimitive,
            ) shouldBe empty

          // Sanity check that the reward really got collected and not expired.
          val (_, issuingRounds) = sv1ScanBackend.getOpenAndIssuingMiningRounds()
          issuingRounds.map(_.payload.round.number) should contain(rewardRound)
        }
      }

      inside(update) {
        case definitions.UpdateHistoryItem.members.UpdateHistoryTransaction(transaction) =>
          forExactly(1, transaction.eventsById) {
            case (_, definitions.TreeEvent.members.ExercisedEvent(ev)) =>
              ev.choice shouldBe "ExternalPartyAmuletRules_CreateTransferCommand"
            case _ => fail()
          }
      }

      // Check that transfer works correctly with featured app rights
      bobValidatorWalletClient.selfGrantFeaturedAppRight()
      // Transfer 500.0 from Alice to Bob
      val prepareSendFeatured =
        aliceValidatorBackend.prepareTransferPreapprovalSend(
          aliceParty,
          bobParty,
          BigDecimal(500.0),
          CantonTimestamp.now().plus(Duration.ofHours(24)),
          1L,
          Some("transfer-command-description"),
          verboseHashing = true,
        )
      prepareSendFeatured.hashingDetails should not be empty
      val (_, _) = actAndCheck(
        "Submit signed TransferCommand creation",
        aliceValidatorBackend.submitTransferPreapprovalSend(
          aliceParty,
          prepareSendFeatured.transaction,
          HexString.toHexString(
            crypto
              .signBytes(
                HexString.parseToByteString(prepareSendFeatured.txHash).value,
                alicePrivateKey.asInstanceOf[SigningPrivateKey],
                usage = SigningKeyUsage.ProtocolOnly,
              )
              .value
              .toProtoV30
              .signature
          ),
          publicKeyAsHexString(alicePublicKey),
        ),
      )(
        "validator automation completes transfer",
        _ => {
          BigDecimal(
            aliceValidatorBackend
              .getExternalPartyBalance(aliceParty)
              .totalUnlockedCoin
          ) should be(
            BigDecimal(
              2000 - 1000 - 500
            ) + BigDecimal(issuingRound.issuancePerUnfeaturedAppRewardCoupon) * appRewardAmount
          )
          bobValidatorBackend
            .getExternalPartyBalance(bobParty)
            .totalUnlockedCoin shouldBe "1500.0000000000"
          val rewards =
            bobValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
              .filterJava(amuletCodegen.AppRewardCoupon.COMPANION)(
                bobValidatorBackend.getValidatorUserInfo().primaryParty,
                c =>
                  c.data.provider == bobValidatorBackend
                    .getValidatorUserInfo()
                    .primaryParty
                    .toProtoPrimitive,
              )
          rewards.loneElement.data.featured shouldBe true
        },
      )

      val txs = sv1ScanBackend.listActivity(None, 1000)
      // Alice transfers twice (1000, and then 500 to bob)
      forExactly(2, txs) { tx =>
        // Test that the tx history for the TransferCommand_Send exercise gets parsed properly.
        val transfer = tx.transfer.value
        transfer.sender.party shouldBe aliceParty.toProtoPrimitive
        transfer.transferKind shouldBe Some(
          definitions.Transfer.TransferKind.members.PreapprovalSend
        )
        transfer.description shouldBe Some("transfer-command-description")
      }

      // Check that transfer command gets archived if preapproval does not exist.
      val sv1Party = sv1Backend.getDsoInfo().svParty
      val now = env.environment.clock.now.toInstant
      // Create a preapproval temporarily, otherwise the prepare step already rejects
      val (preapproval, _) = actAndCheck(
        "Create preapproval",
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
          .submitWithResult(
            userId = sv1Backend.config.ledgerApiUser,
            actAs = Seq(dsoParty, sv1Party),
            readAs = Seq.empty,
            update = new TransferPreapproval(
              dsoParty.toProtoPrimitive,
              sv1Party.toProtoPrimitive,
              sv1Party.toProtoPrimitive,
              now,
              now,
              now.plusMillis(500),
            ).create,
          ),
      )(
        "Preapproval is ingested by scan",
        _ =>
          inside(aliceValidatorBackend.scanProxy.lookupTransferPreapprovalByParty(sv1Party)) {
            case Some(_) =>
              succeed
          },
      )

      val prepareSendNoPreapproval =
        aliceValidatorBackend.prepareTransferPreapprovalSend(
          aliceParty,
          sv1Party,
          BigDecimal(10.0),
          CantonTimestamp.now().plus(Duration.ofHours(24)),
          2L,
          Some("transfer-command-description"),
        )
      prepareSendNoPreapproval.hashingDetails shouldBe empty

      // Archive the preapproval
      sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
        .submitWithResult(
          userId = sv1Backend.config.ledgerApiUser,
          actAs = Seq(dsoParty, sv1Party),
          readAs = Seq.empty,
          update = preapproval.contractId.exerciseArchive(),
        )
      setTriggersWithin(triggersToPauseAtStart =
        Seq(aliceValidatorBackend.validatorAutomation.trigger[TransferCommandSendTrigger])
      ) {
        val (_, suffixedCid) = actAndCheck(
          "Submit signed TransferCommand creation",
          aliceValidatorBackend.submitTransferPreapprovalSend(
            aliceParty,
            prepareSendNoPreapproval.transaction,
            HexString.toHexString(
              crypto
                .signBytes(
                  HexString.parseToByteString(prepareSendNoPreapproval.txHash).value,
                  alicePrivateKey.asInstanceOf[SigningPrivateKey],
                  usage = SigningKeyUsage.ProtocolOnly,
                )
                .value
                .toProtoV30
                .signature
            ),
            publicKeyAsHexString(alicePublicKey),
          ),
        )(
          "TransferCommand is created",
          _ => {
            aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
              .filterJava(TransferCommand.COMPANION)(
                aliceParty,
                c => c.data.sender == aliceParty.toProtoPrimitive,
              )
              .loneElement
              .id
          },
        )
        actAndCheck(
          "Resume validator automation for TransferCommands",
          aliceValidatorBackend.validatorAutomation.trigger[TransferCommandSendTrigger].resume(),
        )(
          "TransferCommand gets archived",
          _ => {
            aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
              .filterJava(TransferCommand.COMPANION)(
                aliceParty,
                c => c.data.sender == aliceParty.toProtoPrimitive,
              ) shouldBe empty
            val result = aliceValidatorBackend.scanProxy
              .lookupTransferCommandStatus(
                aliceParty,
                2L,
              )
              .value
            result.transferCommandsByContractId.values.loneElement.status shouldBe definitions.TransferCommandContractStatus.members
              .TransferCommandFailedResponse(
                definitions.TransferCommandFailedResponse(
                  status = "failed",
                  failureKind =
                    definitions.TransferCommandFailedResponse.FailureKind.members.Failed,
                  reason =
                    s"ITR_Other(No TransferPreapproval for receiver '${sv1Party.toProtoPrimitive}')",
                )
              )
            result.transferCommandsByContractId.keys.loneElement shouldBe suffixedCid.contractId
          },
        )
      }
  }

  "TransferPreapprovals get renewed by validator automation" in { implicit env =>
    val onboarding = onboardExternalParty(aliceValidatorBackend)
    aliceValidatorWalletClient.tap(10.0)
    val (_, initial) = actAndCheck(
      s"Setup external party ${onboarding.party} on alice validator",
      createAndAcceptExternalPartySetupProposal(aliceValidatorBackend, onboarding),
    )(
      s"TransferPreapproval for external party ${onboarding.party} was created",
      { _ =>
        val preapproval =
          aliceValidatorBackend.lookupTransferPreapprovalByParty(onboarding.party).value
        preapproval.payload.lastRenewedAt should be(preapproval.payload.validFrom)
        preapproval
      },
    )

    def renewalTrigger =
      aliceValidatorBackend.validatorAutomation.trigger[RenewTransferPreapprovalTrigger]
    // Trigger renewal
    setTriggersWithin(Seq.empty, triggersToResumeAtStart = Seq(renewalTrigger)) {
      eventually() {
        val renewed = aliceValidatorBackend.lookupTransferPreapprovalByParty(onboarding.party).value
        renewed.contractId should not be initial.contractId
        renewed.payload.lastRenewedAt should not be renewed.payload.validFrom
        renewed.payload.expiresAt should be(
          initial.payload.expiresAt.plus(
            aliceValidatorBackend.config.transferPreapproval.preapprovalLifetime.asJava
          )
        )
      }
    }
  }

  // TODO(DACH-NY/canton-network-node#15468): Simplify this test to not require a ledger submission
  "TransferPreapprovals get expired by SV automation" in { implicit env =>
    val onboarding = onboardExternalParty(aliceValidatorBackend)
    val externalParty = onboarding.party
    aliceValidatorWalletClient.tap(10.0)
    aliceValidatorBackend.lookupTransferPreapprovalByParty(externalParty) shouldBe None
    // Pause the expiry trigger
    setTriggersWithin(
      triggersToPauseAtStart =
        env.svs.local.map(_.dsoDelegateBasedAutomation.trigger[ExpireTransferPreapprovalsTrigger])
    ) {
      val (proposalCid, _) = actAndCheck(
        s"Create a proposal to setup an external party with a soon-to-expire transfer preapproval",
        createExternalPartyProposalViaLedgerApi(externalParty, Instant.now().plusSeconds(2)),
      )(
        s"External party setup proposal for $externalParty was created",
        { proposalCid =>
          aliceValidatorBackend
            .listExternalPartySetupProposals()
            .map(c => c.contract.contractId) should contain(proposalCid)
        },
      )
      actAndCheck(
        "External party accepts the proposal",
        acceptExternalPartySetupProposal(aliceValidatorBackend, onboarding, proposalCid),
      )(
        "An expiring TransferPreapproval for the external party is created",
        _ =>
          aliceValidatorBackend.lookupTransferPreapprovalByParty(externalParty) should not be None,
      )
    }

    // Expiry trigger resumed
    clue("SV automation expires the TransferPreapproval contract") {
      eventually() {
        aliceValidatorBackend.lookupTransferPreapprovalByParty(externalParty) shouldBe None
      }
    }
  }

  private def createExternalPartyProposalViaLedgerApi(receiverParty: PartyId, expiresAt: Instant)(
      implicit env: SpliceTestConsoleEnvironment
  ): ExternalPartySetupProposal.ContractId = {
    val validatorParty = aliceValidatorBackend.getValidatorPartyId()
    val transferContext = sv1ScanBackend.getTransferContextWithInstances(env.environment.clock.now)
    val inputAmulets = aliceValidatorWalletClient.list().amulets
    val walletInstall = inside(
      aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
        .filterJava(WalletAppInstall.COMPANION)(
          validatorParty,
          c => c.data.validatorParty == c.data.endUserParty,
        )
    ) { case Seq(install) => install }
    val executeBatchCmd = walletInstall.id.exerciseWalletAppInstall_ExecuteBatch(
      new splice.amuletrules.PaymentTransferContext(
        transferContext.amuletRules.contract.contractId,
        new splice.amuletrules.TransferContext(
          transferContext.latestOpenMiningRound.contract.contractId,
          Map.empty[Round, IssuingMiningRound.ContractId].asJava,
          Map.empty[String, splice.amulet.ValidatorRight.ContractId].asJava,
          None.toJava,
        ),
      ),
      inputAmulets
        .map(_.contract.contractId.contractId)
        .map[splice.amuletrules.TransferInput](cid =>
          new splice.amuletrules.transferinput.InputAmulet(new splice.amulet.Amulet.ContractId(cid))
        )
        .asJava,
      List[AmuletOperation](
        new CO_CreateExternalPartySetupProposal(
          receiverParty.toProtoPrimitive,
          expiresAt,
        )
      ).asJava,
    )
    inside(
      aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
        .submitWithResult(
          aliceValidatorBackend.config.ledgerApiUser,
          Seq(validatorParty),
          Seq(validatorParty),
          executeBatchCmd,
          disclosedContracts = DisclosedContracts
            .forTesting(
              transferContext.amuletRules,
              transferContext.latestOpenMiningRound,
            )
            .toLedgerApiDisclosedContracts,
        )
        .exerciseResult
        .outcomes
        .asScala
        .toSeq
    ) { case Seq(outcome) =>
      outcome.asInstanceOf[COO_CreateExternalPartySetupProposal].contractIdValue
    }
  }
}
