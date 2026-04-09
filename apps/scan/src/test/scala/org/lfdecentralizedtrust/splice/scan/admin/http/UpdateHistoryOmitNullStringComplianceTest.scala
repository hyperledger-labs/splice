// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.http

import com.digitalasset.canton.BaseTest
import org.lfdecentralizedtrust.splice.http.OmitNullString
import org.lfdecentralizedtrust.splice.http.v0.definitions.{
  AppActivityRecord,
  CreatedEvent,
  EnvelopeTrafficSummary,
  EventHistoryAppActivityRecords,
  EventHistoryItem,
  EventHistoryTrafficSummary,
  EventHistoryVerdict,
  ExercisedEvent,
  Quorum,
  TransactionView,
  TransactionViews,
  UpdateHistoryAssignment,
  UpdateHistoryReassignment,
  UpdateHistoryTransaction,
  UpdateHistoryTransactionV2,
  UpdateHistoryTransactionV2WithHash,
  UpdateHistoryUnassignment,
}
import org.scalatest.wordspec.AnyWordSpec

import scala.collection.immutable.SortedMap
import scala.reflect.runtime.universe.*

/** Ensures that types participating in BFT consensus do not introduce optional fields
  * that would emit `null` in JSON, breaking cross-SV consistency.
  *
  * Update history types are serialized to JSON and compared across SVs for BFT consensus.
  * When a new optional field is added, SVs that have the field emit `"field": null` while
  * SVs that don't have the field omit the key entirely — causing JSON divergence.
  *
  * The only permitted optional type at this time is `Option[OmitNullString]`, which is handled by
  * [[ScanJsonSupport]] to omit the key from JSON when `None`.
  *
  * Any other `Option[_]` field on these types will cause this test to fail, guiding the
  * developer to either make the field required or use `OmitNullString` with the necessary
  * wiring in `ScanJsonSupport`.
  */
class UpdateHistoryOmitNullStringComplianceTest extends AnyWordSpec with BaseTest {

  /** Test-only type used by the sanity check to verify that `unsafeOptionFields` catches
    * `Option[_]` of any type parameter, not just `Option[String]`.
    */
  private case class HasOptionInt(x: String, y: Option[Int] = None)

  /** Returns all `Option[_]` fields that are NOT `Option[OmitNullString]`. */
  private def unsafeOptionFields[A: TypeTag]: List[String] = {
    val tpe = typeOf[A]
    val constructor = tpe.decls
      .collectFirst {
        case m: MethodSymbol if m.isPrimaryConstructor => m
      }
      .getOrElse(fail(s"No primary constructor found for ${tpe.typeSymbol.name}"))

    val omitNullStringOption = typeOf[Option[OmitNullString]]

    constructor.paramLists.flatten.collect {
      case param
          if param.typeSignature.typeConstructor =:= typeOf[Option[Any]].typeConstructor &&
            !(param.typeSignature =:= omitNullStringOption) =>
        param.name.toString
    }
  }

  private def omitNullStringFields[A: TypeTag]: List[String] = {
    val tpe = typeOf[A]
    val constructor = tpe.decls
      .collectFirst {
        case m: MethodSymbol if m.isPrimaryConstructor => m
      }
      .getOrElse(fail(s"No primary constructor found for ${tpe.typeSymbol.name}"))

    constructor.paramLists.flatten.collect {
      case param if param.typeSignature =:= typeOf[Option[OmitNullString]] =>
        param.name.toString
    }
  }

  /** Converts a camelCase Scala field name to the snake_case JSON key that guardrail generates. */
  private def toSnakeCase(name: String): String =
    name.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase

  /** Pre-existing `Option[_]` fields that are already part of the BFT consensus schema across
    * all SVs.  These were present before the `OmitNullString` mechanism was introduced and are
    * safe because every SV already knows about them.
    *
    * Do NOT add new entries here — new optional fields must use `Option[OmitNullString]`.
    */
  private val allowedLegacyOptionFields: Set[String] = Set(
    "ExercisedEvent.interfaceId",
    "EventHistoryItem.update",
    "EventHistoryItem.verdict",
    "EventHistoryItem.trafficSummary",
    "EventHistoryItem.appActivityRecords",
  )

  /** All types that participate in BFT consensus via the update history JSON encoding. */
  private def checkNoUnsafeOptions[A: TypeTag](typeName: String): List[String] =
    unsafeOptionFields[A]
      .map(f => s"$typeName.$f")
      .filterNot(allowedLegacyOptionFields.contains)

