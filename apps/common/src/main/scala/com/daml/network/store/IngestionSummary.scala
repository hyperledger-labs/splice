package com.daml.network.store

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.ledger.javaapi.data.{CreatedEvent, ExercisedEvent}
import com.daml.network.store.MultiDomainAcsStore.TransferId
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.DomainId

private[store] case class IngestionSummary[TXE <: TxLogStore.Entry[?]](
    txId: Option[String],
    offset: Option[String],
    newAcsSize: Int,
    ingestedCreatedEvents: Seq[CreatedEvent],
    numFilteredCreatedEvents: Int,
    ingestedArchivedEvents: Seq[ExercisedEvent],
    numFilteredArchivedEvents: Int,
    addedContractLocations: Seq[ContractLocation],
    removedContractLocations: Seq[ContractLocation],
    addedTransferInEvents: Seq[(ContractId[?], TransferId)],
    numFilteredTransferInEvents: Int,
    removedTransferInEvents: Seq[(ContractId[?], TransferId)],
    addedTransferOutEvents: Seq[(ContractId[?], TransferId)],
    numFilteredTransferOutEvents: Int,
    removedTransferOutEvents: Seq[(ContractId[?], TransferId)],
    addedArchivedTombstones: Seq[ContractId[?]],
    removedArchivedTombstones: Seq[ContractId[?]],
    ingestedTxLogEntries: Seq[TXE],
) extends PrettyPrinting {

  import com.daml.network.util.PrettyInstances
  import com.daml.network.util.PrettyInstances.*

  @SuppressWarnings(Array("org.wartremover.warts.Product"))
  implicit val txLogPretty: Pretty[TXE] = adHocPrettyInstance

  implicit def prettyContractLocation: Pretty[ContractLocation] =
    prettyNode("ContractLocation", param("cid", _.contractId), param("domain", _.domain))

  implicit def prettyTransferId: Pretty[TransferId] =
    prettyNode(
      "TransferId",
      param("source", _.source),
      param[TransferId, String]("id", _.id)(PrettyInstances.prettyString),
    )

  override def pretty: Pretty[this.type] = {

    def paramIfNonZero[T](name: String, getValue: T => Int) =
      param(name, getValue(_), (x: T) => getValue(x) != 0)

    prettyNode(
      "", // intentionally left empty, as that worked better in the log messages above
      paramIfDefined("txId", _.txId.map(_.unquoted)),
      paramIfDefined("offset", _.offset.map(_.unquoted)),
      param("newAcsSize", _.newAcsSize),
      paramIfNonEmpty("ingestedCreatedEvents", _.ingestedCreatedEvents),
      paramIfNonZero("numFilteredCreatedEvents", _.numFilteredCreatedEvents),
      paramIfNonEmpty("ingestedArchivedEvents", _.ingestedArchivedEvents),
      paramIfNonZero("numFilteredArchivedEvents", _.numFilteredArchivedEvents),
      paramIfNonEmpty("addedContractLocations", _.addedContractLocations),
      paramIfNonEmpty("removedContractLocations", _.removedContractLocations),
      paramIfNonEmpty("addedTransferInEvents", _.addedTransferInEvents),
      paramIfNonZero("numFilteredTransferInEvents", _.numFilteredTransferInEvents),
      paramIfNonEmpty("removedTransferInEvents", _.removedTransferInEvents),
      paramIfNonEmpty("addedTransferOutEvents", _.addedTransferOutEvents),
      paramIfNonZero("numFilteredTransferOutEvents", _.numFilteredTransferOutEvents),
      paramIfNonEmpty("removedTransferOutEvents", _.removedTransferOutEvents),
      paramIfNonEmpty("addedArchivedTombstones", _.addedArchivedTombstones),
      paramIfNonEmpty("removedArchivedTombstones", _.removedArchivedTombstones),
      paramIfNonEmpty(
        "ingestedTxLogEntries",
        _.ingestedTxLogEntries,
      ),
    )
  }
}

private[store] object IngestionSummary {
  def empty[TXE <: TxLogStore.Entry[?]]: IngestionSummary[TXE] = IngestionSummary(
    txId = None,
    offset = None,
    newAcsSize = 0,
    ingestedCreatedEvents = Seq.empty,
    numFilteredCreatedEvents = 0,
    ingestedArchivedEvents = Seq.empty,
    numFilteredArchivedEvents = 0,
    addedContractLocations = Seq.empty,
    removedContractLocations = Seq.empty,
    addedTransferInEvents = Seq.empty,
    numFilteredTransferInEvents = 0,
    removedTransferInEvents = Seq.empty,
    addedTransferOutEvents = Seq.empty,
    numFilteredTransferOutEvents = 0,
    removedTransferOutEvents = Seq.empty,
    addedArchivedTombstones = Seq.empty,
    removedArchivedTombstones = Seq.empty,
    ingestedTxLogEntries = Seq.empty,
  )
}

private[store] case class ContractLocation(
    contractId: ContractId[?],
    domain: DomainId,
)
