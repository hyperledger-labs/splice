package com.daml.network.util

import com.daml.ledger.javaapi.data.ContractMetadata
import com.daml.lf.data.Time.Timestamp
import com.daml.network.http.v0.definitions as http
import com.google.protobuf.ByteString
import org.apache.commons.codec.binary.Hex

import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit
import scala.util.Try

object ContractMetadataUtil {

  // Identical to Time.Timestamp.assertMicrosFromInstant but that is private.
  def instantToMicros(i: Instant) =
    TimeUnit.SECONDS.toMicros(i.getEpochSecond) + TimeUnit.NANOSECONDS.toMicros(i.getNano.toLong)

  def toHttp(contractMetadata: ContractMetadata): http.ContractMetadata = {

    val res = http.ContractMetadata(
      createdAt = Timestamp.assertFromInstant(contractMetadata.createdAt).toString,
      contractKeyHash = Hex.encodeHexString(contractMetadata.contractKeyHash.toByteArray),
      driverMetadata =
        Base64.getUrlEncoder.encodeToString(contractMetadata.driverMetadata.toByteArray),
    )
    res
  }

  def fromHttp(metadata: http.ContractMetadata): ContractMetadata = {
    // For backwards compatibility (required for ACS dumps) we also support an
    // older format where createdAt stores the micros since unix epoch and
    // driverMetadata is base16 encoded.
    val res = new ContractMetadata(
      Try(Timestamp.assertFromLong(metadata.createdAt.toLong))
        .getOrElse(Timestamp.assertFromString(metadata.createdAt))
        .toInstant,
      ByteString.copyFrom(Hex.decodeHex(metadata.contractKeyHash)),
      ByteString.copyFrom(
        Try(Hex.decodeHex(metadata.driverMetadata))
          .getOrElse(Base64.getUrlDecoder.decode(metadata.driverMetadata))
      ),
    )
    res
  }
}
