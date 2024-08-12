package com.daml.network.util

import com.daml.ledger.api.v2.participant_offset.ParticipantOffset
import com.daml.ledger.api.v2.ParticipantOffsetOuterClass.ParticipantOffset as ledgerApiParticipantOffset
import com.daml.ledger.javaapi.data.{
  Bool,
  ContractId,
  CreatedEvent,
  DamlEnum,
  DamlGenMap,
  DamlList,
  DamlOptional,
  DamlRecord,
  ExercisedEvent,
  Identifier,
  Int64,
  Numeric,
  Party,
  Text,
  Timestamp,
  TransactionTree,
  Unit,
  Variant,
  ParticipantOffset as javaApiParticipantOffset,
}
import com.daml.network.console.{ScanAppClientReference, SvAppBackendReference}
import com.daml.network.environment.ledger.api.LedgerClient.GetTreeUpdatesResponse
import com.daml.network.environment.ledger.api.ReassignmentEvent.{Assign, Unassign}
import com.daml.network.environment.ledger.api.{
  LedgerClient,
  Reassignment,
  ReassignmentEvent,
  ReassignmentUpdate,
  TransactionTreeUpdate,
}
import com.daml.network.http.v0.definitions
import com.daml.network.integration.tests.SpliceTests.TestCommon
import com.digitalasset.canton.admin.api.client.commands.LedgerApiCommands.UpdateService.{
  AssignedWrapper,
  TransactionTreeWrapper,
  UnassignedWrapper,
}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.{DomainId, PartyId}
import io.circe.Json

import java.time.Instant
import java.util
import java.util.Optional
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import org.scalatest.matchers.dsl.MatcherFactory1
import org.scalatest.matchers.{MatchResult, Matcher}
import org.scalactic.{Equality, Prettifier}

trait UpdateHistoryComparator extends TestCommon {

  def compareHistoryViaScanApi(
      ledgerBegin: ParticipantOffset,
      svAppBackend: SvAppBackendReference,
      scanClient: ScanAppClientReference,
  ) = {
    val participant = svAppBackend.participantClient
    val ledgerEnd = participant.ledger_api.state.end()
    val dsoParty = svAppBackend.getDsoInfo().dsoParty
    val actualUpdates = participant.ledger_api.updates
      .trees(
        partyIds = Set(dsoParty),
        completeAfter = Int.MaxValue,
        beginOffset = ledgerBegin,
        endOffset = Some(ledgerEnd),
        verbose = false,
      )
      .map {
        case TransactionTreeWrapper(protoTree) =>
          LedgerClient.GetTreeUpdatesResponse(
            TransactionTreeUpdate(LedgerClient.lapiTreeToJavaTree(protoTree)),
            DomainId.tryFromString(protoTree.domainId),
          )
        case UnassignedWrapper(protoReassignment, protoUnassignEvent) =>
          GetTreeUpdatesResponse(
            ReassignmentUpdate(Reassignment.fromProto(protoReassignment)),
            DomainId.tryFromString(protoUnassignEvent.source),
          )
        case AssignedWrapper(protoReassignment, protoAssignEvent) =>
          GetTreeUpdatesResponse(
            ReassignmentUpdate(Reassignment.fromProto(protoReassignment)),
            DomainId.tryFromString(protoAssignEvent.target),
          )
      }

    val recordedUpdates = scanClient.getUpdateHistory(
      actualUpdates.size,
      Some(
        (
          0L,
          // Note that we deliberately do not start from ledger begin here since the ledgerBeginSv1 variable above
          // only points at the end after initialization.
          actualUpdates.head.update.recordTime
            .minusMillis(1L)
            .toString, // include the first element, as otherwise it's excluded
        )
      ),
      false,
    )

    recordedUpdates should have length actualUpdates.size.toLong

    val recordedUpdatesByDomainId = recordedUpdates.groupBy {
      case definitions.UpdateHistoryItem.members.UpdateHistoryTransaction(
            definitions.UpdateHistoryTransaction(_, _, _, _, domainId, _, _, _, _)
          ) =>
        domainId
      case definitions.UpdateHistoryItem.members.UpdateHistoryReassignment(
            definitions.UpdateHistoryReassignment(_, _, _, event)
          ) =>
        event match {
          case definitions.UpdateHistoryReassignment.Event.members.UpdateHistoryAssignment(
                definitions.UpdateHistoryAssignment(_, _, targetDomainId, _, _, _, _)
              ) =>
            targetDomainId
          case definitions.UpdateHistoryReassignment.Event.members.UpdateHistoryUnassignment(
                definitions.UpdateHistoryUnassignment(_, sourceDomainId, _, _, _, _, _)
              ) =>
            sourceDomainId
        }
    }

    val actualUpdatesByDomainId = actualUpdates.groupBy {
      case GetTreeUpdatesResponse(_, domainId) => domainId.toProtoPrimitive
    }

    recordedUpdatesByDomainId.keySet should be(actualUpdatesByDomainId.keySet)
    recordedUpdatesByDomainId.keySet.foreach { domainId =>
      val actualForDomain = actualUpdatesByDomainId.get(domainId).value
      val recordedForDomain = recordedUpdatesByDomainId.get(domainId).value
      actualForDomain.zip(recordedForDomain).foreach { case (actual, recorded) =>
        actual should matchUpdateHistory(recorded)
      }
    }
  }

