// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.util

import com.daml.ledger.javaapi.data as JavaApi
import com.daml.ledger.javaapi.data.{CreatedEvent, ExercisedEvent, Identifier}
import com.daml.ledger.javaapi.data.codegen.DefinedDataType
import com.daml.ledger.javaapi.data.codegen.json.JsonLfReader

import scala.util.Try

/** A codec for converting [[com.daml.ledger.javaapi.data.Value]] to/from JSON strings.
  *
  * This codec produces compact and human readable JSON values, but has several drawbacks:
  * - It can only encode or decode values for known types.
  *   More precisely, the java API value must be a contract argument or choice argument/result
  *   for a template for which the Java codegen output has been bundled with the application.
  *   See [[com.daml.network.util.ContractCompanions.allDecoders]] for the list of supported templates.
  *   Templates are matched by qualified name.
  * - The encoded values do not contain type information.
  *   For example, decimal, contract id, or text values are all encoded as strings.
  *   Users need type information to interpret encoded values.
  * - The encoded values depend on the Java codegen, and users need to be careful when upgrading the
  *   daml code (remember that daml templates can be upgraded by adding optional fields at the end):
  *   - Downgrade: Any fields known to the codegen but not included in the original Java API value
  *     will be included as explicit `null` values in the encoded JSON output.
  *   - Upgrade: Any fields not known to the codegen but included in the original Java API value
  *     will be silently dropped in the encoded JSON output.
  *
  *  Note: a similar (or perhaps even equivalent) codec is implemented in [[com.digitalasset.canton.daml.lf.value.json.ApiCodecCompressed]],
  *  and the decoding part in [[com.daml.network.util.TemplateJsonDecoder]].
  */
object ValueJsonCodecCodegen {
  def serializableContractPayload(
      event: CreatedEvent
  ): Either[String, String] = {
    import scala.language.existentials
    for {
      companion <- ContractCompanions.lookup(event.getTemplateId)
      contract = companion.fromCreatedEvent(event)
    } yield contract.data.toJson
  }

  def deserializableContractPayload(
      templateId: Identifier,
      payload: String,
  ): Either[String, JavaApi.DamlRecord] = for {
    companion <- ContractCompanions.lookup(templateId)
    data <- Try(companion.fromJson(payload)).toEither.left.map(_.getMessage)
  } yield data.toValue

  def serializeChoiceArgument(
      event: ExercisedEvent
  ): Either[String, String] = for {
    companion <- ContractCompanions.lookup(event.getTemplateId)
    choiceCompanion <- ContractCompanions.lookupChoice(companion, event.getChoice)
    arg <- Try(choiceCompanion.argTypeDecoder.decode(event.getChoiceArgument)).toEither.left
      .map(_.getMessage)
    json = choiceCompanion.argJsonEncoder(arg).intoString()
  } yield json

  def deserializeChoiceArgument(
      templateId: Identifier,
      choice: String,
      argument: String,
  ): Either[String, JavaApi.DamlRecord] = for {
    companion <- ContractCompanions.lookup(templateId)
    choiceCompanion <- ContractCompanions.lookupChoice(companion, choice)
    arg <- Try(choiceCompanion.argJsonDecoder.decode(new JsonLfReader(argument))).toEither.left
      .map(_.getMessage)
  } yield arg.toValue

  def serializeChoiceResult(
      event: ExercisedEvent
  ): Either[String, String] = for {
    companion <- ContractCompanions.lookup(event.getTemplateId)
    choiceCompanion <- ContractCompanions.lookupChoice(companion, event.getChoice)
    ret <- Try(choiceCompanion.returnTypeDecoder.decode(event.getExerciseResult)).toEither.left
      .map(_.getMessage)
    json = choiceCompanion.resultJsonEncoder(ret).intoString()
  } yield json

  def deserializeChoiceResult(
      templateId: Identifier,
      choice: String,
      result: String,
  ): Either[String, JavaApi.Value] = for {
    companion <- ContractCompanions.lookup(templateId)
    choiceCompanion <- ContractCompanions.lookupChoice(companion, choice)
    res <- Try(choiceCompanion.resultJsonDecoder.decode(new JsonLfReader(result))).toEither.left
      .map(_.getMessage)
    value <- choiceResultToValue(res)
  } yield value

  /** In the ledger API, the result of a choice is represented as a JavaApi.Value.
    * In the Java codegen however, the result of a choice can be a number of different types
    * with no common supertype. For example, a daml list is mapped to a java.util.List.
    *
    * Unfortunately com.daml.ledger.javaapi.data.codegen.Choice has no method to convert the result type
    * to a JavaApi.Value
    */
  private def choiceResultToValue(result: Any): Either[String, JavaApi.Value] = result match {
    // If the result type is a named record
    case ddt: DefinedDataType[?] => Right(ddt.toValue)

    // If the result type is Unit
    case v: JavaApi.Value => Right(v)

    // Our 1st party daml code happens to never use anything but named records and Unit,
    // but the result type can also one of many other types with no common supertype.
    case _ => Left(s"Unsupported choice result type: ${result.getClass}")
  }

}
