// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.util

import com.daml.ledger.client.binding.{ValueDecoder, ValueEncoder}
import com.daml.network.v0
import com.digitalasset.canton.ProtoDeserializationError
import com.digitalasset.canton.serialization.ProtoConverter

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
}
