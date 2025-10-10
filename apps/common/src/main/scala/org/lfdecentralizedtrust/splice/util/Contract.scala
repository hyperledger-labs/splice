// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import cats.syntax.either.*
import com.daml.ledger.api.v2.{CommandsOuterClass, value as scalaValue}
import com.daml.ledger.javaapi.data.{CreatedEvent, Identifier, Value}
import com.daml.ledger.javaapi.data.codegen.{
  ContractCompanion,
  ContractId,
  DamlRecord,
  InterfaceCompanion,
  Update,
  Contract as CodegenContract,
}
import com.digitalasset.daml.lf.value as lf
import com.digitalasset.daml.lf.data.Ref.Identifier as LfIdentifier
import com.digitalasset.daml.lf.data.Time.Timestamp
import org.lfdecentralizedtrust.splice.http.v0.definitions as http
import org.lfdecentralizedtrust.splice.http.v0.definitions.MaybeCachedContract
import org.lfdecentralizedtrust.splice.util.JavaDecodeUtil
import com.digitalasset.canton.daml.lf.value.json.ApiCodecCompressed
import com.digitalasset.canton.ProtoDeserializationError
import com.digitalasset.canton.ledger.api.validation.ValueValidator
import com.digitalasset.canton.logging.ErrorLoggingContext
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.participant.pretty.Implicits.prettyContractId
import com.digitalasset.canton.util.ErrorUtil
import com.google.protobuf.ByteString
import io.circe.{Json, parser as circe}

import java.time.Instant
import java.util.Base64
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
  * @param createEventsPayload The createEventsPayload bytestring required for explicit disclosure.
  * @tparam T Contract template type parameter.
  */
final case class Contract[TCid, T](
    identifier: Identifier,
    override val contractId: TCid & ContractId[_],
    override val payload: T & DamlRecord[_],
    val createdEventBlob: ByteString,
    val createdAt: Instant,
) extends PrettyPrinting
    with Contract.Has[TCid, T] {

  def toHttp(implicit elc: ErrorLoggingContext): http.Contract = {
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
      createdEventBlob = Base64.getEncoder.encodeToString(createdEventBlob.toByteArray),
      createdAt = Timestamp.assertFromInstant(createdAt).toString,
    )
  }

  def toDisclosedContract: CommandsOuterClass.DisclosedContract = {
    com.daml.ledger.api.v2.CommandsOuterClass.DisclosedContract
      .newBuilder()
      .setCreatedEventBlob(createdEventBlob)
      .setContractId(contractId.contractId)
      .setTemplateId(identifier.toProto)
      .build()
  }

  override def pretty: Pretty[Contract[TCid, T]] = {

    import org.lfdecentralizedtrust.splice.util.PrettyInstances.*

    prettyOfClass[Contract[TCid, T]](
      param("contractId", _.contractId),
      param("templateId", _.identifier),
      param("payload", _.payload),
      param("createdEventBlob", _.createdEventBlob),
      param("createdAt", _.createdAt),
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
    ValueValidator
      .validateValue(scalaValue.Value.fromJavaProto(v.toProto))
      .valueOr(err => ErrorUtil.internalError(err))

  def fromHttp[TCid <: ContractId[T], T <: DamlRecord[?]](
      companion: Companion.Template[TCid, T]
  )(contract: http.Contract)(implicit
      decoder: TemplateJsonDecoder
  ): Either[ProtoDeserializationError, Contract[TCid, T]] = {
    val contractId = companion.toContractId(new ContractId[T](contract.contractId))
    fromHttp(
      companion.getTemplateIdWithPackageId,
      contractId,
      decoder.decodeTemplate(companion),
      contract,
    )
  }

  def fromHttp[ICid <: ContractId[Marker], Marker, View <: DamlRecord[?]](
      interfaceCompanion: Companion.Interface[ICid, Marker, View]
  )(contract: http.Contract)(implicit
      decoder: TemplateJsonDecoder
  ): Either[ProtoDeserializationError, Contract[ICid, View]] = {
    val contractId = interfaceCompanion.toContractId(new ContractId[Marker](contract.contractId))
    fromHttp(
      interfaceCompanion.getTemplateIdWithPackageId,
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
      instant <- Try(Instant.parse(contract.createdAt)).toEither.leftMap(ex =>
        ProtoDeserializationError.ValueConversionError(
          "createdAt",
          s"Cannot parse instant: ${ex.getMessage}",
        )
      )
      createdAt <- Timestamp
        .fromInstant(instant)
        .left
        .map(ProtoDeserializationError.ValueConversionError("createdAt", _))
      result <- fromHttp(companionTemplateId, contractId, decodePayload)(
        javaTemplateId,
        contract.payload,
        ByteString.copyFrom(Base64.getDecoder.decode(contract.createdEventBlob)),
        createdAt.toInstant,
      )
    } yield result
  }

  def fromHttp[TCid <: ContractId[?], T <: DamlRecord[?]](
      companionTemplateId: Identifier,
      contractId: TCid,
      decodePayload: Json => T,
  )(
      javaTemplateId: Identifier,
      payload: Json,
      createdEventBlob: ByteString,
      createdAt: Instant,
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
    } yield Contract[TCid, T](
      identifier = javaTemplateId,
      contractId = contractId,
      payload = payload,
      createdEventBlob = createdEventBlob,
      createdAt = createdAt,
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
    * [[org.lfdecentralizedtrust.splice.util.Contract]] instance through passing:
    * - the `createdEventBlob` from the corresponding CreatedEvent
    * - the `identifier` of the interface if being parsed as interface view, and the one of the template otherwise
    */
  private def fromCodegenContract[TCid <: ContractId[?], T <: DamlRecord[?]](
      contract: CodegenContract[TCid, T],
      identifier: Identifier,
      createdEventBlob: ByteString,
      createdAt: Instant,
  ): Contract[TCid, T] = {
    Contract(
      identifier = identifier,
      contractId = contract.id,
      payload = contract.data,
      createdEventBlob = createdEventBlob,
      createdAt = createdAt,
    )
  }

  def fromCreatedEvent[TCid <: ContractId[?], T <: DamlRecord[?]](
      companion: Companion.Template[TCid, T]
  )(ev: CreatedEvent): Option[Contract[TCid, T]] = {
    JavaDecodeUtil
      .decodeCreated(companion)(ev)
      .map(fromCodegenContract(_, ev.getTemplateId, ev.getCreatedEventBlob, ev.createdAt))
  }

  def fromCreatedEvent[ICid <: ContractId[Marker], Marker, View <: DamlRecord[View]](
      companion: Companion.Interface[ICid, Marker, View]
  )(ev: CreatedEvent): Option[Contract[ICid, View]] = {
    JavaDecodeUtil
      .decodeCreated(companion)(ev)
      .map(contract =>
        fromCodegenContract(
          contract,
//          contract.getContractTypeId, // contains #package-name
          companion.getTemplateIdWithPackageId,
          ev.getCreatedEventBlob,
          ev.createdAt,
        )
      )
  }
}
