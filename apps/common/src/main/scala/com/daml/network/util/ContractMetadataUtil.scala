package com.daml.network.util

import com.daml.lf.data.Time.Timestamp
import com.daml.ledger.javaapi.data.ContractMetadata
import com.daml.network.http.v0.definitions as http
import com.google.protobuf.ByteString
import org.apache.commons.codec.binary.Hex

import java.time.Instant
import java.util.concurrent.TimeUnit

object ContractMetadataUtil {

  // Identical to Time.Timestamp.assertMicrosFromInstant but that is private.
  def instantToMicros(i: Instant) =
    TimeUnit.SECONDS.toMicros(i.getEpochSecond) + TimeUnit.NANOSECONDS.toMicros(i.getNano.toLong)

  def toJson(contractMetadata: ContractMetadata): http.ContractMetadata = {

    val res = http.ContractMetadata(
      createdAt = instantToMicros(contractMetadata.createdAt).toString,
      contractKeyHash = Hex.encodeHexString(contractMetadata.contractKeyHash.toByteArray),
      driverMetadata = Hex.encodeHexString(contractMetadata.driverMetadata.toByteArray),
    )
    res
  }

  def fromJson(metadata: http.ContractMetadata): ContractMetadata = {
    val res = new ContractMetadata(
      Timestamp.assertFromLong(metadata.createdAt.toLong).toInstant,
      ByteString.copyFrom(Hex.decodeHex(metadata.contractKeyHash)),
      ByteString.copyFrom(Hex.decodeHex(metadata.driverMetadata)),
    )
    res
  }
}
