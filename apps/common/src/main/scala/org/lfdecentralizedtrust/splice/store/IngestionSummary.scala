// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.ledger.javaapi.data.{CreatedEvent, ExercisedEvent}
import com.daml.metrics.api.MetricsContext
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.{
  ContractStateEvent,
  ReassignmentId,
}
import com.digitalasset.canton.config.CantonRequireTypes.String3
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.pretty.Pretty
import com.digitalasset.canton.participant.pretty.Implicits.prettyContractId
import com.digitalasset.canton.topology.SynchronizerId

import scala.collection.mutable

final case class IngestionSummary(
    offset: Option[Long],
    synchronizerIdToRecordTime: Map[SynchronizerId, CantonTimestamp],
    newAcsSize: Int,
    ingestedCreatedEvents: Vector[CreatedEvent],
    numFilteredCreatedEvents: Int,
    ingestedArchivedEvents: Vector[ExercisedEvent],
    numFilteredArchivedEvents: Int,
    updatedContractStates: Vector[ContractStateEvent],
    addedAssignEvents: Vector[(ContractId[?], ReassignmentId)],
    numFilteredAssignEvents: Int,
    removedAssignEvents: Vector[(ContractId[?], ReassignmentId)],
    addedUnassignEvents: Vector[(ContractId[?], ReassignmentId)],
    numFilteredUnassignEvents: Int,
    removedUnassignEvents: Vector[(ContractId[?], ReassignmentId)],
    prunedContracts: Vector[ContractId[?]],
    ingestedTxLogEntries: Seq[(String3, String)],
)

private[store] object IngestionSummary {
  def empty[TXE]: IngestionSummary = Empty

  private val Empty: IngestionSummary = IngestionSummary(
    offset = None,
    synchronizerIdToRecordTime = Map.empty,
    newAcsSize = 0,
    ingestedCreatedEvents = Vector.empty,
    updatedContractStates = Vector.empty,
    numFilteredCreatedEvents = 0,
    ingestedArchivedEvents = Vector.empty,
    numFilteredArchivedEvents = 0,
    addedAssignEvents = Vector.empty,
    numFilteredAssignEvents = 0,
    removedAssignEvents = Vector.empty,
    addedUnassignEvents = Vector.empty,
    numFilteredUnassignEvents = 0,
    removedUnassignEvents = Vector.empty,
    prunedContracts = Vector.empty,
    ingestedTxLogEntries = Seq.empty,
  )

  import Pretty.{prettyString as _, *}
  import org.lfdecentralizedtrust.splice.util.PrettyInstances.*

  implicit def pretty: Pretty[IngestionSummary] = {
    def paramIfNonZero[T](name: String, getValue: T => Int) =
      param(name, getValue(_), (x: T) => getValue(x) != 0)

    prettyNode(
      "", // intentionally left empty, as that worked better in the log messages above
      paramIfDefined("offset", _.offset),
      param("newAcsSize", _.newAcsSize),
      param("synchronizerIdToRecordTime", _.synchronizerIdToRecordTime),
      paramIfNonEmpty("ingestedCreatedEvents", _.ingestedCreatedEvents),
      paramIfNonZero("numFilteredCreatedEvents", _.numFilteredCreatedEvents),
      paramIfNonEmpty("ingestedArchivedEvents", _.ingestedArchivedEvents),
      paramIfNonZero("numFilteredArchivedEvents", _.numFilteredArchivedEvents),
      paramIfNonEmpty("updatedContractStates", _.updatedContractStates),
      paramIfNonEmpty("addedAssignEvents", _.addedAssignEvents),
      paramIfNonZero("numFilteredAssignEvents", _.numFilteredAssignEvents),
      paramIfNonEmpty("removedAssignEvents", _.removedAssignEvents),
      paramIfNonEmpty("addedUnassignEvents", _.addedUnassignEvents),
      paramIfNonZero("numFilteredUnassignEvents", _.numFilteredUnassignEvents),
      paramIfNonEmpty("removedUnassignEvents", _.removedUnassignEvents),
      paramIfNonEmpty("prunedContracts", _.prunedContracts),
      paramIfNonEmpty(
        "ingestedTxLogEntries",
        _.ingestedTxLogEntries.map { case (typee, json) =>
          typee.str.unquoted -> json.unquoted
        },
      ),
    )
  }

  private[this] implicit def prettyReassignmentId: Pretty[ReassignmentId] =
    prettyNode(
      "ReassignmentId",
      param("source", _.source),
      param[ReassignmentId, String]("id", _.id)(prettyString),
    )
}

