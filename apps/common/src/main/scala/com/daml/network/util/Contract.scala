// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.util

import cats.syntax.either.*
import cats.syntax.traverse.*
import com.daml.ledger.api.v1.{CommandsOuterClass, value as scalaValue, ValueOuterClass}
import com.daml.ledger.javaapi.data.{ContractMetadata, CreatedEvent, Identifier, Value}
import com.daml.ledger.javaapi.data.codegen.{
  ContractCompanion,
  ContractId,
  DamlRecord,
  InterfaceCompanion,
  Contract as CodegenContract,
  Update,
}
import com.daml.lf.value as lf
import com.daml.lf.data.Ref.Identifier as LfIdentifier
import com.digitalasset.canton.daml.lf.value.json.ApiCodecCompressed
import com.daml.network.http.v0.definitions as http
import com.daml.network.http.v0.definitions.MaybeCachedContract
import com.daml.network.util.JavaDecodeUtil
import com.digitalasset.canton.ProtoDeserializationError
import com.digitalasset.canton.ledger.api.validation.NoLoggingValueValidator
import com.digitalasset.canton.logging.ErrorLoggingContext
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.util.ErrorUtil
import com.google.protobuf.util.JsonFormat
import io.circe.{Json, parser as circe}

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
  * @param payloadValue The raw protobuf value of the payload. This is required for contracts that are used through
  *   explicit disclosure as that requires passing the original payload which we cannot recover from payload as that may
  *   have been extended with optional fields.
  * @tparam T Contract template type parameter.
  */
final case class Contract[TCid, T](
    identifier: Identifier,
    override val contractId: TCid & ContractId[_],
    override val payload: T & DamlRecord[_],
    // TODO(#7965) Make this mandatory
    val payloadValue: Option[com.daml.ledger.javaapi.data.DamlRecord],
    metadata: ContractMetadata,
) extends PrettyPrinting
    with Contract.Has[TCid, T] {

  // TODO(#7965) Remove this.
  // We only need to be able to decode old contract so we can already exploit the fact that this field is mandatory in most places.
  // In particular, it is safe to use this for writes to stores.
  def mandatoryPayloadValue: com.daml.ledger.javaapi.data.DamlRecord =
    payloadValue.getOrElse(
      throw new IllegalStateException(
        show"Called mandatory payload value on contract that did not have it set: $this"
      )
    )

  def toHttp(implicit elc: ErrorLoggingContext): http.Contract = {
    val payloadValueJson = payloadValue.map { v =>
      val payloadValueBuilder = new java.lang.StringBuilder()
      JsonFormat.printer().appendTo(v.toProtoRecord, payloadValueBuilder)
      circe
        .parse(payloadValueBuilder.toString)
        .valueOr(err => ErrorUtil.invalidState(s"Failed to convert payload value to JSON: $err"))
    }
    http.Contract(
      templateId =
        s"${identifier.getPackageId}:${identifier.getModuleName}:${identifier.getEntityName}",
      contractId = contractId.contractId,
      payload = circe
        .parse(
          ApiCodecCompressed
            .apiValueToJsValue(Contract.javaValueToLfValue(payload.toValue))
            .compactPrint
        )
        .valueOr(err => ErrorUtil.invalidState(s"Failed to convert from spray to circe: $err")),
      payloadValue = payloadValueJson,
      metadata = ContractMetadataUtil.toHttp(metadata),
    )
  }

  def toDisclosedContract: CommandsOuterClass.DisclosedContract = {
    com.daml.ledger.api.v1.CommandsOuterClass.DisclosedContract
      .newBuilder()
      .setCreateArguments(mandatoryPayloadValue.toProtoRecord)
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
      param("payloadValue", _.payloadValue),
      param("contractMetadata", _.metadata),
    )
  }

  @deprecated("no need to call `.contract`, guaranteed to return receiver", since = "20230621")
  override def contract: this.type = this
}

object Contract {
  object Companion {
    type Template[TCid, Data] = ContractCompanion[_ <: CodegenContract[TCid, Data], TCid, Data]
    type Interface[ICid, Marker, View] = InterfaceCompanion[Marker, ICid, View]
  }

  trait Has[TCid, T] {
    def contract: Contract[TCid, T]
    def contractId: TCid & ContractId[?] = contract.contractId
    def payload: T & DamlRecord[?] = contract.payload

    final def exercise[Z](f: (TCid & ContractId[?]) => Update[Z]): Exercising[this.type, Z] =
      Exercising(this, f(this.contractId))
  }

  final case class Exercising[+Origin, Z](origin: Origin, update: Update[Z])

