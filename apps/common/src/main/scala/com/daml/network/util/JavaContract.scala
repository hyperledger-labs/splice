// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.util

import com.daml.ledger.api.v1.{value => scalaValue}
import com.daml.ledger.javaapi.data.codegen.{
  Contract => CodegenContract,
  ContractCompanion,
  ContractId,
  DamlRecord,
  ValueDecoder,
}
import com.daml.ledger.javaapi.data.{CreatedEvent, Identifier, Template, Value}
import com.daml.network.v0
import com.digitalasset.canton.ProtoDeserializationError
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting, PrettyUtil}
import com.digitalasset.canton.participant.ledger.api.client.JavaDecodeUtil
import com.digitalasset.canton.serialization.ProtoConverter

import scala.util.Try

/** A class representing a Daml contract of a specific type (Daml template) with an assigned contract ID.
  *
  * @param contractId     Contract ID.
  * @param payload          Contract instance as defined in Daml template (without `contractId` and `agreementText`).
  * @tparam T             Contract template type parameter.
  */
final case class JavaContract[TCid <: ContractId[T], T](
    identifier: Identifier,
    contractId: TCid,
    payload: T with DamlRecord[_],
) extends PrettyPrinting {
  def toProtoV0: v0.Contract = v0.Contract(
    templateId = Some(scalaValue.Identifier.fromJavaProto(identifier.toProto)),
    contractId = contractId.contractId,
    payload = Some(scalaValue.Record.fromJavaProto(payload.toValue.toProtoRecord)),
  )

  override def pretty: Pretty[JavaContract[TCid, T]] = {

    implicit def prettyPrimitiveContractId: Pretty[ContractId[_]] = prettyOfString { coid =>
      val coidStr = coid.contractId
      val tokens = coidStr.split(':')
      if (tokens.lengthCompare(2) == 0) {
        tokens(0).readableHash.toString + ":" + tokens(1).readableHash.toString
      } else {
        // Don't abbreviate anything for unusual contract ids
        coidStr
      }
    }

    implicit def prettyRecord: Pretty[DamlRecord[_]] =
      PrettyUtil.prettyOfString(_.toValue.toString)

    prettyOfClass[JavaContract[TCid, T]](
      param("contractId", _.contractId),
      param("payload", _.payload),
    )
  }
}

object JavaContract {
  def fromProto[TCid <: ContractId[T], T <: Template](
      companion: ContractCompanion[_, TCid, T]
  )(contract: v0.Contract): Either[ProtoDeserializationError, JavaContract[TCid, T]] = {
    val decoder: ValueDecoder[T] = ContractCompanion.valueDecoder[T](companion)
    for {
      templateId <- ProtoConverter.required("templateId", contract.templateId)
      javaTemplateId = Identifier.fromProto(scalaValue.Identifier.toJavaProto(templateId))
      _ <- Either.cond(
        javaTemplateId == companion.TEMPLATE_ID,
        (),
        ProtoDeserializationError.ValueConversionError(
          "templateId",
          s"Actual template id $javaTemplateId does not match expected template id ${companion.TEMPLATE_ID}",
        ),
      )
      contractId = companion.toContractId(new ContractId[T](contract.contractId))
      payloadP <- ProtoConverter.required("opayload", contract.payload)
      payload <- Try(
        decoder.decode(
          Value.fromProto(scalaValue.Value.toJavaProto(scalaValue.Value().withRecord(payloadP)))
        )
      ).toEither.left.map(ex =>
        ProtoDeserializationError.ValueConversionError("payload", s"Failed to decode payload: $ex")
      )
    } yield JavaContract[TCid, T](
      identifier = javaTemplateId,
      contractId = contractId,
      payload = payload,
    )
  }

  def fromCodegenContract[TCid <: ContractId[T], T <: Template](
      contract: CodegenContract[TCid, T]
  ): JavaContract[TCid, T] =
    JavaContract(
      identifier = contract.getContractTypeId,
      contractId = contract.id,
      payload = contract.data,
    )

  def fromCreatedEvent[TC <: CodegenContract[TCid, T], TCid <: ContractId[T], T <: Template](
      companion: ContractCompanion[TC, TCid, T]
  )(ev: CreatedEvent): Option[JavaContract[TCid, T]] =
    JavaDecodeUtil.decodeCreated(companion)(ev).map(JavaContract.fromCodegenContract)
}
