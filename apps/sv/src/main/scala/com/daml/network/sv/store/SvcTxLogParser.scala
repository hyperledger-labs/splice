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
import com.daml.network.store.db.AcsJdbcTypes
import com.daml.network.store.{StoreErrors, TxLogStore}
import com.daml.network.sv.history.*
import com.daml.network.util.{ExerciseNode, TemplateJsonDecoder}
import com.digitalasset.canton.config.CantonRequireTypes.String3
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import spray.json.{
  DefaultJsonProtocol,
  DeserializationException,
  JsString,
  JsValue,
  JsonFormat,
  RootJsonFormat,
  deserializationError,
}

import scala.collection.immutable
import scala.jdk.CollectionConverters.*

class SvcTxLogParser(
    override val loggerFactory: NamedLoggerFactory
) extends TxLogStore.Parser[
      SvcTxLogParser.TxLogEntry
    ]
    with NamedLogging {

  import SvcTxLogParser.*

  private def parseTree(tree: TransactionTreeV2, domainId: DomainId, root: TreeEvent)(implicit
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

  private def parseTrees(tree: TransactionTreeV2, domainId: DomainId, rootsEventIds: List[String])(
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
  ): Seq[(DomainId, Option[codegen.ContractId[?]], SvcTxLogParser.TxLogEntry)] =
    Seq.empty

  override def tryParse(tx: TransactionTreeV2, domain: DomainId)(implicit
      tc: TraceContext
  ): Seq[SvcTxLogParser.TxLogEntry] = {
    val ret = parseTrees(tx, domain, tx.getRootEventIds.asScala.toList).entries
    ret
  }

  override def error(offset: String, eventId: String, domainId: DomainId): Option[TxLogEntry] =
    Some(
      TxLogEntry.ErrorTxLogEntry()
    )

}

object SvcTxLogParser {

  sealed trait TxLogEntry

  object TxLogEntry {

    // Note: the spray-json reader depends on a TemplateJsonDecoder
    // to decode the ActionRequiringConfirmation payload.
    case class Codec(templateJsonDecoder: TemplateJsonDecoder) {
      def encode(entry: TxLogEntry): (String3, JsValue) = {
        import spray.json.*
        import JsonProtocol.*
        entry match {
          case e: ErrorTxLogEntry => (ErrorTxLogEntry.dbType, e.toJson)
          case e: DefiniteVoteTxLogEntry => (DefiniteVoteTxLogEntry.dbType, e.toJson)

        }
      }
      def decode(dbType: String3, json: JsValue): TxLogEntry = {
        import JsonProtocol.*
        try {
          dbType match {
            case ErrorTxLogEntry.dbType => json.convertTo[ErrorTxLogEntry]
            case DefiniteVoteTxLogEntry.dbType =>
              json.convertTo[DefiniteVoteTxLogEntry]
            case _ => throw txDecodingFailed()
          }
        } catch {
          case _: DeserializationException => throw txDecodingFailed()
        }
      }

      object JsonProtocol extends DefaultJsonProtocol with StoreErrors {
        implicit val domainIdFormat: JsonFormat[DomainId] =
          new JsonFormat[DomainId] {
            override def write(obj: DomainId) =
              JsString(obj.uid.toProtoPrimitive)

            override def read(json: JsValue) = json match {
              case JsString(s) =>
                DomainId
                  .fromProtoPrimitive(s, "")
                  .fold(f => deserializationError(f.message), identity)
              case _ => deserializationError("DomainId must be a string")
            }
          }

        implicit val arcFormat: RootJsonFormat[ActionRequiringConfirmation] =
          new RootJsonFormat[ActionRequiringConfirmation] {
            override def read(json: JsValue): ActionRequiringConfirmation = {
              val circeJson =
                io.circe.parser.parse(json.toString).getOrElse(throw txDecodingFailed())
              templateJsonDecoder.decodeValue(
                ActionRequiringConfirmation.valueDecoder(),
                ActionRequiringConfirmation._packageId,
                "CN.SvcRules",
                "ActionRequiringConfirmation",
              )(circeJson)
            }

            override def write(obj: ActionRequiringConfirmation): JsValue =
              AcsJdbcTypes.payloadJsonFromValue(obj.toValue)
          }
        implicit val errorEntryFormat: RootJsonFormat[ErrorTxLogEntry] =
          jsonFormat0(() => ErrorTxLogEntry.apply())
        implicit val definiteVoteEntryFormat: RootJsonFormat[DefiniteVoteTxLogEntry] =
          jsonFormat9(DefiniteVoteTxLogEntry.apply)

      }
    }

    final case class ErrorTxLogEntry() extends TxLogEntry {}

    object ErrorTxLogEntry {
      val dbType: String3 = String3.tryCreate("err")
    }

    final case class DefiniteVoteTxLogEntry(
        actionName: String,
        executed: Boolean,
        requester: String,
        effectiveAt: String,
        votedAt: String,
        expired: Boolean,
        rejectedBy: List[String],
        acceptedBy: List[String],
        action: ActionRequiringConfirmation,
    ) extends TxLogEntry

    object DefiniteVoteTxLogEntry {
      val dbType: String3 = String3.tryCreate("dv")
    }

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
        node: ExerciseNode[SvcRules_ExecuteDefiniteVote, VoteResult]
    ): State = {
      val action = mapActionName(node.result.value.action)
      State(
        immutable.Queue(
          TxLogEntry.DefiniteVoteTxLogEntry(
            actionName = action,
            executed = node.result.value.executed,
            requester = node.result.value.requester,
            effectiveAt = node.result.value.effectiveAt.toString,
            votedAt = node.result.value.votedAt.toString,
            action = node.result.value.action,
            expired = node.result.value.expired,
            rejectedBy = node.result.value.rejectedBy.asScala.toList,
            acceptedBy = node.result.value.acceptedBy.asScala.toList,
          )
        )
      )
    }

    def fromVoteRequestExpire(
        node: ExerciseNode[SvcRules_VoteRequest_Expire, VoteResult]
    ): State = {
      val action = mapActionName(node.result.value.action)
      State(
        immutable.Queue(
          TxLogEntry.DefiniteVoteTxLogEntry(
            actionName = action,
            executed = node.result.value.executed,
            requester = node.result.value.requester,
            effectiveAt = node.result.value.effectiveAt.toString,
            votedAt = node.result.value.votedAt.toString,
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
