// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.util

import com.daml.ledger.javaapi.data.codegen.{
  Choice,
  ContractCompanion,
  InterfaceCompanion,
  ValueDecoder,
}
import com.daml.ledger.javaapi.data.{ExercisedEvent, Value as CodegenValue}
import com.digitalasset.canton.ProtoDeserializationError
import com.digitalasset.canton.logging.ErrorLoggingContext
import com.digitalasset.canton.util.ErrorUtil

import scala.jdk.OptionConverters.*
import scala.util.{Failure, Success, Try}

final case class ExerciseNode[Arg, Res](
    argument: Value[Arg],
    result: Value[Res],
)

/** Trait for companions of exercise nodes. For each exercise node that should be decoded, you must define an object that implements this.
  */
trait ExerciseNodeCompanion {
  type Tpl
  type Arg
  type Res

  val choice: Choice[Tpl, Arg, Res]
  val templateOrInterface: Either[ContractCompanion[_, _, Tpl], InterfaceCompanion[Tpl, _, _]]

  // The Java codegen does not provide generic en/decode functionality so we need to explicitly cary it around.
  val argDecoder: ValueDecoder[Arg]
  def argToValue(a: Arg): CodegenValue
  val resDecoder: ValueDecoder[Res]
  def resToValue(r: Res): CodegenValue

  def unapply(
      event: ExercisedEvent
  )(implicit lc: ErrorLoggingContext): Option[ExerciseNode[Arg, Res]] = {
    ExerciseNode
      .decodeExerciseEvent(this)(event)(lc)
  }
}

object ExerciseNode {
  def fromProtoEvent(
      companion: ExerciseNodeCompanion
  )(
      exercised: ExercisedEvent
  ): Either[ProtoDeserializationError, ExerciseNode[companion.Arg, companion.Res]] = for {
    argument <- decodeValue(companion.argDecoder, companion.argToValue)(
      "choiceArgument",
      exercised.getChoiceArgument,
    )
    result <- decodeValue(companion.resDecoder, companion.resToValue)(
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
    companion.templateOrInterface match {
      case Left(tplCompanion) =>
        event.getTemplateId == tplCompanion.TEMPLATE_ID && event.getChoice == companion.choice.name
      case Right(ifaceCompanion) =>
        // TODO(#2842) This works around a bug in canton-research.
        event.getInterfaceId.toScala.contains(ifaceCompanion.TEMPLATE_ID) && event.getChoice
          .split("#")
          .last == companion.choice.name
    }
  }

  def decodeExerciseEvent(companion: ExerciseNodeCompanion)(
      event: ExercisedEvent
  )(implicit lc: ErrorLoggingContext): Option[ExerciseNode[companion.Arg, companion.Res]] =
    Option.when(isChoice(companion)(event))(ExerciseNode.tryFromProtoEvent(companion)(event))

  private def decodeValue[A](valueDecoder: ValueDecoder[A], toValue: A => CodegenValue)(
      field: String,
      value: CodegenValue,
  ): Either[ProtoDeserializationError, Value[A]] = {
    Try(valueDecoder.decode(value)) match {
      case Failure(_) =>
        Left(
          ProtoDeserializationError.ValueConversionError(
            field,
            s"Unexpectedly couldn't decode LF-value $value. Did you specify the wrong type to decode to?",
          )
        )
      case Success(value) => Right(new Value(value, toValue))
    }
  }
}
