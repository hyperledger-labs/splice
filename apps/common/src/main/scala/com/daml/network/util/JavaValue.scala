// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.util

import com.daml.ledger.api.v1.{value => scalaValue}
import com.daml.ledger.javaapi.data.codegen.ValueDecoder
import com.daml.ledger.javaapi.data.{Value => CodegenValue}
import com.daml.network.v0
import com.digitalasset.canton.ProtoDeserializationError
import com.digitalasset.canton.serialization.ProtoConverter

import scala.util.Try

/** A class representing a Daml-LF value of a specific type.
  * See https://docs.daml.com/app-dev/daml-lf-translation.html#data-types for the translation
  * from Daml to Daml-LF.
  * @param value The underlying value.
  */
final case class JavaValue[T <: CodegenValue](
    value: T
) {
  def toProtoV0: v0.Value = v0.Value(
    value = Some(scalaValue.Value.fromJavaProto(value.toProto))
  )
}

object JavaValue {
  def fromProto[T](decoder: ValueDecoder[T])(
      value: v0.Value
  ): Either[ProtoDeserializationError, Value[T]] = {
    for {
      valueP <- ProtoConverter.required("Value.value", value.value)
      value <- Try(
        decoder.decode(CodegenValue.fromProto(scalaValue.Value.toJavaProto(valueP)))
      ).toOption
        .toRight(
          ProtoDeserializationError
            .ValueConversionError("value", s"Failed to decode $valueP")
        )
    } yield Value(
      value = value
    )
  }
}
