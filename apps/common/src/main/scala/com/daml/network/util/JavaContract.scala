// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.util

import cats.syntax.either.*
import com.daml.ledger.api.v1.{value as scalaValue}
import com.daml.ledger.api.validation.NoLoggingValueValidator
import com.daml.ledger.javaapi.data.codegen.{
  Contract as CodegenContract,
  ContractCompanion,
  ContractId,
  DamlRecord,
  InterfaceCompanion,
  ValueDecoder,
}
import com.daml.ledger.javaapi.data.{CreatedEvent, Identifier, Template, Value}
import com.daml.lf.data.Ref.Identifier as LfIdentifier
import com.daml.lf.value.json.ApiCodecCompressed
import com.daml.lf.value as lf
import com.daml.network.http.v0.definitions as http
import com.daml.network.v0
import com.digitalasset.canton.ProtoDeserializationError
import com.digitalasset.canton.logging.ErrorLoggingContext
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.participant.ledger.api.client.JavaDecodeUtil
import com.digitalasset.canton.serialization.ProtoConverter
import com.digitalasset.canton.util.ErrorUtil
import io.circe.parser as circe

import scala.util.Try

/** A class representing a Daml contract of a specific type (Daml template) with an assigned contract ID.
  *
  * @param contractId     Contract ID.
  * @param payload          Contract instance as defined in Daml template (without `contractId` and `agreementText`).
  * @tparam T             Contract template type parameter.
  */
final case class JavaContract[TCid <: ContractId[_], T](
    identifier: Identifier,
    contractId: TCid,
    payload: T with DamlRecord[_],
) extends PrettyPrinting {

  def toProtoV0: v0.Contract = v0.Contract(
    templateId = Some(scalaValue.Identifier.fromJavaProto(identifier.toProto)),
    contractId = contractId.contractId,
    payload = Some(scalaValue.Record.fromJavaProto(payload.toValue.toProtoRecord)),
  )

  def toJson(implicit elc: ErrorLoggingContext): http.Contract = http.Contract(
    templateId =
      s"${identifier.getPackageId}:${identifier.getModuleName}:${identifier.getEntityName}",
    contractId = contractId.contractId,
    // TODO(#2019) Improve conversion between JSON libraries
    // once we switched to the native JSON encoding in the Java codeegn.
    payload = circe
      .parse(
        ApiCodecCompressed
          .apiValueToJsValue(JavaContract.javaValueToLfValue(payload.toValue))
          .compactPrint
      )
      .valueOr(err => ErrorUtil.invalidState(s"Failed to convert from spray to circe: $err")),
  )

  override def pretty: Pretty[JavaContract[TCid, T]] = {

    import com.daml.network.util.PrettyInstances.*

    prettyOfClass[JavaContract[TCid, T]](
      param("contractId", _.contractId),
      param("templateId", _.identifier),
      param("payload", _.payload),
    )
  }
}

object JavaContract {
  def javaValueToLfValue(v: Value)(implicit elc: ErrorLoggingContext): lf.Value =
    // Disabling logging and instead logging the result
    // because LF uses a different logging library.
    NoLoggingValueValidator
      .validateValue(scalaValue.Value.fromJavaProto(v.toProto))
      .valueOr(err => ErrorUtil.internalError(err))

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

  def fromJson[TCid <: ContractId[T], T <: Template](
      companion: ContractCompanion[_, TCid, T]
  )(contract: http.Contract)(implicit
      decoder: TemplateJsonDecoder
  ): Either[ProtoDeserializationError, JavaContract[TCid, T]] = {
    for {
      templateId <- LfIdentifier
        .fromString(contract.templateId)
        .left
        .map(err => ProtoDeserializationError.ValueConversionError("templateId", err))
      javaTemplateId = new Identifier(
        templateId.packageId,
        templateId.qualifiedName.module.dottedName,
        templateId.qualifiedName.name.dottedName,
      )
      _ <- Either.cond(
        javaTemplateId == companion.TEMPLATE_ID,
        (),
        ProtoDeserializationError.ValueConversionError(
          "templateId",
          s"Actual template id $javaTemplateId does not match expected template id ${companion.TEMPLATE_ID}",
        ),
      )
      contractId = companion.toContractId(new ContractId[T](contract.contractId))
      payload <- Try(
        decoder.decodeTemplate(companion)(contract.payload)
      ).toEither.left.map(ex =>
        ProtoDeserializationError.ValueConversionError("payload", s"Failed to decode payload: $ex")
      )
    } yield JavaContract[TCid, T](
      identifier = javaTemplateId,
      contractId = contractId,
      payload = payload,
    )
  }

  def fromCodegenContract[TCid <: ContractId[_], T <: DamlRecord[_]](
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

  def fromCreatedEvent[I, Id <: ContractId[I], View <: DamlRecord[View]](
      companion: InterfaceCompanion[I, Id, View]
  )(ev: CreatedEvent): Option[JavaContract[Id, View]] =
    JavaDecodeUtil.decodeCreated(companion)(ev).map(JavaContract.fromCodegenContract)
}
