// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.util

import cats.syntax.either._
import com.daml.api.util.TimestampConversion
import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.api.v1.value.Value
import com.daml.ledger.client.binding.Primitive
import com.daml.lf.data.Numeric
import com.digitalasset.canton.ProtoDeserializationError
import com.digitalasset.canton.topology.PartyId

/** Trait for values used in our protobuf requests.
  * Dec is the Scala representation while Enc is the protobuf representation, e.g., Proto[BigDecimal, String].
  */
trait Proto[Dec, Enc] {
  def encode(d: Dec): Enc
  def decode(e: Enc): Either[String, Dec]
}

/** Companion object to make type inference on Proto.decode work */
trait ProtoCompanion[Dec] {
  type Enc
  def instance: Proto[Dec, Enc]
}

object Proto {
  def encode[Dec, Enc](d: Dec)(implicit instance: Proto[Dec, Enc]): Enc =
    instance.encode(d)
  def decode[Dec](companion: ProtoCompanion[Dec])(e: companion.Enc): Either[String, Dec] =
    companion.instance.decode(e)
  def tryDecode[Dec](companion: ProtoCompanion[Dec])(e: companion.Enc): Dec =
    decode(companion)(e).fold(err => throw new IllegalArgumentException(err), identity)
  // Convenience wrapper because we can’t have a generic companion.
  def decodeContractId[T](e: String): Either[String, Primitive.ContractId[T]] =
    contractIdValue[T].decode(e)
  def decodeContractIdDeserialization[T](
      e: String
  ): Either[ProtoDeserializationError, Primitive.ContractId[T]] =
    contractIdValue[T].decode(e).leftMap(ProtoDeserializationError.OtherError)
  def tryDecodeContractId[T](e: String): Primitive.ContractId[T] =
    decodeContractId[T](e).fold(err => throw new IllegalArgumentException(err), identity)

  implicit val bigDecimalValue: Proto[BigDecimal, String] =
    new Proto[BigDecimal, String] {
      def encode(d: BigDecimal) = Numeric.toString(d.bigDecimal)
      def decode(e: String) = Numeric.fromString(e).map(scala.BigDecimal(_))
    }

  object BigDecimal extends ProtoCompanion[BigDecimal] {
    type Enc = String
    def instance = bigDecimalValue
  }

  implicit val partyValue: Proto[PartyId, String] = new Proto[PartyId, String] {
    def encode(d: PartyId) = d.toProtoPrimitive
    def decode(e: String) = PartyId.fromProtoPrimitive(e)
  }

  object Party extends ProtoCompanion[PartyId] {
    type Enc = String
    def instance = partyValue
  }

  implicit val codegenPartyValue: Proto[ApiTypes.Party, String] =
    new Proto[ApiTypes.Party, String] {
      def encode(d: ApiTypes.Party) = ApiTypes.Party.unwrap(d)
      def decode(e: String) = Right(ApiTypes.Party(e))
    }

  object CodegenParty extends ProtoCompanion[ApiTypes.Party] {
    type Enc = String
    def instance = codegenPartyValue
  }

  implicit def contractIdValue[T]: Proto[Primitive.ContractId[T], String] =
    new Proto[Primitive.ContractId[T], String] {
      def encode(d: Primitive.ContractId[T]) = ApiTypes.ContractId.unwrap(d)
      def decode(e: String) = Right(Primitive.ContractId(e))
    }

  implicit val timestampValue: Proto[Primitive.Timestamp, Long] =
    new Proto[Primitive.Timestamp, Long] {
      def encode(d: Primitive.Timestamp) = TimestampConversion.instantToMicros(d).value
      def decode(e: Long) = {
        val instant = TimestampConversion.microsToInstant(Value.Sum.Timestamp(e))
        Primitive.Timestamp.discardNanos(instant).toRight("Could not convert timestamp")
      }
    }

  object Timestamp extends ProtoCompanion[Primitive.Timestamp] {
    type Enc = Long
    def instance = timestampValue
  }
}
