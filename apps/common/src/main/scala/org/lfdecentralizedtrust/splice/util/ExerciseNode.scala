// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.daml.ledger.javaapi.data.{ExercisedEvent, Value as CodegenValue}
import com.daml.ledger.javaapi.data.codegen.{
  Choice,
  ContractCompanion,
  DefinedDataType,
  ValueDecoder,
}
import com.digitalasset.canton.ProtoDeserializationError
import com.digitalasset.canton.logging.ErrorLoggingContext
import com.digitalasset.canton.util.ErrorUtil
import com.digitalasset.canton.util.ShowUtil.*
import org.lfdecentralizedtrust.splice.util.PrettyInstances.*

import scala.util.{Failure, Success, Try}

final case class ExerciseNode[Arg, Res](
    argument: Value[Arg],
    result: Value[Res],
)

/** Trait for companions of exercise nodes. For each exercise node that should be decoded, you must define an object that implements this.
  */
sealed abstract class ExerciseNodeCompanion {
  type Tpl
  type Arg
  type Res

  val choice: Choice[Tpl, Arg, Res]
  val template: ContractCompanion[?, ?, Tpl]

  val argDecoder: ValueDecoder[Arg] = choice.argTypeDecoder
  final def argToValue(a: Arg): CodegenValue = choice.encodeArg(a)
  val resDecoder: ValueDecoder[Res] = choice.returnTypeDecoder
  def resToValue(r: Res): CodegenValue

  final def unapply(
      event: ExercisedEvent
  )(implicit lc: ErrorLoggingContext): Option[ExerciseNode[Arg, Res]] = {
    ExerciseNode
      .decodeExerciseEvent(this)(event)(lc)
  }
}

object ExerciseNodeCompanion {
  // convert R's upper bound to a typeclass if you need to support incompatible
  // choice return types, e.g. Archive's Unit
  abstract class Mk[T, A, R <: DefinedDataType[?]](
      override val choice: Choice[T, A, R],
      override val template: ContractCompanion[?, ?, T],
  ) extends ExerciseNodeCompanion {
    type Tpl = T
    type Arg = A
    type Res = R

    final override def resToValue(r: Res): CodegenValue =
      r.toValue
  }
}

object ExerciseNode {
  def fromProtoEvent(
      companion: ExerciseNodeCompanion
  )(
      exercised: ExercisedEvent
  ): Either[ProtoDeserializationError, ExerciseNode[companion.Arg, companion.Res]] = for {
    argument <- decodeValue(companion.argDecoder)(
      companion,
      "choiceArgument",
      exercised.getChoiceArgument,
    )
    result <- decodeValue(companion.resDecoder)(
      companion,
      "exerciseResult",
      exercised.getExerciseResult,
    )
  } yield ExerciseNode(argument, result)

  private def tryFromProtoEvent(
      companion: ExerciseNodeCompanion
  )(exercised: ExercisedEvent)(implicit
      lc: ErrorLoggingContext
  ): ExerciseNode[companion.Arg, companion.Res] = fromProtoEvent(companion)(exercised) match {
    case Left(e) => ErrorUtil.invalidState(e.message)
    case Right(v) => v
  }

  private def isChoice(companion: ExerciseNodeCompanion)(event: ExercisedEvent) = {
    QualifiedName(companion.template.TEMPLATE_ID) == QualifiedName(
      event.getTemplateId
    ) && event.getChoice == companion.choice.name
  }

  def decodeExerciseEvent(companion: ExerciseNodeCompanion)(
      event: ExercisedEvent
  )(implicit lc: ErrorLoggingContext): Option[ExerciseNode[companion.Arg, companion.Res]] =
    Option.when(isChoice(companion)(event))(ExerciseNode.tryFromProtoEvent(companion)(event))

  private def decodeValue[A](valueDecoder: ValueDecoder[A])(
      companion: ExerciseNodeCompanion,
      field: String,
      value: CodegenValue,
  ): Either[ProtoDeserializationError, Value[A]] = {
    Try(valueDecoder.decode(value)) match {
      case Failure(_) =>
        Left(
          ProtoDeserializationError.ValueConversionError(
            field,
            show"""
            |Unexpectedly couldn't decode LF-value, did you specify the wrong type to decode to, or is there an upgrade incompatibility?
            |  specified template: ${companion.template.TEMPLATE_ID}
            |  specified choice: ${companion.choice.name.unquoted}
            |  value: $value
            |""".stripMargin,
          )
        )
      case Success(value) => Right(new Value(value))
    }
  }
}
