package com.daml.network.store

import com.daml.ledger.javaapi.data
import com.daml.ledger.javaapi.data.{CreatedEvent, Identifier}
import com.daml.network.codegen.java.cc
import com.daml.network.util.Contract
import com.digitalasset.canton.topology.PartyId

import java.io.{InputStream, OutputStream}
import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*

object AcsStoreDump {

  def writeEvents(events: Seq[data.CreatedEvent], output: OutputStream): Unit = {
    events.foreach { event =>
      // TODO(#6073): consider switching to JSON for ease of human inspection and editing
      event.toProto.writeDelimitedTo(output)
    }
  }

  def readFile(input: InputStream): Seq[data.CreatedEvent] = {
    val events = ListBuffer[data.CreatedEvent]()
    @tailrec
    def parse(): Unit = {
      val event =
        com.daml.ledger.api.v1.EventOuterClass.CreatedEvent.parseDelimitedFrom(input)
      if (event != null) {
        events.append(data.CreatedEvent.fromProto(event))
        parse()
      }
    }
    parse()
    events.toSeq
  }

  def extractImportCommands(svcParty: PartyId, productionMode: Boolean)(
      ev: data.CreatedEvent
  ): Seq[data.Command] =
    extractCoinContract(ev).toList.flatMap(co => {
      val receiverName =
        if (productionMode) co.payload.owner
        else {
          // Drop the party name suffix, as we don't migrate the parties to a fresh participant node
          dropPartyNameSuffix(co.payload.owner)
        }

      new cc.coinimport.ImportCrate(
        svcParty.toProtoPrimitive,
        receiverName,
        productionMode,
        co.payload, // TODO(#6073): consider embedding the contract-id as well for easier traceability
      ).create().commands().asScala.toSeq
    })

  def dropPartyNameSuffix(partyStr: String): String =
    "-[a-z0-9_]+::.*$".r.replaceFirstIn(partyStr, "")

  /** Extract a Coin contract serialized using a different package then ours. */
  private def extractCoinContract(
      ev: data.CreatedEvent
  ): Option[Contract[cc.coin.Coin.ContractId, cc.coin.Coin]] = {
    // fixup event to cater for changes in the package-id
    val id = ev.getTemplateId
    val fixedId = new Identifier(
      cc.coin.Coin.COMPANION.TEMPLATE_ID.getPackageId,
      id.getModuleName,
      id.getEntityName,
    )
    val fixedEv = new CreatedEvent(
      ev.getWitnessParties,
      ev.getEventId,
      fixedId,
      ev.getContractId,
      ev.getArguments,
      ev.getCreateArgumentsBlob,
      ev.getContractMetadata,
      ev.getInterfaceViews,
      ev.getFailedInterfaceViews,
      ev.getAgreementText,
      ev.getContractKey,
      ev.getSignatories,
      ev.getObservers,
    )
    Contract.fromCreatedEvent(cc.coin.Coin.COMPANION)(fixedEv)
  }

}
