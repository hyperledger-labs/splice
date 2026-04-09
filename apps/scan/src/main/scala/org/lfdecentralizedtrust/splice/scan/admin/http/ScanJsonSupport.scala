// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.http

import io.circe.Encoder
import io.circe.syntax.*
import org.lfdecentralizedtrust.splice.http.OmitNullString
import org.lfdecentralizedtrust.splice.http.v0.definitions.{
  EventHistoryItem,
  GetImportUpdatesResponse,
  GetUpdatesBeforeResponse,
  UpdateHistoryItem,
  UpdateHistoryItemV2,
  UpdateHistoryReassignment,
  UpdateHistoryResponse,
  UpdateHistoryResponseV2,
  UpdateHistoryTransaction,
  UpdateHistoryTransactionV2,
}

/** Provides implicit circe `Encoder` instances that omit `Option[OmitNullString]` fields when
  * `None`.
  *
  * The `external_transaction_hash` field uses the
  * [[org.lfdecentralizedtrust.splice.http.OmitNullString]] type in the OpenAPI spec (via
  * `x-scala-type`), making it explicit in both the spec and the generated Scala code which fields
  * receive this treatment.
  *
  * Because guardrail-generated `Encoder.AsObject` instances are compiled in the definitions files
  * (where custom imports are not in scope), we must re-define encoders for the types that contain
  * `OmitNullString` fields '''and''' for the `oneOf` wrapper types whose generated encoders
  * hard-wire the companion-object encoders at compile time.
  *
  * This object is imported by guardrail-generated route code via the `imports` configuration in
  * `build.sbt`, so these implicits shadow the companion-object ones in the server routes.  For
  * other call sites (e.g. bulk storage) an explicit import of `ScanJsonSupport.*` is required.
  */
object ScanJsonSupport {

  /** Wraps a guardrail-generated `Encoder.AsObject` to omit `Option[OmitNullString]` fields when
    * `None`.  The field to omit is identified by its JSON key name, but the removal is only
    * triggered when value equals `None` and of type `OmitNullString`.
    */
  private def omitWhenNone[A](
      underlying: Encoder.AsObject[A],
      jsonKey: String,
      accessor: A => Option[OmitNullString],
  ): Encoder.AsObject[A] =
    Encoder.AsObject.instance[A] { a =>
      val obj = underlying.encodeObject(a)
      if (accessor(a).isEmpty) obj.remove(jsonKey) else obj
    }

  implicit val encodeUpdateHistoryTransaction: Encoder.AsObject[UpdateHistoryTransaction] =
    omitWhenNone(
      UpdateHistoryTransaction.encodeUpdateHistoryTransaction,
      "external_transaction_hash",
      _.externalTransactionHash,
    )

  implicit val encodeUpdateHistoryTransactionV2: Encoder.AsObject[UpdateHistoryTransactionV2] =
    omitWhenNone(
      UpdateHistoryTransactionV2.encodeUpdateHistoryTransactionV2,
      "external_transaction_hash",
      _.externalTransactionHash,
    )

  // The guardrail-generated encoders for these sealed traits call `member.asJson` which resolves
  // the companion-object encoder at compile time.  We re-define them here so they pick up our
  // custom transaction encoders above.

  implicit val encodeUpdateHistoryItem: Encoder[UpdateHistoryItem] =
    Encoder.instance {
      case UpdateHistoryItem.members.UpdateHistoryTransaction(member) =>
        encodeUpdateHistoryTransaction(member)
      case UpdateHistoryItem.members.UpdateHistoryReassignment(member) =>
        Encoder[UpdateHistoryReassignment].apply(member)
    }

  implicit val encodeUpdateHistoryItemV2: Encoder[UpdateHistoryItemV2] =
    Encoder.instance {
      case UpdateHistoryItemV2.members.UpdateHistoryTransactionV2(member) =>
        encodeUpdateHistoryTransactionV2(member)
      case UpdateHistoryItemV2.members.UpdateHistoryReassignment(member) =>
        Encoder[UpdateHistoryReassignment].apply(member)
    }

  // -- Wrapper types that transitively contain UpdateHistoryTransaction -----------------------
  // The guardrail-generated encoders for these types call `.asJson` on their fields, which
  // resolves the companion-object encoders at compile time.  We re-encode specific fields so
  // that `.asJson` picks up our custom encoders above.

  /** Replaces a single field in a guardrail-generated encoder with a value encoded using the
    * implicits in this scope.  This is necessary because the `.asJson` calls inside the generated
    * companion-object encoders resolve implicits at the generated file's compile time — our custom
    * encoders defined here are not in scope there and can never be picked up.
    *
    * `JsonObject.add` replaces the value when the key already exists, preserving key order.
    */
  private def reEncode[A, B: Encoder](
      underlying: Encoder.AsObject[A],
      jsonKey: String,
      accessor: A => B,
  ): Encoder.AsObject[A] =
    Encoder.AsObject.instance[A] { a =>
      underlying.encodeObject(a).add(jsonKey, accessor(a).asJson)
    }

  implicit val encodeUpdateHistoryResponseV2: Encoder.AsObject[UpdateHistoryResponseV2] =
    reEncode(
      UpdateHistoryResponseV2.encodeUpdateHistoryResponseV2,
      "transactions",
      _.transactions,
    )

  implicit val encodeUpdateHistoryResponse: Encoder.AsObject[UpdateHistoryResponse] =
    reEncode(
      UpdateHistoryResponse.encodeUpdateHistoryResponse,
      "transactions",
      _.transactions,
    )

  implicit val encodeEventHistoryItem: Encoder.AsObject[EventHistoryItem] =
    reEncode(
      EventHistoryItem.encodeEventHistoryItem,
      "update",
      _.update,
    )

  implicit val encodeGetUpdatesBeforeResponse: Encoder.AsObject[GetUpdatesBeforeResponse] =
    reEncode(
      GetUpdatesBeforeResponse.encodeGetUpdatesBeforeResponse,
      "transactions",
      _.transactions,
    )

  implicit val encodeGetImportUpdatesResponse: Encoder.AsObject[GetImportUpdatesResponse] =
    reEncode(
      GetImportUpdatesResponse.encodeGetImportUpdatesResponse,
      "transactions",
      _.transactions,
    )
}
