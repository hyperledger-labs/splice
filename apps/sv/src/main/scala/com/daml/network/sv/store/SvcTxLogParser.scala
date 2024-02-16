package com.daml.network.sv.store

import cats.Monoid
import cats.syntax.foldable.*
import com.daml.ledger.javaapi.data.*
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.{
  ARC_CoinRules,
  ARC_SvcRules,
}
import com.daml.network.codegen.java.cn.svcrules.{
  ActionRequiringConfirmation,
  SvcRules_ExecuteDefiniteVote,
  SvcRules_VoteRequest_Expire,
  VoteResult,
}
import com.daml.network.environment.ledger.api.{ActiveContract, IncompleteReassignmentEvent}
import com.daml.network.store.TxLogStore
import com.daml.network.sv.history.*
import com.daml.network.util.ExerciseNode
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext

import scala.collection.immutable
import scala.jdk.CollectionConverters.*

class SvcTxLogParser(
    override val loggerFactory: NamedLoggerFactory
) extends TxLogStore.Parser[
      TxLogEntry
    ]
    with NamedLogging {

  import SvcTxLogParser.*

  private def parseTree(tree: TransactionTree, domainId: DomainId, root: TreeEvent)(implicit
      tc: TraceContext
  ): State = {

    root match {
      case exercised: ExercisedEvent =>
        exercised match {
          case SvcRulesExecuteDefiniteVote(node) =>
            State.fromExecuteDefiniteVote(node)
          case SvcRulesVoteRequestExpire(node) =>
            State.fromVoteRequestExpire(node)
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

  override def parseAcs(
      acs: Seq[ActiveContract],
      incompleteOut: Seq[IncompleteReassignmentEvent.Unassign],
      incompleteIn: Seq[IncompleteReassignmentEvent.Assign],
  )(implicit
      tc: TraceContext
  ): Seq[(DomainId, Option[codegen.ContractId[?]], TxLogEntry)] =
    Seq.empty

  override def tryParse(tx: TransactionTree, domain: DomainId)(implicit
      tc: TraceContext
  ): Seq[TxLogEntry] = {
    val ret = parseTrees(tx, domain, tx.getRootEventIds.asScala.toList).entries
    ret
  }

  override def error(offset: String, eventId: String, domainId: DomainId): Option[TxLogEntry] =
    Some(
      ErrorTxLogEntry()
    )

}

object SvcTxLogParser {

  case class State(
      entries: immutable.Queue[TxLogEntry]
  ) {
    def appended(other: State): State = State(
      entries = entries.appendedAll(other.entries)
    )
  }

  object State {

    def fromExecuteDefiniteVote(
        node: ExerciseNode[SvcRules_ExecuteDefiniteVote, VoteResult]
    ): State = {
      State(
        immutable.Queue(
          DefiniteVoteTxLogEntry(
            result = Some(node.result.value)
          )
        )
      )
    }

    def fromVoteRequestExpire(
        node: ExerciseNode[SvcRules_VoteRequest_Expire, VoteResult]
    ): State = {
      State(
        immutable.Queue(
          DefiniteVoteTxLogEntry(
            result = Some(node.result.value)
          )
        )
      )
    }

    def mapActionName(
        action: ActionRequiringConfirmation
    ): String = {
      action match {
        case arcSvcRules: ARC_SvcRules =>
          arcSvcRules.svcAction.getClass.getSimpleName
        case arcCoinRules: ARC_CoinRules =>
          arcCoinRules.coinRulesAction.getClass.getSimpleName
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
