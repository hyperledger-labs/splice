package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.api.v2.CommandsOuterClass
import com.daml.ledger.api.v2.value.Identifier
import com.daml.ledger.javaapi.data.Command
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet as amuletCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.TransferPreapproval
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.util.{TriggerTestUtil, WalletTestUtil}
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.crypto.*
import com.digitalasset.canton.topology.PartyId
import com.google.protobuf.ByteString
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.metadatav1
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.holdingv1
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.transferinstructionv1
import org.lfdecentralizedtrust.splice.scan.admin.http.CompactJsonScanHttpEncodings

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import scala.jdk.CollectionConverters.*

class TokenStandardTransferIntegrationTest
    extends IntegrationTest
    with HasExecutionContext
    with WalletTestUtil
    with TriggerTestUtil
    with ExternallySignedPartyTestUtil {

  // TODO (#17384): support token standard choices in the script
  override protected def runUpdateHistorySanityCheck: Boolean = false

  override lazy val updateHistoryIgnoredRootExercises = Seq(
    (TransferPreapproval.TEMPLATE_ID_WITH_PACKAGE_ID, "Archive")
  )

  override lazy val updateHistoryIgnoredRootCreates = Seq(
    TransferPreapproval.TEMPLATE_ID_WITH_PACKAGE_ID,
    amuletCodegen.AppRewardCoupon.TEMPLATE_ID_WITH_PACKAGE_ID,
  )

  override def environmentDefinition: EnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
  }

  def transferViaTokenStandardCommands(
      sender: PartyId,
      receiver: PartyId,
      amount: BigDecimal,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): (Seq[Command], Vector[CommandsOuterClass.DisclosedContract]) = {
    clue(
      s"Creating command to transfer $amount amulets via token standard from $sender to $receiver"
    ) {
      val senderHoldings =
        aliceValidatorBackend.participantClientWithAdminToken.ledger_api.state.acs.of_party(
          party = sender,
          filterInterfaces = Seq(holdingv1.Holding.TEMPLATE_ID).map(templateId =>
            Identifier(
              templateId.getPackageId,
              templateId.getModuleName,
              templateId.getEntityName,
            )
          ),
          includeCreatedEventBlob = true,
        )
      // canton requires passing these explicitly
      val disclosedHoldings: Seq[CommandsOuterClass.DisclosedContract] =
        senderHoldings.map(senderHolding =>
          com.daml.ledger.api.v2.commands.DisclosedContract.toJavaProto(
            com.daml.ledger.api.v2.commands.DisclosedContract(
              templateId = Some(senderHolding.templateId.toIdentifier),
              contractId = senderHolding.contractId,
              createdEventBlob = senderHolding.event.createdEventBlob,
            )
          )
        )
      val choiceArgs = new transferinstructionv1.TransferFactory_Transfer(
        new transferinstructionv1.Transfer(
          sender.toProtoPrimitive,
          receiver.toProtoPrimitive,
          amount.bigDecimal,
          new holdingv1.InstrumentId(dsoParty.toProtoPrimitive, "Amulet"),
          Instant.now().plus(10, ChronoUnit.MINUTES),
          senderHoldings
            .map(senderHolding => new holdingv1.Holding.ContractId(senderHolding.contractId))
            .asJava,
          new metadatav1.Metadata(java.util.Map.of()),
        ),
        new metadatav1.ExtraArgs(
          new metadatav1.ChoiceContext(java.util.Map.of()),
          new metadatav1.Metadata(java.util.Map.of()),
        ),
      )
      val transferFactory = sv1ScanBackend.getTransferFactory(choiceArgs)
      val choiceContextData =
        metadatav1.ChoiceContext.fromJson(
          transferFactory.choiceContext.choiceContextData
            .valueOrFail("Choice context data must be defined.")
            .noSpaces
        )
      val commands = new transferinstructionv1.TransferFactory.ContractId(transferFactory.factoryId)
        .exerciseTransferFactory_Transfer(
          new transferinstructionv1.TransferFactory_Transfer(
            choiceArgs.transfer,
            new metadatav1.ExtraArgs(
              choiceContextData,
              new metadatav1.Metadata(java.util.Map.of()),
            ),
          )
        )
        .commands()
        .asScala
        .toSeq
      val disclosedContracts = transferFactory.choiceContext.disclosedContracts.map {
        disclosedContract =>
          CommandsOuterClass.DisclosedContract
            .newBuilder()
            .setContractId(disclosedContract.contractId)
            .setCreatedEventBlob(
              ByteString.copyFrom(
                java.util.Base64.getDecoder.decode(disclosedContract.createdEventBlob)
              )
            )
            .setSynchronizerId(disclosedContract.synchronizerId)
            .setTemplateId(
              CompactJsonScanHttpEncodings.parseTemplateId(disclosedContract.templateId).toProto
            )
            .build()
      } ++ disclosedHoldings
      (commands, disclosedContracts)
    }
  }

  "Token Standard transfer between externally signed parties" in { implicit env =>
    // Onboard and Create/Accept ExternalPartySetupProposal for Alice
    val onboardingAlice @ OnboardingResult(aliceParty, _, alicePrivateKey) =
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
    actAndCheck(
      "Transfer 2000.0 to Alice via Token Standard", {
        val (commands, disclosedContracts) = transferViaTokenStandardCommands(
          aliceValidatorBackend.getValidatorPartyId(),
          aliceParty,
          BigDecimal(2000.0),
        )
        aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
          .submitJava(
            Seq(aliceValidatorBackend.getValidatorPartyId()),
            optTimeout = None,
            commands = commands,
            disclosedContracts = disclosedContracts,
          )
      },
    )(
      "Alice (external party) has received the 2000.0 Amulet",
      _ => {
        aliceValidatorBackend
          .getExternalPartyBalance(aliceParty)
          .totalUnlockedCoin shouldBe "2000.0000000000"
      },
    )

    // Onboard and Create/Accept ExternalPartySetupProposal for Bob
    val onboardingBob @ OnboardingResult(bobParty, _, _) =
      onboardExternalParty(aliceValidatorBackend, Some("bobExternal"))
    aliceValidatorBackend.participantClient.parties
      .hosted(filterParty = bobParty.filterString) should not be empty
    val (cidBob, _) =
      createAndAcceptExternalPartySetupProposal(
        aliceValidatorBackend,
        onboardingBob,
        verboseHashing = true,
      )
    eventually() {
      aliceValidatorBackend.lookupTransferPreapprovalByParty(bobParty) should not be empty
      aliceValidatorBackend.scanProxy.lookupTransferPreapprovalByParty(bobParty) should not be empty
    }
    aliceValidatorBackend
      .listTransferPreapprovals()
      .map(tp => tp.contract.contractId) should contain(cidBob)

    val (aliceToBobCommands, aliceToBobDisclosedContracts) = transferViaTokenStandardCommands(
      aliceParty,
      bobParty,
      BigDecimal(1000.0),
    )
    val prepareSend =
      aliceValidatorBackend.participantClientWithAdminToken.ledger_api.interactive_submission
        .prepare(
          actAs = Seq(aliceParty),
          readAs = Seq(aliceParty),
          commands = aliceToBobCommands.map(javaCommand =>
            com.daml.ledger.api.v2.commands.Command.fromJavaProto(javaCommand.toProtoCommand)
          ),
          disclosedContracts = aliceToBobDisclosedContracts.map(x =>
            com.daml.ledger.api.v2.commands.DisclosedContract.fromJavaProto(x)
          ),
          synchronizerId = Some(decentralizedSynchronizerId),
          verboseHashing = true,
        )
    prepareSend.hashingDetails should not be empty
    val submissionId = UUID.randomUUID().toString
    val (_, _) = actAndCheck(
      "Transfer 1000.0 from Alice to Bob",
      aliceValidatorBackend.participantClientWithAdminToken.ledger_api.interactive_submission
        .execute(
          prepareSend.getPreparedTransaction,
          Map(
            aliceParty -> Seq(
              crypto
                .signBytes(
                  prepareSend.preparedTransactionHash,
                  alicePrivateKey.asInstanceOf[SigningPrivateKey],
                  usage = SigningKeyUsage.ProtocolOnly,
                )
                .valueOrFail("Couldn't sign with alice's private key")
            )
          ),
          submissionId = submissionId,
          hashingSchemeVersion = prepareSend.hashingSchemeVersion,
        ),
    )(
      "validator automation completes transfer",
      _ => {
        aliceValidatorBackend
          .getExternalPartyBalance(bobParty)
          .totalUnlockedCoin shouldBe "1000.0000000000"
        BigDecimal(
          aliceValidatorBackend
            .getExternalPartyBalance(aliceParty)
            .totalUnlockedCoin
        ) should beAround(
          BigDecimal(2000 - 1000 - 16.0 - 6.0 /* 16 output fees, 6.0 sender change fees */ )
        )
      },
    )
  }

}
