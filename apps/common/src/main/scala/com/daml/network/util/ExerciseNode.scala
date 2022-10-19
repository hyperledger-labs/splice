// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.util

import com.daml.ledger.api.v1
import com.daml.ledger.api.v1.transaction.TreeEvent.Kind.Exercised
import com.daml.ledger.client.binding.{Value => CodegenValue, ValueDecoder, ValueEncoder}
import com.daml.network.v0
import com.digitalasset.canton.ProtoDeserializationError
import com.digitalasset.canton.logging.ErrorLoggingContext
import com.digitalasset.canton.serialization.ProtoConverter
import com.digitalasset.canton.util.ErrorUtil

final case class ExerciseNode[Arg, Res](
    argument: Arg,
    result: Res,
) {
  def toProtoV0(implicit arg: ValueEncoder[Arg], resEnc: ValueEncoder[Res]): v0.ExerciseNode =
    v0.ExerciseNode(
      argument = Some(Value(argument).toProtoV0),
      result = Some(Value(result).toProtoV0),
    )
}

/** Trait for companions of exercise nodes. For each exercise node that should be decoded, you must define an object that implements this.
  * The object can then be passed to ExerciseNode.fromProto.
  */
trait ExerciseNodeCompanion {
  type Arg
  type Res
}

object ExerciseNode {
  def fromProto(
      companion: ExerciseNodeCompanion
  )(node: v0.ExerciseNode)(implicit
      argDec: ValueDecoder[companion.Arg],
      resDec: ValueDecoder[companion.Res],
  ): Either[ProtoDeserializationError, ExerciseNode[companion.Arg, companion.Res]] = for {
    argumentP <- ProtoConverter.required("ExerciseNode.argument", node.argument)
    argument <- Value.fromProto[companion.Arg](argumentP)
    resultP <- ProtoConverter.required("ExerciseNode.result", node.result)
    result <- Value.fromProto[companion.Res](resultP)
  } yield ExerciseNode(argument.value, result.value)

  def fromProtoEvent(
      companion: ExerciseNodeCompanion
  )(exercised: Exercised)(implicit
      argDec: ValueDecoder[companion.Arg],
      resDec: ValueDecoder[companion.Res],
  ): Either[ProtoDeserializationError, ExerciseNode[companion.Arg, companion.Res]] = for {
    argumentP <- ProtoConverter.required(
      "ExercisedEvent.choiceArgument",
      exercised.value.choiceArgument,
    )
    argument <- decodeValue("choiceArgument", argumentP)(argDec)
    resultP <- ProtoConverter.required(
      "ExercisedEvent.exerciseResult",
      exercised.value.exerciseResult,
    )
    result <- decodeValue("exerciseResult", resultP)(resDec)
  } yield ExerciseNode(argument, result)

  def tryFromProtoEvent(
      companion: ExerciseNodeCompanion
  )(exercised: Exercised)(implicit
      argDec: ValueDecoder[companion.Arg],
      resDec: ValueDecoder[companion.Res],
      lc: ErrorLoggingContext,
  ): ExerciseNode[companion.Arg, companion.Res] = fromProtoEvent(companion)(exercised) match {
    case Left(e) => ErrorUtil.invalidState(e.message)
    case Right(v) => v
  }

  private def decodeValue[A](
      field: String,
      value: v1.value.Value,
  )(implicit A: ValueDecoder[A]): Either[ProtoDeserializationError, A] = {
    CodegenValue.decode[A](value) match {
      case None =>
        Left(
          ProtoDeserializationError.ValueConversionError(
            field,
            s"Unexpectedly couldn't decode LF-value $value to $A. Did you specify the wrong type to decode to?",
          )
        )
      case Some(value) => Right(value)
    }
  }
}