  def javaValueToLfValue(v: Value)(implicit elc: ErrorLoggingContext): lf.Value =
    // Disabling logging and instead logging the result
    // because LF uses a different logging library.
    NoLoggingValueValidator
      .validateValue(scalaValue.Value.fromJavaProto(v.toProto))
      .valueOr(err => ErrorUtil.internalError(err))

  def fromHttp[TCid <: ContractId[T], T <: DamlRecord[?]](
      companion: Companion.Template[TCid, T]
  )(contract: http.Contract)(implicit
      decoder: TemplateJsonDecoder
  ): Either[ProtoDeserializationError, Contract[TCid, T]] = {
    val contractId = companion.toContractId(new ContractId[T](contract.contractId))
    fromHttp(companion.TEMPLATE_ID, contractId, decoder.decodeTemplate(companion), contract)
  }

  def fromHttp[ICid <: ContractId[Marker], Marker, View <: DamlRecord[?]](
      interfaceCompanion: Companion.Interface[ICid, Marker, View]
  )(contract: http.Contract)(implicit
      decoder: TemplateJsonDecoder
  ): Either[ProtoDeserializationError, Contract[ICid, View]] = {
    val contractId = interfaceCompanion.toContractId(new ContractId[Marker](contract.contractId))
    fromHttp(
      interfaceCompanion.TEMPLATE_ID,
      contractId,
      decoder.decodeInterface(interfaceCompanion),
      contract,
    )
  }

  private def fromHttp[TCid <: ContractId[?], T <: DamlRecord[?]](
      companionTemplateId: Identifier,
      contractId: TCid,
      decodePayload: Json => T,
      contract: http.Contract,
  ): Either[ProtoDeserializationError, Contract[TCid, T]] = {
    val metadata = ContractMetadataUtil.fromHttp(contract.metadata)
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
      result <- fromHttp(companionTemplateId, contractId, decodePayload)(
        javaTemplateId,
        contract.payload,
        contract.payloadValue,
        metadata,
      )
    } yield result
  }

  private def decodePayloadValue(
      json: Json
  ): Either[ProtoDeserializationError, com.daml.ledger.javaapi.data.DamlRecord] = {
    val builder = ValueOuterClass.Record.newBuilder
    Try(JsonFormat.parser().merge(new java.io.StringReader(json.noSpaces), builder)).toEither
      .bimap(
        ex =>
          ProtoDeserializationError
            .ValueConversionError("payloadValue", s"Failed to decode payload value: $ex"),
        _ => com.daml.ledger.javaapi.data.DamlRecord.fromProto(builder.build),
      )
  }

  def fromHttp[TCid <: ContractId[?], T <: DamlRecord[?]](
      companionTemplateId: Identifier,
      contractId: TCid,
      decodePayload: Json => T,
  )(
      javaTemplateId: Identifier,
      payload: Json,
      payloadValue: Option[Json],
      metadata: ContractMetadata,
  ): Either[ProtoDeserializationError, Contract[TCid, T]] = {
    for {
      _ <- Either.cond(
        QualifiedName(javaTemplateId) == QualifiedName(
          companionTemplateId
        ),
        (),
        ProtoDeserializationError.ValueConversionError(
          "templateId",
          s"Actual template id $javaTemplateId does not match expected template id $companionTemplateId",
        ),
      )
      payload <- Try(decodePayload(payload)).toEither.left.map(ex =>
        ProtoDeserializationError.ValueConversionError("payload", s"Failed to decode payload: $ex")
      )
      payloadValue <- payloadValue.traverse(decodePayloadValue(_))
    } yield Contract[TCid, T](
      identifier = javaTemplateId,
      contractId = contractId,
      payload = payload,
      payloadValue = payloadValue,
      metadata = metadata,
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
          Contract.fromHttp(companion)(contract).leftMap(_.toString)
      }
    } yield res
  }

  /** This method is private on purpose because we only want to allow the construction of a
    * [[com.daml.network.util.Contract]] instance through passing a [[com.daml.ledger.javaapi.data.CreatedEvent]]
    * instance and not through a [[com.daml.ledger.javaapi.data.Contract]] because
    * the [[com.daml.ledger.javaapi.data.Contract]] doesn't have the `metadata`
    * argument we need.
    */
  private def fromCodegenContract[TCid <: ContractId[?], T <: DamlRecord[?]](
      contract: CodegenContract[TCid, T],
      ev: CreatedEvent,
  ): Contract[TCid, T] = {
    Contract(
      identifier = ev.getTemplateId,
      contractId = contract.id,
      payload = contract.data,
      payloadValue = Some(ev.getArguments),
      metadata = ev.getContractMetadata,
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
