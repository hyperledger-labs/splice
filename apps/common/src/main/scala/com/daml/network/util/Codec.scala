// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.util

import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.client.binding.Primitive
import com.daml.ledger.javaapi.data.codegen.{ContractCompanion, ContractId as JavaContractId}
import com.daml.lf.data.Numeric
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.LfTimestamp

/** Trait for values used in our requests.
  * Dec is the Scala representation while Enc is the representation used in code generated for the serialization format, e.g., Codec[BigDecimal, String].
  */
trait Codec[Dec, Enc] {
  def encode(d: Dec): Enc
  def decode(e: Enc): Either[String, Dec]
}

/** Companion object to make type inference on Codec.decode work */
trait CodecCompanion[Dec] {
  type Enc
  def instance: Codec[Dec, Enc]
}

object Codec {
  def encode[Dec, Enc](d: Dec)(implicit instance: Codec[Dec, Enc]): Enc =
    instance.encode(d)
  def decode[Dec](companion: CodecCompanion[Dec])(e: companion.Enc): Either[String, Dec] =
    companion.instance.decode(e)
  def tryDecode[Dec](companion: CodecCompanion[Dec])(e: companion.Enc): Dec =
    decode(companion)(e).fold(err => throw new IllegalArgumentException(err), identity)
  // Convenience wrapper because we can’t have a generic companion.
  def decodeContractId[T](e: String): Either[String, Primitive.ContractId[T]] =
    contractIdValue[T].decode(e)
  def tryDecodeContractId[T](e: String): Primitive.ContractId[T] =
    decodeContractId[T](e).fold(err => throw new IllegalArgumentException(err), identity)

  def decodeJavaContractId[TC, TCid, T](companion: ContractCompanion[TC, TCid, T])(
      e: String
  ): Either[String, TCid] =
    Right(companion.toContractId(new JavaContractId(e)))
  def tryDecodeJavaContractId[TC, TCid, T](
      companion: ContractCompanion[TC, TCid, T]
  )(e: String): TCid =
    decodeJavaContractId(companion)(e)
      .fold(err => throw new IllegalArgumentException(err), identity)
  def encodeContractId[TCid <: JavaContractId[_]](d: TCid): String = d.contractId

  implicit val bigDecimalValue: Codec[BigDecimal, String] =
    new Codec[BigDecimal, String] {
      def encode(d: BigDecimal) = Numeric.toString(d.bigDecimal)
      def decode(e: String) = Numeric.fromString(e).map(scala.BigDecimal(_))
    }

  object BigDecimal extends CodecCompanion[BigDecimal] {
    type Enc = String
    def instance = bigDecimalValue
  }

  implicit val javaBigDecimalValue: Codec[java.math.BigDecimal, String] =
    new Codec[java.math.BigDecimal, String] {
      def encode(d: java.math.BigDecimal) = Numeric.toString(d)
      def decode(e: String) = Numeric.fromString(e)
    }

  object JavaBigDecimal extends CodecCompanion[java.math.BigDecimal] {
    type Enc = String
    def instance = javaBigDecimalValue
  }

  implicit val partyValue: Codec[PartyId, String] = new Codec[PartyId, String] {
    def encode(d: PartyId) = d.filterString
    def decode(e: String) = PartyId.fromString(e)
  }

  object Party extends CodecCompanion[PartyId] {
    type Enc = String
    def instance = partyValue
  }

  implicit val codegenPartyValue: Codec[ApiTypes.Party, String] =
    new Codec[ApiTypes.Party, String] {
      def encode(d: ApiTypes.Party) = ApiTypes.Party.unwrap(d)
      def decode(e: String) = Right(ApiTypes.Party(e))
    }

  object CodegenParty extends CodecCompanion[ApiTypes.Party] {
    type Enc = String
    def instance = codegenPartyValue
  }

  implicit def contractIdValue[T]: Codec[Primitive.ContractId[T], String] =
    new Codec[Primitive.ContractId[T], String] {
      def encode(d: Primitive.ContractId[T]) = ApiTypes.ContractId.unwrap(d)
      def decode(e: String) = Right(Primitive.ContractId(e))
    }

  implicit val timestampValue: Codec[CantonTimestamp, Long] =
    new Codec[CantonTimestamp, Long] {
      def encode(d: CantonTimestamp) = d.underlying.micros
      def decode(e: Long) = {
        LfTimestamp.fromLong(e).map(CantonTimestamp(_))
      }
    }

  object Timestamp extends CodecCompanion[CantonTimestamp] {
    type Enc = Long
    def instance = timestampValue
  }
}
