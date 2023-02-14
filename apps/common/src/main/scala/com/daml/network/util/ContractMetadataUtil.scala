package com.daml.network.util

import com.daml.ledger.javaapi.data.ContractMetadata
import com.daml.network.http.v0.definitions as http
import com.google.protobuf.ByteString
import org.apache.commons.codec.binary.Hex

import java.time.Instant
import java.time.format.DateTimeFormatter

object ContractMetadataUtil {

  def tryToJson(contractMetadata: ContractMetadata): http.ContractMetadata = {

    val res = http.ContractMetadata(
      createdAt = DateTimeFormatter.ISO_INSTANT.format(contractMetadata.createdAt),
      contractKeyHash = Hex.encodeHexString(contractMetadata.contractKeyHash.toByteArray),
      driverMetadata = Hex.encodeHexString(contractMetadata.driverMetadata.toByteArray),
    )
    res
  }

  def tryFromJson(metadata: http.ContractMetadata): ContractMetadata = {
    val res = new ContractMetadata(
      Instant.from(DateTimeFormatter.ISO_INSTANT.parse(metadata.createdAt)),
      ByteString.copyFrom(Hex.decodeHex(metadata.contractKeyHash)),
      ByteString.copyFrom(Hex.decodeHex(metadata.driverMetadata)),
    )
    res
  }
}
