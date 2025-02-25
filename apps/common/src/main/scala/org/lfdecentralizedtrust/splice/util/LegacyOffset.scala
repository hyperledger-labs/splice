// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.digitalasset.daml.lf.data.{Bytes, Ref}
import com.google.protobuf.ByteString
import org.lfdecentralizedtrust.splice.util.LegacyOffset.beforeBegin

import java.nio.{ByteBuffer, ByteOrder}
import scala.util.{Failure, Success, Try}

/** @since canton 3.3
  * New offsets represented by [[com.digitalasset.canton.data.Offset]] are represented as Long only
  * As in our stores we store offsets as String we ported the code to convert between them and preserve compatibility
  */
final case class LegacyOffset(bytes: Bytes) extends Ordered[LegacyOffset] {
  override def compare(that: LegacyOffset): Int =
    Bytes.ordering.compare(this.bytes, that.bytes)

  def toByteString: ByteString = bytes.toByteString

  def toByteArray: Array[Byte] = bytes.toByteArray

  def toHexString: Ref.HexString = bytes.toHexString

  def toLong: Long =
    if (this == beforeBegin) 0L
    else ByteBuffer.wrap(bytes.toByteArray).getLong(1)
}

/** Ported from the 3.2 version https://github.com/digital-asset/canton/blob/release-line-3.2/community/ledger/ledger-api-core/src/main/scala/com/digitalasset/canton/platform/ApiOffset.scala
  */
object LegacyOffset {
  object Api {

    def tryFromString(s: String): Try[LegacyOffset] =
      fromString(s) match {
        case Left(msg) => Failure(new IllegalArgumentException(msg))
        case Right(offset) => Success(offset)
      }

    def fromString(s: String): Either[String, LegacyOffset] =
      Ref.HexString
        .fromString(s)
        .map(LegacyOffset.fromHexString)

    def assertFromString(s: String): LegacyOffset = tryFromString(s).fold(throw _, identity)

    // TODO(#18685) remove converter as it should be unused
    def assertFromStringToLongO(s: String): Option[Long] =
      Option.unless(s.isEmpty)(assertFromString(s).toLong)

    // TODO(#18685) remove converter as it should be unused
    def assertFromStringToLong(s: String): Long =
      assertFromStringToLongO(s).getOrElse(0L)

    def fromLong(l: Long): String =
      LegacyOffset.fromLong(l).toHexString
  }
  val beforeBegin: LegacyOffset = new LegacyOffset(Bytes.Empty)
  private val longBasedByteLength: Int = 9 // One byte for the version plus 8 bytes for Long
  private val versionUpstreamOffsetsAsLong: Byte = 0

  def fromByteString(bytes: ByteString) = new LegacyOffset(Bytes.fromByteString(bytes))

  def fromHexString(s: Ref.HexString) = new LegacyOffset(Bytes.fromHexString(s))

  def fromLong(l: Long): LegacyOffset =
    if (l == 0L) beforeBegin
    else
      LegacyOffset(
        com.digitalasset.daml.lf.data.Bytes.fromByteString(
          ByteString.copyFrom(
            ByteBuffer
              .allocate(longBasedByteLength)
              .order(ByteOrder.BIG_ENDIAN)
              .put(0, versionUpstreamOffsetsAsLong)
              .putLong(1, l)
          )
        )
      )

}
