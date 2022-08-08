// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.util

import com.daml.ledger.client.binding.{Value => CodegenValue, ValueDecoder, ValueEncoder, Primitive}

import com.daml.ledger.api.refinements.ApiTypes
import com.daml.network.v0
import com.digitalasset.canton.ProtoDeserializationError
import com.digitalasset.canton.serialization.ProtoConverter

/** A class representing a Daml-LF value of a specific type.
  * See https://docs.daml.com/app-dev/daml-lf-translation.html#data-types for the translation
  * from Daml to Daml-LF.
  * @param value The underlying value.
  */
final case class Value[T](
    value: T
) {
  def toProtoV0(implicit enc: ValueEncoder[T]): v0.Value = v0.Value(
    value = Some(CodegenValue.encode(value))
  )
}

object Value {
  def fromProto[T](
      value: v0.Value
  )(implicit dec: ValueDecoder[T]): Either[ProtoDeserializationError, Value[T]] = {
    for {
      valueP <- ProtoConverter.required("value", value.value)
      value <- CodegenValue
        .decode(valueP)
        .toRight(
          ProtoDeserializationError
            .ValueConversionError("value", s"Failed to decode $valueP")
        )
    } yield Value(
      value = value
    )
  }
}
