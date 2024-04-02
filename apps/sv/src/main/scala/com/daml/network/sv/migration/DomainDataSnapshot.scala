package com.daml.network.sv.migration

import com.daml.network.http.v0.definitions as http
import com.daml.network.migration.Dar
import com.digitalasset.canton.crypto.Hash
import com.google.protobuf.ByteString

import java.time.Instant
import java.util.Base64

// TODO(#11100) Split domain data snapshots for validators and SVs to avoid
// the optional mess.
case class DomainDataSnapshot(
    genesisState: Option[ByteString],
    acsSnapshot: ByteString,
    authorizedStoreSnapshot: ByteString,
    acsTimestamp: Instant,
    dars: Seq[Dar],
    domainWasPaused: Boolean,
) {
  def toHttp: http.DomainDataSnapshot = http.DomainDataSnapshot(
    genesisState.map(s => Base64.getEncoder.encodeToString(s.toByteArray)),
    Base64.getEncoder.encodeToString(acsSnapshot.toByteArray),
    Base64.getEncoder.encodeToString(authorizedStoreSnapshot.toByteArray),
    acsTimestamp.toString,
    dars.map { dar =>
      val content = Base64.getEncoder.encodeToString(dar.content.toByteArray)
      http.Dar(dar.hash.toHexString, content)
    }.toVector,
    domainWasPaused,
  )
}

object DomainDataSnapshot {
  private val base64Decoder = Base64.getDecoder()
  def fromHttp(
      src: http.DomainDataSnapshot
  ): Either[String, DomainDataSnapshot] = {
    val genesisState = src.genesisState.map(s => ByteString.copyFrom(base64Decoder.decode(s)))
    val acsSnapshot = ByteString.copyFrom(base64Decoder.decode(src.acsSnapshot))
    val authorizedStoreSnapshot =
      ByteString.copyFrom(base64Decoder.decode(src.authorizedStoreSnapshot))
    val dars =
      src.dars.map { dar =>
        val decoded = base64Decoder.decode(dar.content)
        Dar(Hash.tryFromHexString(dar.hash), ByteString.copyFrom(decoded))
      }
    val acsTimestamp = Instant.parse(src.acsTimestamp)
    Right(
      DomainDataSnapshot(
        genesisState,
        acsSnapshot,
        authorizedStoreSnapshot,
        acsTimestamp,
        dars,
        src.domainWasHalted,
      )
    )
  }
}
