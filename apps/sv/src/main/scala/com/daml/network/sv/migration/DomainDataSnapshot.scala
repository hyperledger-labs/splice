package com.daml.network.sv.migration

import cats.syntax.either.*
import com.daml.network.http.v0.definitions as http
import com.daml.network.migration.Dar
import com.digitalasset.canton.crypto.Hash
import com.digitalasset.canton.topology.admin.v30.TopologyTransactions
import com.digitalasset.canton.topology.store.StoredTopologyTransactionsX
import com.digitalasset.canton.topology.store.StoredTopologyTransactionsX.GenericStoredTopologyTransactionsX
import com.google.protobuf.ByteString

import java.util.Base64

case class DomainDataSnapshot(
    topologySnapshot: GenericStoredTopologyTransactionsX,
    acsSnapshot: ByteString,
    dars: Seq[Dar],
) {
  def toHttp: http.DomainDataSnapshot = http.DomainDataSnapshot(
    Base64.getEncoder.encodeToString(topologySnapshot.toProtoV30.toByteArray),
    Base64.getEncoder.encodeToString(acsSnapshot.toByteArray),
    dars.map { dar =>
      val content = Base64.getEncoder.encodeToString(dar.content.toByteArray)
      http.Dar(dar.hash.toHexString, content)
    }.toVector,
  )
}

object DomainDataSnapshot {
  private val base64Decoder = Base64.getDecoder()
  def fromHttp(
      src: http.DomainDataSnapshot
  ) = for {
    topologySnapshot <- {
      val decoded = base64Decoder.decode(src.topologySnapshot)
      val proto = TopologyTransactions.parseFrom(decoded)
      StoredTopologyTransactionsX
        .fromProtoV30(proto)
        .leftMap(_ => "Failed to parse Topology Transactions")
    }
    acsSnapshot = {
      val decoded = base64Decoder.decode(src.acsSnapshot)
      ByteString.copyFrom(decoded)
    }
    dars = {
      src.dars.map { dar =>
        val decoded = base64Decoder.decode(dar.content)
        Dar(Hash.tryFromHexString(dar.hash), ByteString.copyFrom(decoded))
      }
    }

  } yield DomainDataSnapshot(
    topologySnapshot,
    acsSnapshot,
    dars,
  )

}