/** Like [[IngestionSummary]], but with all fields mutable to simplify collecting the content from helper methods */
@SuppressWarnings(Array("org.wartremover.warts.Var"))
case class MutableIngestionSummary(
    ingestedCreatedEvents: mutable.ArrayBuffer[CreatedEvent],
    var numFilteredCreatedEvents: Int,
    ingestedArchivedEvents: mutable.ArrayBuffer[ExercisedEvent],
    var numFilteredArchivedEvents: Int,
    updatedContractStates: mutable.ArrayBuffer[ContractStateEvent],
    addedAssignEvents: mutable.ArrayBuffer[(ContractId[?], ReassignmentId)],
    var numFilteredAssignEvents: Int,
    removedAssignEvents: mutable.ArrayBuffer[(ContractId[?], ReassignmentId)],
    addedUnassignEvents: mutable.ArrayBuffer[(ContractId[?], ReassignmentId)],
    var numFilteredUnassignEvents: Int,
    removedUnassignEvents: mutable.ArrayBuffer[(ContractId[?], ReassignmentId)],
    prunedContracts: mutable.ArrayBuffer[ContractId[?]],
    ingestedTxLogEntries: mutable.ArrayBuffer[(String3, String)],
) {
  def acsSizeDiff: Int = ingestedCreatedEvents.size - ingestedArchivedEvents.size

  def toIngestionSummary(
      synchronizerIdToRecordTime: Map[SynchronizerId, CantonTimestamp],
      offset: Long,
      newAcsSize: Int,
      metrics: StoreMetrics,
  ): IngestionSummary = {
    // We update the metrics in here as it's the easiest way
    // to not miss any place that might need updating.
    metrics.acsSize.updateValue(newAcsSize.toLong)
    metrics.ingestedTxLogEntries.mark(ingestedTxLogEntries.size.toLong)(MetricsContext.Empty)
    metrics.eventCount.inc(this.ingestedCreatedEvents.length.toLong)(
      MetricsContext("event_type" -> "created")
    )
    metrics.eventCount.inc(this.ingestedArchivedEvents.length.toLong)(
      MetricsContext("event_type" -> "archived")
    )
    metrics.completedIngestions.mark()
    synchronizerIdToRecordTime.foreach { case (synchronizer, recordTime) =>
      metrics
        .getLastIngestedRecordTimeMsForSynchronizer(synchronizer)
        .updateValue(recordTime.toEpochMilli)
    }
    IngestionSummary(
      offset = Some(offset),
      synchronizerIdToRecordTime = synchronizerIdToRecordTime,
      newAcsSize = newAcsSize,
      ingestedCreatedEvents = this.ingestedCreatedEvents.toVector,
      numFilteredCreatedEvents = this.numFilteredCreatedEvents,
      ingestedArchivedEvents = this.ingestedArchivedEvents.toVector,
      numFilteredArchivedEvents = this.numFilteredArchivedEvents,
      updatedContractStates = this.updatedContractStates.toVector,
      addedAssignEvents = this.addedAssignEvents.toVector,
      numFilteredAssignEvents = this.numFilteredAssignEvents,
      removedAssignEvents = this.removedAssignEvents.toVector,
      addedUnassignEvents = this.addedUnassignEvents.toVector,
      numFilteredUnassignEvents = this.numFilteredUnassignEvents,
      removedUnassignEvents = this.removedUnassignEvents.toVector,
      prunedContracts = Vector.empty,
      ingestedTxLogEntries = this.ingestedTxLogEntries.toSeq,
    )
  }
}

object MutableIngestionSummary {
  def empty: MutableIngestionSummary = MutableIngestionSummary(
    mutable.ArrayBuffer.empty,
    0,
    mutable.ArrayBuffer.empty,
    0,
    mutable.ArrayBuffer.empty,
    mutable.ArrayBuffer.empty,
    0,
    mutable.ArrayBuffer.empty,
    mutable.ArrayBuffer.empty,
    0,
    mutable.ArrayBuffer.empty,
    mutable.ArrayBuffer.empty,
    mutable.ArrayBuffer.empty,
  )
}
