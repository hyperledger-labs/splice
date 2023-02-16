// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.util

import com.google.protobuf
import cats.syntax.either.*
import com.daml.ledger.api.v1.contract_metadata.ContractMetadata.toJavaProto
import com.daml.ledger.api.v1.{CommandsOuterClass, value as scalaValue}
import com.daml.ledger.api.validation.NoLoggingValueValidator
import com.daml.ledger.javaapi.data.codegen.{
  ContractCompanion,
  ContractId,
  DamlRecord,
  InterfaceCompanion,
  ValueDecoder,
  Contract as CodegenContract,
}
import com.daml.ledger.javaapi.data.{ContractMetadata, CreatedEvent, Identifier, Value}
import com.daml.lf.data.Ref.Identifier as LfIdentifier
import com.daml.lf.value.json.ApiCodecCompressed
import com.daml.lf.value as lf
import com.daml.network.http.v0.definitions as http
import com.daml.network.http.v0.definitions.MaybeCachedContract
import com.daml.network.v0
import com.digitalasset.canton.ProtoDeserializationError
import com.digitalasset.canton.logging.ErrorLoggingContext
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.participant.ledger.api.client.JavaDecodeUtil
import com.digitalasset.canton.serialization.ProtoConverter
import com.digitalasset.canton.util.ErrorUtil
import io.circe.parser as circe
import org.apache.commons.codec.binary.Hex

import scala.util.Try

/** A class representing a Daml contract of a specific type (Daml
  * template) with an assigned contract ID. This is the type we use
  * for all our own code apart from the few places where we need to
  * integrate with the Java codegen APIs or the existing Canton code
  * so if you are unsure which type to use, this is probably the right
  * one.
  *
  * @param contractId Contract ID.
  * @param payload    Contract instance as defined in Daml template (without `contractId` and `agreementText`).
  * @tparam T Contract template type parameter.
  */
final case class Contract[TCid, T](
    identifier: Identifier,
    contractId: TCid & ContractId[_],
    payload: T & DamlRecord[_],
    metadata: ContractMetadata,
    createArgumentsBlob: protobuf.Any,
) extends PrettyPrinting {

  def toProtoV0: v0.Contract = v0.Contract(
    templateId = Some(scalaValue.Identifier.fromJavaProto(identifier.toProto)),
    contractId = contractId.contractId,
    payload = Some(scalaValue.Record.fromJavaProto(payload.toValue.toProtoRecord)),
    metadata = Some(
      com.daml.ledger.api.v1.contract_metadata.ContractMetadata.fromJavaProto(metadata.toProto)
    ),
    createArgumentsBlob = Some(com.google.protobuf.any.Any.fromJavaProto(createArgumentsBlob)),
  )

  def toJson(implicit elc: ErrorLoggingContext): http.Contract = {
    http.Contract(
      templateId =
        s"${identifier.getPackageId}:${identifier.getModuleName}:${identifier.getEntityName}",
      contractId = contractId.contractId,
      // TODO(#2019) Improve conversion between JSON libraries
      // once we switched to the native JSON encoding in the Java codegen.
      payload = circe
        .parse(
          ApiCodecCompressed
            .apiValueToJsValue(Contract.javaValueToLfValue(payload.toValue))
            .compactPrint
        )
        .valueOr(err => ErrorUtil.invalidState(s"Failed to convert from spray to circe: $err")),
      metadata = ContractMetadataUtil.toJson(metadata),
      createArgumentsBlob = Hex.encodeHexString(createArgumentsBlob.toByteArray),
    )
  }

  def toDisclosedContract: CommandsOuterClass.DisclosedContract = {
    com.daml.ledger.api.v1.CommandsOuterClass.DisclosedContract
      .newBuilder()
      .setCreateArguments(
        payload.toValue.toProtoRecord
      ) // TODO(#2676): use createArgumentsBlob here. Can't use it yet because currently the blob is always empty when
      // using the update-service.
      .setContractId(contractId.contractId)
      .setTemplateId(identifier.toProto)
      .setMetadata(metadata.toProto)
      .build()
  }

  override def pretty: Pretty[Contract[TCid, T]] = {

    import com.daml.network.util.PrettyInstances.*

    prettyOfClass[Contract[TCid, T]](
      param("contractId", _.contractId),
      param("templateId", _.identifier),
      param("payload", _.payload),
      param("contractMetadata", _.metadata),
    )
  }
}

object Contract {
  object Companion {
    type Template[TCid, Data] = ContractCompanion[_ <: CodegenContract[TCid, Data], TCid, Data]
    type Interface[ICid, Marker, View] = InterfaceCompanion[Marker, ICid, View]
  }

