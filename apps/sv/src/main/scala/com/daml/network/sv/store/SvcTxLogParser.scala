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
import com.digitalasset.canton.config.CantonRequireTypes.String3
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext

import scala.collection.immutable
import scala.jdk.CollectionConverters.*

class SvcTxLogParser(
    override val loggerFactory: NamedLoggerFactory
) extends TxLogStore.Parser[
      SvcTxLogParser.TxLogIndexRecord,
      SvcTxLogParser.TxLogEntry,
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
            State.fromExecuteDefiniteVote(tree, root, domainId, node)
          case SvcRulesVoteRequestExpire(node) =>
            State.fromVoteRequestExpire(tree, root, domainId, node)
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
  ): Seq[(DomainId, SvcTxLogParser.TxLogEntry)] =
    Seq.empty

  override def tryParse(tx: TransactionTree, domain: DomainId)(implicit
      tc: TraceContext
  ): Seq[SvcTxLogParser.TxLogEntry] = {
    val ret = parseTrees(tx, domain, tx.getRootEventIds.asScala.toList).entries
    ret
  }

  override def error(offset: String, eventId: String, domainId: DomainId): Option[TxLogEntry] =
    Some(
      TxLogEntry.ErrorTxLogEntry(
        indexRecord = TxLogIndexRecord.ErrorIndexRecord(
          offset,
          eventId,
          domainId,
        )
      )
    )

}

object SvcTxLogParser {

  sealed trait TxLogIndexRecord extends TxLogStore.IndexRecord {
    val companion: TxLogIndexRecordCompanion
  }

  sealed trait TxLogIndexRecordCompanion {
    val shortType: String

    def dbType: String3 = String3.tryCreate(shortType)
  }

  object TxLogIndexRecord {

    final case class ErrorIndexRecord(
        offset: String,
        eventId: String,
        domainId: DomainId,
    ) extends TxLogIndexRecord {
      override def optOffset = Some(offset)

      override def acsContractId: Option[codegen.ContractId[_]] = None

      override val companion: TxLogIndexRecordCompanion = ErrorIndexRecord
    }

    object ErrorIndexRecord extends TxLogIndexRecordCompanion {
      override val shortType: String = "err"
    }

    final case class DefiniteVoteIndexRecord(
        offset: String,
        eventId: String,
        domainId: DomainId,
        actionName: String,
        executed: Boolean,
        requester: String,
        effectiveAt: String,
        votedAt: String,
    ) extends TxLogIndexRecord {

      override def optOffset = Some(offset)

      override def acsContractId: Option[codegen.ContractId[_]] = None

      override val companion: TxLogIndexRecordCompanion = DefiniteVoteIndexRecord
    }

    object DefiniteVoteIndexRecord extends TxLogIndexRecordCompanion {
      override val shortType: String = "dv"
    }

  }

  sealed trait TxLogEntry extends TxLogStore.Entry[TxLogIndexRecord] {}

  object TxLogEntry {

    final case class ErrorTxLogEntry(indexRecord: TxLogIndexRecord.ErrorIndexRecord)
        extends TxLogEntry {}

    final case class DefiniteVoteTxLogEntry(
        indexRecord: TxLogIndexRecord,
        expired: Boolean,
        rejectedBy: List[String],
        acceptedBy: List[String],
        action: ActionRequiringConfirmation,
    ) extends TxLogEntry {}

  }

  case class State(
      entries: immutable.Queue[TxLogEntry]
  ) {
    def appended(other: State): State = State(
      entries = entries.appendedAll(other.entries)
    )
  }

  object State {

    def fromExecuteDefiniteVote(
        tree: TransactionTree,
        root: TreeEvent,
        domainId: DomainId,
        node: ExerciseNode[SvcRules_ExecuteDefiniteVote, VoteResult],
    ): State = {
      val action = mapActionName(node.result.value.action)
      State(
        immutable.Queue(
          TxLogEntry.DefiniteVoteTxLogEntry(
            indexRecord = TxLogIndexRecord.DefiniteVoteIndexRecord(
              tree.getOffset(),
              root.getEventId(),
              domainId,
              action,
              node.result.value.executed,
              node.result.value.requester,
              node.result.value.effectiveAt.toString,
              node.result.value.votedAt.toString,
            ),
            action = node.result.value.action,
            expired = node.result.value.expired,
            rejectedBy = node.result.value.rejectedBy.asScala.toList,
            acceptedBy = node.result.value.acceptedBy.asScala.toList,
          )
        )
      )
    }

    def fromVoteRequestExpire(
        tree: TransactionTree,
        root: TreeEvent,
        domainId: DomainId,
        node: ExerciseNode[SvcRules_VoteRequest_Expire, VoteResult],
    ): State = {
      val action = mapActionName(node.result.value.action)
      State(
        immutable.Queue(
          TxLogEntry.DefiniteVoteTxLogEntry(
            indexRecord = TxLogIndexRecord.DefiniteVoteIndexRecord(
              tree.getOffset(),
              root.getEventId(),
              domainId,
              action,
              node.result.value.executed,
              node.result.value.requester,
              node.result.value.effectiveAt.toString,
              node.result.value.votedAt.toString,
            ),
            action = node.result.value.action,
            expired = node.result.value.expired,
            rejectedBy = node.result.value.rejectedBy.asScala.toList,
            acceptedBy = node.result.value.acceptedBy.asScala.toList,
          )
        )
      )
    }

    private def mapActionName(
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