  def matchUpdateHistory(right: Any): MatcherFactory1[Any, Equality] =
    new MatcherFactory1[Any, Equality] {
      def matcher[T <: Any: Equality]: Matcher[T] = {
        new Matcher[T] {
          def apply(left: T): MatchResult = {
            left match {
              case GetTreeUpdatesResponse(TransactionTreeUpdate(tree), _) =>
                compare(tree, right)
              case GetTreeUpdatesResponse(
                    ReassignmentUpdate(reassign),
                    _,
                  ) =>
                compare(reassign, right)
              case _ => fail(s"Unepxected comparison for $left")
            }
          }
          override def toString: String = "equal (" + Prettifier.default(right) + ")"
        }
      }
      override def toString: String = "equal (" + Prettifier.default(right) + ")"
    }

  private def compare(
      left: Reassignment[ReassignmentEvent],
      right: Any,
  ): MatchResult = {
    right match {
      case definitions.UpdateHistoryItem.members.UpdateHistoryReassignment(
            definitions.UpdateHistoryReassignment(
              updateId,
              offset,
              recordTime,
              event,
            )
          ) =>
        compare(
          Seq(left.updateId, left.offset, left.recordTime, left.event),
          Seq(updateId, offset, CantonTimestamp.tryFromInstant(Instant.parse(recordTime)), event),
        )
      case _ => MatchResult(false, s"Left was a reassignment, but right was: $right", "")
    }
  }

  private def compare(
      left: Assign,
      right: definitions.UpdateHistoryReassignment.Event,
  ): MatchResult = {
    right match {
      case definitions.UpdateHistoryReassignment.Event.members.UpdateHistoryAssignment(
            definitions.UpdateHistoryAssignment(
              submitter,
              sourceSynchronizer,
              targetSynchronizer,
              _, // Ignoring MigrationId since ledger API does not include it
              unassignId,
              createdEvent,
              reassignmentCounter,
            )
          ) =>
        compare(
          Seq(
            left.submitter,
            left.source,
            left.target,
            left.unassignId,
            left.createdEvent,
            left.counter,
          ),
          Seq(
            PartyId.tryFromProtoPrimitive(submitter),
            DomainId.tryFromString(sourceSynchronizer),
            DomainId.tryFromString(targetSynchronizer),
            unassignId,
            createdEvent,
            reassignmentCounter,
          ),
        )
      case _ => MatchResult(false, s"Left was an assignment, but right was: $right", "")
    }
  }

  private def compare(
      left: Unassign,
      right: definitions.UpdateHistoryReassignment.Event,
  ): MatchResult = {
    right match {
      case definitions.UpdateHistoryReassignment.Event.members.UpdateHistoryUnassignment(
            definitions.UpdateHistoryUnassignment(
              submitter,
              sourceSynchronizer,
              _, // Ignoring MigrationId since ledger API does not include it
              targetSynchronizer,
              unassignId,
              reassignmentCounter,
              contractId,
            )
          ) =>
        compare(
          Seq(
            left.submitter,
            left.source,
            left.target,
            left.unassignId,
            left.counter,
            left.contractId.contractId,
          ),
          Seq(
            PartyId.tryFromProtoPrimitive(submitter),
            DomainId.tryFromString(sourceSynchronizer),
            DomainId.tryFromString(targetSynchronizer),
            unassignId,
            reassignmentCounter,
            contractId,
          ),
        )
      case _ => MatchResult(false, s"Left was an unassignment, but right was: $right", "")
    }
  }

