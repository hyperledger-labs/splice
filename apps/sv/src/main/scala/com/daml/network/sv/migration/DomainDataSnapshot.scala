package com.daml.network.sv.migration

import cats.syntax.either.*
import cats.syntax.traverse.*
import com.daml.network.http.v0.definitions as http
import com.daml.network.migration.Dar
import com.digitalasset.canton.crypto.Hash
import com.digitalasset.canton.topology.admin.v30.TopologyTransactions
import com.digitalasset.canton.topology.store.StoredTopologyTransactionsX
import com.digitalasset.canton.topology.store.StoredTopologyTransactionsX.GenericStoredTopologyTransactionsX
import com.google.protobuf.ByteString

import java.util.Base64

// TODO(#11100) Split domain data snapshots for validators and SVs to avoid
// the optional mess.
case class DomainDataSnapshot(
    genesisState: Option[ByteString],
    // TODO(#11098) Stop replaying vetted packages separately.
    vettedPackages: Option[GenericStoredTopologyTransactionsX],
    acsSnapshot: ByteString,
    dars: Seq[Dar],
) {
  def toHttp: http.DomainDataSnapshot = http.DomainDataSnapshot(
    genesisState.map(s => Base64.getEncoder.encodeToString(s.toByteArray)),
    vettedPackages.map(pkgs => Base64.getEncoder.encodeToString(pkgs.toProtoV30.toByteArray)),
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
  ) = {
    val genesisState = src.genesisState.map(s => ByteString.copyFrom(base64Decoder.decode(s)))
    val acsSnapshot = ByteString.copyFrom(base64Decoder.decode(src.acsSnapshot))
    val dars =
      src.dars.map { dar =>
        val decoded = base64Decoder.decode(dar.content)
        Dar(Hash.tryFromHexString(dar.hash), ByteString.copyFrom(decoded))
      }
    for {
      vettedPackages <- src.vettedPackages.traverse { pkgs =>
        val decoded = base64Decoder.decode(pkgs)
        val proto = TopologyTransactions.parseFrom(decoded)
        StoredTopologyTransactionsX
          .fromProtoV30(proto)
          .leftMap(_ => "Failed to parse Topology Transactions")
      }
    } yield DomainDataSnapshot(
      genesisState,
      vettedPackages,
      acsSnapshot,
      dars,
    )
  }
}