  def javaValueToLfValue(v: Value)(implicit elc: ErrorLoggingContext): lf.Value =
    // Disabling logging and instead logging the result
    // because LF uses a different logging library.
    NoLoggingValueValidator
      .validateValue(scalaValue.Value.fromJavaProto(v.toProto))
      .valueOr(err => ErrorUtil.internalError(err))

  def fromProto[TCid <: ContractId[T], T <: DamlRecord[?]](
      companion: Companion.Template[TCid, T]
  )(contract: v0.Contract): Either[ProtoDeserializationError, Contract[TCid, T]] = {
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
      payloadP <- ProtoConverter.required("payload", contract.payload)
      payload <- Try(
        decoder.decode(
          Value.fromProto(scalaValue.Value.toJavaProto(scalaValue.Value().withRecord(payloadP)))
        )
      ).toEither.left.map(ex =>
        ProtoDeserializationError.ValueConversionError("payload", s"Failed to decode payload: $ex")
      )
      metadata <- ProtoConverter.required(
        "ContractMetadata",
        contract.metadata.map(m => ContractMetadata.fromProto(toJavaProto(m))),
      )
      createArgumentsBlob <- ProtoConverter
        .required(
          "createArgumentsBlob",
          contract.createArgumentsBlob,
        )
        .map(com.google.protobuf.any.Any.toJavaProto)
    } yield Contract[TCid, T](
      identifier = javaTemplateId,
      contractId = contractId,
      payload = payload,
      metadata = metadata,
      createArgumentsBlob = createArgumentsBlob,
    )
  }

  def fromJson[TCid <: ContractId[T], T <: DamlRecord[?]](
      companion: Companion.Template[TCid, T]
  )(contract: http.Contract)(implicit
      decoder: TemplateJsonDecoder
  ): Either[ProtoDeserializationError, Contract[TCid, T]] = {
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
      metadata = ContractMetadataUtil.fromJson(contract.metadata)
      createArgumentsBlob = protobuf.Any.parseFrom(Hex.decodeHex(contract.createArgumentsBlob))
    } yield Contract[TCid, T](
      identifier = javaTemplateId,
      contractId = contractId,
      payload = payload,
      metadata = metadata,
      createArgumentsBlob = createArgumentsBlob,
    )
  }

  def handleMaybeCachedContract[TCid <: ContractId[T], T <: DamlRecord[?]](
      companion: Companion.Template[TCid, T]
  )(
      cachedValue: Option[Contract[TCid, T]],
      maybeCached: MaybeCachedContract,
  )(implicit decoder: TemplateJsonDecoder): Either[String, Contract[TCid, T]] = {
    for {
      res <- maybeCached.contract match {
        case None =>
          cachedValue.toRight(
            "The server indicated that we have cached a certain contract, but we don't have it cached. "
          )
        case Some(contract) =>
          Contract.fromJson(companion)(contract).leftMap(_.toString)
      }
    } yield res
  }

  /** This method is private on purpose because we only want to allow the construction of a
    * [[com.daml.network.util.Contract]] instance through passing a [[com.daml.ledger.javaapi.data.CreatedEvent]]
    * instance and not through a [[com.daml.ledger.javaapi.data.Contract]] because
    * the [[com.daml.ledger.javaapi.data.Contract]] doesn't have the `metadata` and `createArgumentsBlob`
    * arguments we need.
    */
  private def fromCodegenContract[TCid <: ContractId[?], T <: DamlRecord[?]](
      contract: CodegenContract[TCid, T],
      ev: CreatedEvent,
  ): Contract[TCid, T] = {
    Contract(
      identifier = contract.getContractTypeId,
      contractId = contract.id,
      payload = contract.data,
      metadata = ev.getContractMetadata,
      createArgumentsBlob = ev.getCreateArgumentsBlob,
    )
  }

  def fromCreatedEvent[TCid <: ContractId[?], T <: DamlRecord[?]](
      companion: Companion.Template[TCid, T]
  )(ev: CreatedEvent): Option[Contract[TCid, T]] = {
    JavaDecodeUtil
      .decodeCreated(companion)(ev)
      .map(fromCodegenContract(_, ev))
  }

  def fromCreatedEvent[Id <: ContractId[?], View <: DamlRecord[?]](
      companion: InterfaceCompanion[?, Id, View]
  )(ev: CreatedEvent): Option[Contract[Id, View]] =
    JavaDecodeUtil
      .decodeCreated(companion)(ev)
      .map(fromCodegenContract(_, ev))
}