  private def compare(left: TransactionTree, right: Any): MatchResult = {
    right match {
      // Ignoring CommandId. They're not in the openAPI spec
      //   since command ids are not properly shared across nodes so
      //   they're not reliably usable for backfilling
      case definitions.UpdateHistoryItem.members.UpdateHistoryTransaction(
            definitions.UpdateHistoryTransaction(
              updateId,
              _, // Ignoring MigrationId since ledger API does not include it.
              workflowId,
              recordTime,
              synchronizerId,
              effectiveAt,
              offset,
              rootEventIds,
              eventsById,
            )
          ) =>
        compare(
          Seq(
            left.getUpdateId,
            left.getWorkflowId,
            left.getRecordTime,
            left.getDomainId,
            left.getEffectiveAt,
            left.getOffset,
            left.getRootEventIds.asScala,
            left.getEventsById.asScala.to(Map),
          ),
          Seq(
            updateId,
            workflowId,
            java.time.Instant.parse(recordTime),
            synchronizerId,
            java.time.Instant.parse(effectiveAt),
            offset,
            rootEventIds,
            eventsById,
          ),
        )
      case _ => MatchResult(false, s"Could not parse $right as a UpdateHistoryTransaction", "")
    }
  }

  private def compare[T, S, R](left: Map[T, S], right: Map[T, R]): MatchResult = {
    if (left.size != right.size) {
      MatchResult(false, s"EventByIDs $left and $right have different sizes", "")
    } else {
      val missing = left.keys.filter(!right.contains(_))
      if (!missing.isEmpty) {
        MatchResult(false, s"Elements $missing defined in $left but are missing from $right", "")
      } else {
        val keys = left.keys
        compare(keys.map(left.get(_)), keys.map(right.get(_)))
      }
    }
  }

  private def compare(left: CreatedEvent, right: definitions.CreatedEvent): MatchResult = {
    right match {
      // Skipping eventType
      case definitions.CreatedEvent(
            _,
            eventId,
            contractId,
            templateId,
            packageName,
            createArguments,
            createdAt,
            signatories,
            observers,
          ) =>
        compare(
          Seq(
            left.getEventId,
            left.getContractId,
            left.getTemplateId,
            left.getPackageName,
            left.getArguments,
            left.getCreatedAt,
            left.getSignatories.asScala.to(Set),
            left.getObservers.asScala.to(Set),
          ),
          Seq(
            eventId,
            contractId,
            parseTemplateId(templateId),
            packageName,
            parseCreateArguments(createArguments, parseTemplateId(templateId)),
            createdAt.toInstant,
            signatories.toSet,
            observers.toSet,
          ),
        )
    }
  }

  private def compare(
      left: CreatedEvent,
      right: definitions.TreeEvent.members.CreatedEvent,
  ): MatchResult = {
    right match {
      case definitions.TreeEvent.members.CreatedEvent(createdEvent) => compare(left, createdEvent)
    }
  }

  private def compare(
      left: ExercisedEvent,
      right: definitions.TreeEvent.members.ExercisedEvent,
  ): MatchResult = {
    right match {
      // Skipping eventType
      case definitions.TreeEvent.members.ExercisedEvent(
            definitions.ExercisedEvent(
              _,
              eventId,
              contractId,
              templateId,
              packageName,
              choice,
              choiceArgument,
              childEventIds,
              exerciseResult,
              consuming,
              actingParties,
              interfaceId,
            )
          ) =>
        compare(
          Seq(
            left.getEventId,
            left.getContractId,
            left.getTemplateId,
            left.getPackageName,
            left.getChoice,
            left.getChoiceArgument,
            left.getChildEventIds,
            left.getExerciseResult,
            left.isConsuming,
            left.getActingParties,
            left.getInterfaceId.toScala,
          ),
          Seq(
            eventId,
            contractId,
            parseTemplateId(templateId),
            packageName,
            choice,
            parseChoiceArgument(choiceArgument, parseTemplateId(templateId), choice),
            childEventIds.asJava,
            parseExerciseResult(exerciseResult, parseTemplateId(templateId), choice),
            consuming,
            actingParties.asJava,
            interfaceId.map(parseTemplateId(_)),
          ),
        )
    }
  }

