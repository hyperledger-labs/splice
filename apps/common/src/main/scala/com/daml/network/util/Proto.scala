// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.util

import cats.syntax.traverse.*
import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.client.binding.Primitive
import com.daml.ledger.javaapi.data.codegen.{ContractCompanion, ContractId as JavaContractId}
import com.daml.lf.data.Numeric
import com.daml.network.v0
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.{DomainId as CantonDomainId, PartyId}
import com.digitalasset.canton.{DomainAlias, LfTimestamp}

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

  implicit val bigDecimalValue: Proto[BigDecimal, String] =
    new Proto[BigDecimal, String] {
      def encode(d: BigDecimal) = Numeric.toString(d.bigDecimal)
      def decode(e: String) = Numeric.fromString(e).map(scala.BigDecimal(_))
    }

  object BigDecimal extends ProtoCompanion[BigDecimal] {
    type Enc = String
    def instance = bigDecimalValue
  }

  implicit val javaBigDecimalValue: Proto[java.math.BigDecimal, String] =
    new Proto[java.math.BigDecimal, String] {
      def encode(d: java.math.BigDecimal) = Numeric.toString(d)
      def decode(e: String) = Numeric.fromString(e)
    }

  object JavaBigDecimal extends ProtoCompanion[java.math.BigDecimal] {
    type Enc = String
    def instance = javaBigDecimalValue
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

  implicit val timestampValue: Proto[CantonTimestamp, Long] =
    new Proto[CantonTimestamp, Long] {
      def encode(d: CantonTimestamp) = d.underlying.micros
      def decode(e: Long) = {
        LfTimestamp.fromLong(e).map(CantonTimestamp(_))
      }
    }

  object Timestamp extends ProtoCompanion[CantonTimestamp] {
    type Enc = Long
    def instance = timestampValue
  }

  implicit val domainIdValue: Proto[CantonDomainId, String] =
    new Proto[CantonDomainId, String] {
      def encode(d: CantonDomainId) = d.toProtoPrimitive
      def decode(e: String) = CantonDomainId.fromString(e)
    }

  object DomainId extends ProtoCompanion[CantonDomainId] {
    type Enc = String
    def instance = domainIdValue
  }

  implicit val connectedDomainsValue: Proto[Map[DomainAlias, CantonDomainId], v0.ConnectedDomains] =
    new Proto[Map[DomainAlias, CantonDomainId], v0.ConnectedDomains] {
      def encode(d: Map[DomainAlias, CantonDomainId]) =
        v0.ConnectedDomains(
          d.view.map { case (k, v) =>
            k.toProtoPrimitive -> Proto.encode(v)
          }.toMap
        )
      def decode(e: v0.ConnectedDomains) =
        e.connectedDomains.toList
          .traverse { case (k, v) =>
            for {
              k <- DomainAlias.create(k)
              v <- Proto.decode(DomainId)(v)
            } yield (k, v)
          }
          .map(_.toMap)
    }

  object ConnectedDomains extends ProtoCompanion[Map[DomainAlias, CantonDomainId]] {
    type Enc = v0.ConnectedDomains
    def instance = connectedDomainsValue
  }
}
