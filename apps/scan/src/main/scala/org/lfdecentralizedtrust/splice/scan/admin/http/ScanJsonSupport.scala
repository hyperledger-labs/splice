// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.http

import io.circe.Encoder
import org.lfdecentralizedtrust.splice.http.OmitNullString
import org.lfdecentralizedtrust.splice.http.v0.definitions.{
  UpdateHistoryItem,
  UpdateHistoryItemV2,
  UpdateHistoryReassignment,
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
}