  private def parseCreateArguments(json: Json, templateId: Identifier) =
    ValueJsonCodecCodegen.deserializableContractPayload(templateId, json.toString()).value

  private def parseChoiceArgument(json: Json, templateId: Identifier, choice: String) =
    ValueJsonCodecCodegen.deserializeChoiceArgument(templateId, choice, json.toString()).value

  private def parseExerciseResult(json: Json, templateId: Identifier, choice: String) =
    ValueJsonCodecCodegen.deserializeChoiceResult(templateId, choice, json.toString()).value

  private def parseTemplateId(templateId: String) = {
    val pattern = "(.*):(.*):(.*)".r
    val split = pattern.findFirstMatchIn(templateId).value
    val (packageId, moduleName, entityName) = (split.group(1), split.group(2), split.group(3))
    new Identifier(packageId, moduleName, entityName)
  }

  private def compare(left: DamlRecord, right: DamlRecord): MatchResult = {
    compare(Seq(left.getRecordId, left.getFields), Seq(right.getRecordId, right.getFields))
  }

  private def compare(left: DamlRecord.Field, right: DamlRecord.Field): MatchResult = {
    compare(left.getValue, right.getValue)
  }

  private def compare(left: DamlList, right: DamlList): MatchResult = {
    val l = left.stream().toList.asScala
    val r = right.stream().toList.asScala
    compare(l, r)
  }

  private def compare(left: DamlOptional, right: DamlOptional): MatchResult = {
    (left.getValue.toScala, right.getValue.toScala) match {
      case (None, None) => MatchResult(true, "", "")
      case (Some(l), Some(r)) => compare(l, r)
      case _ => MatchResult(false, s"DamlOptional $left was not equal to DamlOptional $right", "")
    }
  }

  private def compare(left: Variant, right: Variant): MatchResult = {
    compare(
      Seq(left.getVariantId, left.getConstructor, left.getValue),
      Seq(right.getVariantId, right.getConstructor, right.getValue),
    )
  }

  private def compare[T, S](left: Seq[T], right: Seq[S]): MatchResult = {
    if (left.size != right.size) {
      MatchResult(
        false,
        s"Sequences had a different number of elements (${left.size} vs ${right.size}): Left was $left, Right was $right",
        "",
      )
    } else {
      left
        .zip(right)
        .map { case (l, r) => compare(l, r) }
        .filter(!_.matches)
        .headOption
        .getOrElse(MatchResult(true, "", ""))
    }
  }

  private def compare[T, S](left: Set[T], right: Set[S]): MatchResult = {
    if (left.size != right.size) {
      MatchResult(false, s"Sets ${left} and ${right} have a different number of elements", "")
    } else {
      val notFound = left.filter(l => {
        right.filter(compare(l, _).matches).size != 1
      })
      if (notFound.isEmpty) {
        MatchResult(true, "", "")
      } else {
        MatchResult(false, s"Elements from left set not found in right: $notFound", "")
      }
    }
  }

  private def compare[T, S](left: Option[T], right: Option[S]): MatchResult = {
    (left, right) match {
      case (None, None) => MatchResult(true, "", "")
      case (Some(l), Some(r)) => compare(l, r)
      case _ =>
        MatchResult(
          false,
          s"One side was None, but the other was not. Left: $left, Right: $right",
          "",
        )
    }
  }

  private def equalityCompare[T, S](left: T, right: S): MatchResult = if (left == right) {
    MatchResult(true, "", "")
  } else {
    MatchResult(false, s"$left was not equal to $right", "")
  }

