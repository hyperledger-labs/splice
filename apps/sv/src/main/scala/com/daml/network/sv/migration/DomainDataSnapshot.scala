package com.daml.network.sv.migration

import cats.syntax.either.*
import cats.syntax.traverse.*
import com.daml.network.environment.ParticipantAdminConnection
import com.daml.network.http.v0.definitions as http
import com.daml.network.sv.migration.DomainDataSnapshot.Dar
import com.daml.network.sv.store.SvSvcStore
import com.digitalasset.canton.crypto.Hash
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.admin.v30.TopologyTransactions
import com.digitalasset.canton.topology.store.StoredTopologyTransactionsX
import com.digitalasset.canton.topology.store.StoredTopologyTransactionsX.GenericStoredTopologyTransactionsX
import com.digitalasset.canton.tracing.TraceContext
import com.google.protobuf.ByteString

import java.time.Instant
import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}

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
  def fromHttp(
      src: http.DomainDataSnapshot
  ) = for {
    topologySnapshot <- {
      val decoded = Base64.getDecoder().decode(src.topologySnapshot)
      val proto = TopologyTransactions.parseFrom(decoded)
      StoredTopologyTransactionsX
        .fromProtoV30(proto)
        .leftMap(_ => "Failed to parse Topology Transactions")
    }
    acsSnapshot = {
      val decoded = Base64.getDecoder().decode(src.acsSnapshot)
      ByteString.copyFrom(decoded)
    }
    dars = {
      src.dars.map { dar =>
        val decoded = Base64.getDecoder().decode(dar.content)
        Dar(Hash.tryFromHexString(dar.hash), ByteString.copyFrom(decoded))
      }
    }

  } yield DomainDataSnapshot(
    topologySnapshot,
    acsSnapshot,
    dars,
  )

  def getDomainDataSnapshot(
      participantAdminConnection: ParticipantAdminConnection,
      svcStore: SvSvcStore,
      timestamp: Instant,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[DomainDataSnapshot] = for {
    globalDomain <- svcStore.getSvcRules().map(_.domain)
    topologySnapshotWithProposals <- participantAdminConnection
      .getTopologySnapshot(
        globalDomain,
        CantonTimestamp.tryFromInstant(timestamp),
      )
    topologySnapshot = topologySnapshotWithProposals.filter(_.transaction.isProposal == false)
    acsSnapshot <- participantAdminConnection.downloadAcsSnapshot(
      Set(svcStore.key.svParty, svcStore.key.svcParty)
    )
    darDescriptions <- participantAdminConnection.listDars()
    dars <- darDescriptions.traverse { dar =>
      val hash = Hash.tryFromHexString(dar.hash)
      participantAdminConnection.lookupDar(hash).map(_.map(Dar(hash, _)))
    }

  } yield DomainDataSnapshot(
    topologySnapshot,
    acsSnapshot,
    dars.flatten,
  )

  final case class Dar(hash: Hash, content: ByteString)
}
