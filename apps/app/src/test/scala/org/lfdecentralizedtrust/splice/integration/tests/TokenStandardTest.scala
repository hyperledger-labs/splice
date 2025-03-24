package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.api.v2.CommandsOuterClass
import com.daml.ledger.api.v2.value.Identifier
import com.daml.ledger.javaapi.data.Command
import com.digitalasset.canton.topology.PartyId
import com.google.protobuf.ByteString
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.{
  holdingv1,
  metadatav1,
  transferinstructionv1,
}
import org.lfdecentralizedtrust.splice.console.ParticipantClientReference
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.scan.admin.http.CompactJsonScanHttpEncodings

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.jdk.CollectionConverters.*

trait TokenStandardTest extends IntegrationTest {

  def executeTransferViaTokenStandard(
      participant: ParticipantClientReference,
      sender: PartyId,
      receiver: PartyId,
      amount: BigDecimal,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ) = {
    val (commands, disclosedContracts) = transferViaTokenStandardCommands(
      participant,
      sender,
      receiver,
      amount,
    )
    participant.ledger_api_extensions.commands
      .submitJava(
        Seq(sender),
        optTimeout = None,
        commands = commands,
        disclosedContracts = disclosedContracts,
      )
  }

  def transferViaTokenStandardCommands(
      participant: ParticipantClientReference,
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
        participant.ledger_api.state.acs.of_party(
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
        dsoParty.toProtoPrimitive,
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
          new metadatav1.ChoiceContext(
            java.util.Map.of()
          ),
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
            choiceArgs.expectedAdmin,
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

}