  private def compare[T, S](left: T, right: S): MatchResult = {
    (left, right) match {
      case (l: DamlRecord, r: DamlRecord) => compare(l, r)
      case (l: DamlRecord.Field, r: DamlRecord.Field) => compare(l, r)
      case (l: DamlList, r: DamlList) => compare(l, r)
      case (l: DamlOptional, r: DamlOptional) => compare(l, r)
      case (l: DamlEnum, r: DamlEnum) => equalityCompare(l, r)

      case (l: DamlGenMap, r: DamlGenMap) => compare(l.stream().toList, r.stream().toList)
      case (l: Variant, r: Variant) => compare(l, r)
      case (l: Map[Any @unchecked, Any @unchecked], r: Map[Any @unchecked, Any @unchecked]) =>
        compare(l, r)
      case (l: Set[_], r: Set[_]) => compare(l, r)
      case (l: Option[_], r: Option[_]) => compare(l, r)
      case (l: Optional[_], r: Optional[_]) => compare(l.toScala, r.toScala)
      case (l: Optional[_], r: Option[_]) => compare(l.toScala, r)
      case (l: util.AbstractList[_], r: util.AbstractList[_]) =>
        compare(l.stream().toList.asScala, r.stream().toList.asScala)
      case (l: util.AbstractList[_], r: mutable.AbstractSeq[_]) => compare(l, r.toList)
      case (l: mutable.AbstractSeq[_], r: util.AbstractList[_]) => compare(l.toList, r)
      case (l: mutable.AbstractBuffer[_], r: mutable.AbstractBuffer[_]) =>
        compare(l.toList, r.toList)
      case (l: mutable.AbstractBuffer[_], r: mutable.AbstractSeq[_]) => compare(l.toList, r.toList)
      case (l: mutable.Buffer[_], r: Vector[_]) => compare(l.toList, r.toList)
      case (l: util.List[_], r: util.List[_]) =>
        compare(l.stream().toList.asScala, r.stream().toList.asScala)
      case (l: util.Map.Entry[_, _], r: util.Map.Entry[_, _]) =>
        compare(Seq(l.getKey, l.getValue), Seq(r.getKey, r.getValue))
      case (l: CreatedEvent, r: definitions.CreatedEvent) => compare(l, r)
      case (l: CreatedEvent, r: definitions.TreeEvent.members.CreatedEvent) => compare(l, r)
      case (_: CreatedEvent, _: definitions.TreeEvent.members.ExercisedEvent) =>
        MatchResult(false, "Left was a createdEvent, but Right was an exercisedEvent", "")
      case (l: ExercisedEvent, r: definitions.TreeEvent.members.ExercisedEvent) => compare(l, r)
      case (_: ExercisedEvent, _: definitions.TreeEvent.members.CreatedEvent) =>
        MatchResult(false, "Left was an exercisedEvent, but Right was a createdEvent", "")
      case (l: Assign, r: definitions.UpdateHistoryReassignment.Event) => compare(l, r)
      case (l: Unassign, r: definitions.UpdateHistoryReassignment.Event) => compare(l, r)
      case (l: javaApiParticipantOffset, right: String) => compare(l.toProto, right)
      case (l: ledgerApiParticipantOffset, right: String) => compare(l.getAbsolute, right)
      // We explicitly list types that we want compared by ==, in order to easily catch unexpected comparisons
      case (l: Boolean, r: Boolean) => equalityCompare(l, r)
      case (l: String, r: String) => equalityCompare(l, r)
      case (l: Long, r: Long) => equalityCompare(l, r)
      case (l: Party, r: Party) => equalityCompare(l, r)
      case (l: PartyId, r: PartyId) => equalityCompare(l, r)
      case (l: DomainId, r: DomainId) => equalityCompare(l, r)
      case (l: Instant, r: Instant) => equalityCompare(l, r)
      case (l: Identifier, r: Identifier) => equalityCompare(l, r)
      case (l: ContractId, r: ContractId) => equalityCompare(l, r)
      case (l: Numeric, r: Numeric) => equalityCompare(l, r)
      case (l: Int64, r: Int64) => equalityCompare(l, r)
      case (l: Bool, r: Bool) => equalityCompare(l, r)
      case (l: Unit, r: Unit) => equalityCompare(l, r)
      case (l: Text, r: Text) => equalityCompare(l, r)
      case (l: CantonTimestamp, r: CantonTimestamp) => equalityCompare(l, r)
      case (l: Timestamp, r: Timestamp) => equalityCompare(l, r)

      case (_: DamlRecord, _) => MatchResult(false, "Left was DamlRecord but right was not", "")
      case (_, _: DamlRecord) => MatchResult(false, "Right was DamlRecord but left was not", "")
      case (_: DamlEnum, _) => MatchResult(false, "Left was DamlEnum but right was not", "")
      case (_, _: DamlEnum) => MatchResult(false, "Right was DamlEnum but left was not", "")
      case (_: Party, _) => MatchResult(false, "Left was party, right was not", "")
      case (_, _: Party) => MatchResult(false, "Right was party, left was not", "")

      case _ =>
        fail(s"Comparing ${left.getClass} to ${right.getClass} is not currently implemented")
    }
  }

}
