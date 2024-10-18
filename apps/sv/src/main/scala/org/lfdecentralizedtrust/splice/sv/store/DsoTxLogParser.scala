// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.store

import cats.Monoid
import cats.syntax.foldable.*
import com.daml.ledger.javaapi.data.*
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.{
  ARC_AmuletRules,
  ARC_DsoRules,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  ActionRequiringConfirmation,
  DsoRules_CloseVoteRequest,
  DsoRules_CloseVoteRequestResult,
}
import org.lfdecentralizedtrust.splice.store.TxLogStore
import org.lfdecentralizedtrust.splice.store.events.DsoRulesCloseVoteRequest
import org.lfdecentralizedtrust.splice.util.ExerciseNode
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext

import scala.collection.immutable
import scala.jdk.CollectionConverters.*

class DsoTxLogParser(
    override val loggerFactory: NamedLoggerFactory
) extends TxLogStore.Parser[
      TxLogEntry
    ]
    with NamedLogging {

  import DsoTxLogParser.*

  private def parseTree(tree: TransactionTree, domainId: DomainId, root: TreeEvent)(implicit
      tc: TraceContext
  ): State = {

    root match {
      case exercised: ExercisedEvent =>
        exercised match {
          case DsoRulesCloseVoteRequest(node) =>
            State.fromCloseVoteRequest(node)
          case _ => parseTrees(tree, domainId, exercised.getChildEventIds.asScala.toList)
        }

      case _: CreatedEvent => State.empty

      case _ =>
        sys.error("The above match should be exhaustive")
    }
  }

  private def parseTrees(tree: TransactionTree, domainId: DomainId, rootsEventIds: List[String])(
      implicit tc: TraceContext
  ): State = {
    val roots = rootsEventIds.map(tree.getEventsById.get(_))
    roots.foldMap(parseTree(tree, domainId, _))
  }

  override def tryParse(tx: TransactionTree, domain: DomainId)(implicit
      tc: TraceContext
  ): Seq[TxLogEntry] = {
    val ret = parseTrees(tx, domain, tx.getRootEventIds.asScala.toList).entries
    ret
  }

  override def error(offset: Long, eventId: String, domainId: DomainId): Option[TxLogEntry] =
    Some(
      ErrorTxLogEntry()
    )

}

object DsoTxLogParser {

  case class State(
      entries: immutable.Queue[TxLogEntry]
  ) {
    def appended(other: State): State = State(
      entries = entries.appendedAll(other.entries)
    )
  }

  object State {

    def fromCloseVoteRequest(
        node: ExerciseNode[DsoRules_CloseVoteRequest, DsoRules_CloseVoteRequestResult]
    ): State = {
      State(
        immutable.Queue(
          VoteRequestTxLogEntry(
            result = Some(node.result.value)
          )
        )
      )
    }

    def mapActionName(
        action: ActionRequiringConfirmation
    ): String = {
      action match {
        case arcDsoRules: ARC_DsoRules =>
          arcDsoRules.dsoAction.getClass.getSimpleName
        case arcAmuletRules: ARC_AmuletRules =>
          arcAmuletRules.amuletRulesAction.getClass.getSimpleName
        case _ => ""
      }
    }

    def empty: State = State(
      entries = immutable.Queue.empty
    )

    implicit val stateMonoid: Monoid[State] = new Monoid[State] {
      override val empty = State(immutable.Queue.empty)

      override def combine(a: State, b: State) =
        a.appended(b)
    }
  }

}
