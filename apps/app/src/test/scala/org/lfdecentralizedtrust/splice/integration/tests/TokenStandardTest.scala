package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.api.v2.CommandsOuterClass
import com.daml.ledger.api.v2.value.Identifier
import com.digitalasset.canton.topology.PartyId
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
import org.lfdecentralizedtrust.splice.util.FactoryChoiceWithDisclosures

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
    val factoryChoice = transferViaTokenStandardCommands(
      participant,
      sender,
      receiver,
      amount,
    )
    participant.ledger_api_extensions.commands
      .submitJava(
        Seq(sender),
        commands = factoryChoice.commands,
        disclosedContracts = factoryChoice.disclosedContracts,
      )
  }

  def transferViaTokenStandardCommands(
      participant: ParticipantClientReference,
      sender: PartyId,
      receiver: PartyId,
      amount: BigDecimal,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): FactoryChoiceWithDisclosures = {
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
      val now = env.environment.clock.now.toInstant
      val choiceArgs = new transferinstructionv1.TransferFactory_Transfer(
        dsoParty.toProtoPrimitive,
        new transferinstructionv1.Transfer(
          sender.toProtoPrimitive,
          receiver.toProtoPrimitive,
          amount.bigDecimal,
          new holdingv1.InstrumentId(dsoParty.toProtoPrimitive, "Amulet"),
          now,
          now.plus(10, ChronoUnit.MINUTES),
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
      val factoryChoice = sv1ScanBackend.getTransferFactory(choiceArgs)
      factoryChoice.copy(disclosedContracts = factoryChoice.disclosedContracts ++ disclosedHoldings)
    }
  }

}
