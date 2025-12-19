// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import cats.implicits.toBifunctorOps
import com.daml.ledger.javaapi.data.codegen.{
  ContractCompanion,
  InterfaceCompanion,
  ContractId as JavaContractId,
}
import com.digitalasset.daml.lf.data.Numeric
import com.digitalasset.canton.{LfTimestamp, topology}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.{
  MediatorId,
  ParticipantId,
  PartyId,
  SequencerId,
  UniqueIdentifier,
}
import io.grpc.Status
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round

import java.time.{OffsetDateTime, ZoneOffset}
import scala.util.matching.Regex

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

  private[this] val decimalScale = Numeric.Scale.assertFromInt(10)
  private[this] def encodeJavaBigDecimal(d: java.math.BigDecimal): String =
    Numeric
      .assertFromBigDecimal(decimalScale, d)
      .toScaledString

  // A variant of Numeric.fromString specialized to Decimal that allows numbers w/o a decimal point.
  private[this] val decimalFormat = """-?\d{1,28}(\.\d{1,10})?"""
  private[this] val decimalFormatPattern = new Regex(decimalFormat).pattern
  private[this] def decodeJavaBigDecimal(s: String): Either[String, java.math.BigDecimal] = {
    if (decimalFormatPattern.matcher(s).matches())
      Numeric.fromBigDecimal(decimalScale, new java.math.BigDecimal(s))
    else
      Left(s"""Invalid Decimal string "$s", as it does not match "$decimalFormat"""")
  }

  def encode[Dec, Enc](d: Dec)(implicit instance: Codec[Dec, Enc]): Enc =
    instance.encode(d)
  def decode[Dec](companion: CodecCompanion[Dec])(e: companion.Enc): Either[String, Dec] =
    companion.instance.decode(e)
  def tryDecode[Dec](companion: CodecCompanion[Dec])(e: companion.Enc): Dec =
    decode(companion)(e).fold(err => failedToDecode(err), identity)

  def decodeJavaContractId[TC, TCid, T](companion: ContractCompanion[TC, TCid, T])(
      e: String
  ): Either[String, TCid] =
    Right(companion.toContractId(new JavaContractId(e)))
  def tryDecodeJavaContractId[TC, TCid, T](
      companion: ContractCompanion[TC, TCid, T]
  )(e: String): TCid =
    decodeJavaContractId(companion)(e)
      .fold(err => failedToDecode(err), identity)
  def encodeContractId[TCid <: JavaContractId[?]](d: TCid): String = d.contractId

  def decodeJavaContractIdInterface[I, Id, View](companion: InterfaceCompanion[I, Id, View])(
      e: String
  ): Either[String, Id] =
    Right(companion.toContractId(new JavaContractId(e)))
  def tryDecodeJavaContractIdInterface[I, Id, View](
      companion: InterfaceCompanion[I, Id, View]
  )(e: String): Id =
    decodeJavaContractIdInterface(companion)(e)
      .fold(err => failedToDecode(err), identity)

  implicit val bigDecimalValue: Codec[BigDecimal, String] =
    new Codec[BigDecimal, String] {
      def encode(d: BigDecimal) = encodeJavaBigDecimal(d.bigDecimal)
      def decode(e: String) = decodeJavaBigDecimal(e).map(scala.BigDecimal(_))
    }

  object BigDecimal extends CodecCompanion[BigDecimal] {
    type Enc = String
    def instance = bigDecimalValue
  }

  implicit val javaBigDecimalValue: Codec[java.math.BigDecimal, String] =
    new Codec[java.math.BigDecimal, String] {
      def encode(d: java.math.BigDecimal) = encodeJavaBigDecimal(d)
      def decode(e: String) = decodeJavaBigDecimal(e)
    }

  object JavaBigDecimal extends CodecCompanion[java.math.BigDecimal] {
    type Enc = String
    def instance = javaBigDecimalValue
  }

  implicit val partyValue: Codec[PartyId, String] = new Codec[PartyId, String] {
    def encode(d: PartyId) = d.filterString
    def decode(e: String) =
      UniqueIdentifier.fromProtoPrimitive_(e).leftMap(_.message).map(PartyId(_))
  }

  object Party extends CodecCompanion[PartyId] {
    type Enc = String
    def instance = partyValue
  }

  implicit val participantValue: Codec[ParticipantId, String] = new Codec[ParticipantId, String] {
    def encode(d: ParticipantId) = d.filterString
    def decode(e: String) = ParticipantId.fromProtoPrimitive(e, "participant").left.map(_.message)
  }

  object Participant extends CodecCompanion[ParticipantId] {
    type Enc = String
    def instance = participantValue
  }

  implicit val sequencerValue: Codec[SequencerId, String] = new Codec[SequencerId, String] {
    def encode(d: SequencerId) = d.toProtoPrimitive
    def decode(e: String) = SequencerId.fromProtoPrimitive(e, "sequencer").left.map(_.message)
  }

  object Sequencer extends CodecCompanion[SequencerId] {
    type Enc = String
    def instance = sequencerValue
  }

  implicit val mediatorValue: Codec[MediatorId, String] = new Codec[MediatorId, String] {
    def encode(d: MediatorId) = d.toProtoPrimitive
    def decode(e: String) = MediatorId.fromProtoPrimitive(e, "mediator").left.map(_.message)
  }

  object Mediator extends CodecCompanion[MediatorId] {
    type Enc = String
    def instance = mediatorValue
  }

  implicit val timestampValue: Codec[CantonTimestamp, Long] =
    new Codec[CantonTimestamp, Long] {
      def encode(d: CantonTimestamp) = d.underlying.micros
      def decode(e: Long) = {
        LfTimestamp.fromLong(e).map(CantonTimestamp(_))
      }
    }

  implicit val roundValue: Codec[Round, Long] =
    new Codec[Round, Long] {
      def encode(d: Round): Long = d.number
      def decode(e: Long): Right[Nothing, Round] = Right(new Round(e))
    }

  object Timestamp extends CodecCompanion[CantonTimestamp] {
    type Enc = Long
    def instance = timestampValue
  }

  private val timestampOffsetDateTimeValue: Codec[CantonTimestamp, OffsetDateTime] =
    new Codec[CantonTimestamp, OffsetDateTime] {
      def encode(d: CantonTimestamp) = d.toInstant.atOffset(ZoneOffset.UTC)
      def decode(t: OffsetDateTime) = {
        CantonTimestamp.fromInstant(t.toInstant)
      }
    }

  object OffsetDateTime extends CodecCompanion[CantonTimestamp] {
    type Enc = OffsetDateTime
    def instance = timestampOffsetDateTimeValue
  }

  implicit val synchronizerIdValue: Codec[topology.SynchronizerId, String] =
    new Codec[topology.SynchronizerId, String] {
      def encode(d: topology.SynchronizerId) = d.toProtoPrimitive
      def decode(e: String) = topology.SynchronizerId.fromString(e)
    }

  object SynchronizerId extends CodecCompanion[topology.SynchronizerId] {
    type Enc = String
    def instance = synchronizerIdValue
  }

  private def failedToDecode(err: String) = {
    throw Status.INVALID_ARGUMENT.withDescription(s"Failed to decode: $err").asRuntimeException()
  }
}
