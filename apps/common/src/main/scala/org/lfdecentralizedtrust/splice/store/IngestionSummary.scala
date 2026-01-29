// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.ledger.javaapi.data.{CreatedEvent, ExercisedEvent}
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.{
  ContractStateEvent,
  ReassignmentId,
}
import com.digitalasset.canton.config.CantonRequireTypes.String3
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.pretty.Pretty
import com.digitalasset.canton.participant.pretty.Implicits.prettyContractId
import com.digitalasset.canton.topology.SynchronizerId

final case class IngestionSummary(
    updateId: Option[String],
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
    updateId = None,
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
      paramIfDefined("updateId", _.updateId.map(_.unquoted)),
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
