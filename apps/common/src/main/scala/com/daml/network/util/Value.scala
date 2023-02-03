// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.util

import com.daml.ledger.api.v1.{value as scalaValue}
import com.daml.ledger.javaapi.data.codegen.ValueDecoder
import com.daml.ledger.javaapi.data.{Value as CodegenValue}
import com.daml.network.v0
import com.digitalasset.canton.ProtoDeserializationError
import com.digitalasset.canton.serialization.ProtoConverter

import scala.util.Try

/** A class representing a Daml-LF value of a specific type.
  * See https://docs.daml.com/app-dev/daml-lf-translation.html#data-types for the translation
  * from Daml to Daml-LF.
  * @param value The underlying value.
  * @param toValue Conversion to protobuf value. Java codegen does not provide a generic
  *   mechanism for that so we explicitly carry the function around.
  */
final class Value[T](
    val value: T,
    toValue: T => CodegenValue,
) {
  def toProtoV0: v0.Value = v0.Value(
    value = Some(scalaValue.Value.fromJavaProto(toValue(value).toProto))
  )

  // Overridden to avoid equality on toValue. toValue is uniquely defined
  // for codegen values so this is safe.
  override def equals(obj: Any) = obj match {
    case that: Value[_] => this.value == that.value
    case _ => false
  }

  override def hashCode(): Int = this.value.hashCode()
}

object Value {
  def fromProto[T](decoder: ValueDecoder[T], toValue: T => CodegenValue)(
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
    } yield new Value(
      value = value,
      toValue = toValue,
    )
  }
}