  "BFT consensus update history types" should {

    "detect Option fields via reflection (sanity check)" in {
      // Verify that our reflection helpers actually find known fields.
      // If this fails, the other tests would silently pass with empty lists.
      omitNullStringFields[UpdateHistoryTransaction] should contain("externalTransactionHash")
      omitNullStringFields[UpdateHistoryTransactionV2] should contain("externalTransactionHash")
      unsafeOptionFields[ExercisedEvent] should contain("interfaceId")

      // Verify that unsafeOptionFields catches Option[_] of any type parameter, not just String
      unsafeOptionFields[HasOptionInt] should contain("y")
    }

    "not have any Option[_] fields unless they are Option[OmitNullString]" in {
      // NOTE: each type is checked individually, not recursively. If you add a new type to the
      // update history schema, add a corresponding `checkNoUnsafeOptions` line here.
      val violations =
        checkNoUnsafeOptions[UpdateHistoryTransaction]("UpdateHistoryTransaction") ++
          checkNoUnsafeOptions[UpdateHistoryTransactionV2]("UpdateHistoryTransactionV2") ++
          checkNoUnsafeOptions[UpdateHistoryTransactionV2WithHash](
            "UpdateHistoryTransactionV2WithHash"
          ) ++
          checkNoUnsafeOptions[UpdateHistoryReassignment]("UpdateHistoryReassignment") ++
          checkNoUnsafeOptions[UpdateHistoryAssignment]("UpdateHistoryAssignment") ++
          checkNoUnsafeOptions[UpdateHistoryUnassignment]("UpdateHistoryUnassignment") ++
          checkNoUnsafeOptions[CreatedEvent]("CreatedEvent") ++
          checkNoUnsafeOptions[ExercisedEvent]("ExercisedEvent") ++
          checkNoUnsafeOptions[EventHistoryItem]("EventHistoryItem") ++
          checkNoUnsafeOptions[EventHistoryVerdict]("EventHistoryVerdict") ++
          checkNoUnsafeOptions[EventHistoryTrafficSummary]("EventHistoryTrafficSummary") ++
          checkNoUnsafeOptions[EventHistoryAppActivityRecords]("EventHistoryAppActivityRecords") ++
          checkNoUnsafeOptions[TransactionViews]("TransactionViews") ++
          checkNoUnsafeOptions[TransactionView]("TransactionView") ++
          checkNoUnsafeOptions[Quorum]("Quorum") ++
          checkNoUnsafeOptions[EnvelopeTrafficSummary]("EnvelopeTrafficSummary") ++
          checkNoUnsafeOptions[AppActivityRecord]("AppActivityRecord")

      if (violations.nonEmpty) {
        fail(
          s"The following fields use Option[_] but are not Option[OmitNullString]. " +
            s"Optional fields on BFT-consensus types will emit null in JSON, breaking " +
            s"cross-SV consistency. Either make the field required, or use " +
            s"Option[OmitNullString] and wire it into ScanJsonSupport:\n" +
            violations.mkString("  - ", "\n  - ", "")
        )
      }
    }

    "omit every Option[OmitNullString] field from JSON when None via ScanJsonSupport" in {
      import ScanJsonSupport.*

      // Encode instances with all OmitNullString fields set to None, and verify the
      // corresponding JSON keys are absent.  This catches any OmitNullString field
      // that was added to the case class but not wired into ScanJsonSupport.

      val txV0Json = io.circe
        .Encoder[UpdateHistoryTransaction]
        .apply(
          UpdateHistoryTransaction(
            updateId = "",
            migrationId = 0L,
            workflowId = "",
            recordTime = "",
            synchronizerId = "",
            effectiveAt = "",
            offset = "",
          )
        )

      val txV2Json = io.circe
        .Encoder[UpdateHistoryTransactionV2]
        .apply(
          UpdateHistoryTransactionV2(
            updateId = "",
            migrationId = 0L,
            workflowId = "",
            recordTime = "",
            synchronizerId = "",
            effectiveAt = "",
            eventsById = SortedMap.empty,
          )
        )

      val txV2WithHashJson = io.circe
        .Encoder[UpdateHistoryTransactionV2WithHash]
        .apply(
          UpdateHistoryTransactionV2WithHash(
            updateId = "",
            migrationId = 0L,
            workflowId = "",
            recordTime = "",
            synchronizerId = "",
            effectiveAt = "",
            eventsById = SortedMap.empty,
            externalTransactionHash = OmitNullString(""),
          )
        )

      val unhandled = (
        omitNullStringFields[UpdateHistoryTransaction].map { field =>
          val key = toSnakeCase(field)
          val present = txV0Json.hcursor.downField(key).focus.isDefined
          (s"UpdateHistoryTransaction.$field (JSON key: $key)", present)
        } ++
          omitNullStringFields[UpdateHistoryTransactionV2].map { field =>
            val key = toSnakeCase(field)
            val present = txV2Json.hcursor.downField(key).focus.isDefined
            (s"UpdateHistoryTransactionV2.$field (JSON key: $key)", present)
          } ++
          omitNullStringFields[UpdateHistoryTransactionV2WithHash].map { field =>
            val key = toSnakeCase(field)
            val present = txV2WithHashJson.hcursor.downField(key).focus.isDefined
            (s"UpdateHistoryTransactionV2WithHash.$field (JSON key: $key)", present)
          }
      ).collect { case (desc, true) => desc }

      if (unhandled.nonEmpty) {
        fail(
          s"The following Option[OmitNullString] fields are still present as null in JSON " +
            s"when None. They must be wired into ScanJsonSupport.omitWhenNone:\n" +
            unhandled.mkString("  - ", "\n  - ", "")
        )
      }
    }
  }
}
