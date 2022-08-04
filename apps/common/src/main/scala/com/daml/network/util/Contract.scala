// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.util

import com.daml.ledger.client.binding.{
  Contract => CodegenContract,
  Primitive,
  Template,
  TemplateCompanion,
}
import com.daml.ledger.api.refinements.ApiTypes
import com.daml.network.v0
import com.digitalasset.canton.ProtoDeserializationError
import com.digitalasset.canton.serialization.ProtoConverter

/** A class representing a Daml contract of a specific type (Daml template) with an assigned contract ID.
  *
  * @param contractId     Contract ID.
  * @param payload          Contract instance as defined in Daml template (without `contractId` and `agreementText`).
  * @tparam T             Contract template type parameter.
  */
final case class Contract[+T](
    contractId: Primitive.ContractId[T],
    payload: T with Template[T],
) {
  def toProtoV0: v0.Contract = v0.Contract(
    templateId = Some(ApiTypes.TemplateId.unwrap(payload.templateId)),
    contractId = ApiTypes.ContractId.unwrap(contractId),
    payload = Some(payload.arguments),
  )
}

object Contract {
  def fromProto[T <: Template[T]](
      companion: TemplateCompanion[T]
  )(contract: v0.Contract): Either[ProtoDeserializationError, Contract[T]] = {
    for {
      templateId <- ProtoConverter.required("templateId", contract.templateId)
      _ <- Either.cond(
        templateId == ApiTypes.TemplateId.unwrap(companion.id),
        (),
        ProtoDeserializationError.ValueConversionError(
          "templateId",
          s"Actual template id $templateId does not match expected template id ${companion.id}",
        ),
      )
      contractId = Primitive.ContractId[T](contract.contractId)
      payloadP <- ProtoConverter.required("payload", contract.payload)
      payload <- companion
        .fromNamedArguments(payloadP)
        .toRight(
          ProtoDeserializationError
            .ValueConversionError("payload", s"Failed to decode payload to ${companion.id}")
        )
    } yield Contract(
      contractId = contractId,
      payload = payload,
    )
  }

  def fromCodegenContract[T](contract: CodegenContract[T]): Contract[T] =
    Contract(
      contractId = contract.contractId,
      payload = contract.value,
    )
}
