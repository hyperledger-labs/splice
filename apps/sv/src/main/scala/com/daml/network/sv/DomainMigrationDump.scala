package com.daml.network.sv

import cats.syntax.traverse.*
import com.daml.network.http.v0.definitions as http
import cats.syntax.either.*
import com.daml.network.environment.{ParticipantAdminConnection, TopologyAdminConnection}
import com.daml.network.identities.{NodeIdentitiesDump, NodeIdentitiesStore}
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.sv.DomainMigrationDump.Dar
import com.daml.network.sv.DomainNodeIdentitiesDump.{
  DomainNodeIdentities,
  tryFromMediatorIdProtoPrimitive,
  tryFromSequencerIdProtoPrimitive,
}
import com.daml.network.util.Codec
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.crypto.Hash
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.protocol.v30.TopologyTransactions
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.store.StoredTopologyTransactionsX
import com.digitalasset.canton.topology.store.StoredTopologyTransactionsX.GenericStoredTopologyTransactionsX
import com.digitalasset.canton.topology.{DomainId, ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import com.google.protobuf.ByteString
import io.circe.Json
import io.circe.syntax.*

import java.time.Instant
import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}

case class DomainMigrationDump(
    migrationId: Long,
    svPartyId: PartyId,
    svcPartyId: PartyId,
    domainAlias: DomainAlias,
    domainId: DomainId,
    nodeIdentities: DomainNodeIdentities,
    topologySnapshot: GenericStoredTopologyTransactionsX,
    acsSnapshot: ByteString,
    dars: Seq[Dar],
) {
  def toHttp: http.GetDomainMigrationDumpResponse = http.GetDomainMigrationDumpResponse(
    migrationId,
    svPartyId.toProtoPrimitive,
    svcPartyId.toProtoPrimitive,
    domainAlias.toProtoPrimitive,
    domainId.toProtoPrimitive,
    http.DomainNodeIdentities(
      nodeIdentities.participant.toHttp,
      nodeIdentities.sequencer.toHttp,
      nodeIdentities.mediator.toHttp,
    ),
    Base64.getEncoder.encodeToString(topologySnapshot.toProtoV30.toByteArray),
    Base64.getEncoder.encodeToString(acsSnapshot.toByteArray),
    dars.map { dar =>
      val content = Base64.getEncoder.encodeToString(dar.content.toByteArray)
      http.Dar(dar.hash.toHexString, content)
    }.toVector,
  )

  def toJson: Json = {
    toHttp.asJson
  }
}

object DomainMigrationDump {
  def fromHttp(
      response: http.GetDomainMigrationDumpResponse
  ): Either[String, DomainMigrationDump] = for {
    svPartyId <- Codec.decode(Codec.Party)(response.svPartyId)
    svcPartyId <- Codec.decode(Codec.Party)(response.svcPartyId)
    domainAlias <- DomainAlias.create(response.domainAlias)
    domainId <- Codec.decode(Codec.DomainId)(response.domainId)
    participantIdentities <- NodeIdentitiesDump.fromHttp(
      ParticipantId.tryFromProtoPrimitive,
      response.identities.participant,
    )
    sequencerIdentities <- NodeIdentitiesDump.fromHttp(
      tryFromSequencerIdProtoPrimitive,
      response.identities.sequencer,
    )
    mediatorIdentities <- NodeIdentitiesDump.fromHttp(
      tryFromMediatorIdProtoPrimitive,
      response.identities.mediator,
    )
    topologySnapshot <- {
      val decoded = Base64.getDecoder().decode(response.topologySnapshot)
      val proto = TopologyTransactions.parseFrom(decoded)
      StoredTopologyTransactionsX
        .fromProtoV30(proto)
        .leftMap(_ => "Failed to parse Topology Transactions")
    }
    acsSnapshot = {
      val decoded = Base64.getDecoder().decode(response.acsSnapshot)
      ByteString.copyFrom(decoded)
    }
    dars = {
      response.dars.map { dar =>
        val decoded = Base64.getDecoder().decode(dar.content)
        Dar(Hash.tryFromHexString(dar.hash), ByteString.copyFrom(decoded))
      }
    }
  } yield DomainMigrationDump(
    response.migrationId,
    svPartyId,
    svcPartyId,
    domainAlias,
    domainId,
    nodeIdentities = DomainNodeIdentities(
      participantIdentities,
      sequencerIdentities,
      mediatorIdentities,
    ),
    topologySnapshot = topologySnapshot,
    acsSnapshot = acsSnapshot,
    dars = dars,
  )

  final case class Dar(hash: Hash, content: ByteString)

  def getDomainMigrationDump(
      domainAlias: DomainAlias,
      participantAdminConnection: ParticipantAdminConnection,
      domainNode: LocalDomainNode,
      loggerFactory: NamedLoggerFactory,
      svcStore: SvSvcStore,
      clock: Clock,
      migrationId: Long,
      domainPausedTime: Instant,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[DomainMigrationDump] = {
    def newNodeIdentitiesStore(adminConnection: TopologyAdminConnection) =
      new NodeIdentitiesStore(
        adminConnection,
        None,
        clock,
        loggerFactory,
      )

    for {
      participantIdentities <- newNodeIdentitiesStore(participantAdminConnection)
        .getNodeIdentitiesDump()
      sequencerIdentities <- newNodeIdentitiesStore(domainNode.sequencerAdminConnection)
        .getNodeIdentitiesDump()
      mediatorIdentities <- newNodeIdentitiesStore(domainNode.mediatorAdminConnection)
        .getNodeIdentitiesDump()
      globalDomain <- svcStore.getSvcRules().map(_.domain)
      topologySnapshot <- domainNode.sequencerAdminConnection.getTopologySnapshot(
        globalDomain,
        None,
        Some(tryFromInstant(domainPausedTime)),
      )
      acsSnapshot <- participantAdminConnection.downloadAcsSnapshot(
        Set(svcStore.key.svParty, svcStore.key.svcParty)
      )
      darDescriptions <- participantAdminConnection.listDars()
      dars <- darDescriptions.traverse { dar =>
        val hash = Hash.tryFromHexString(dar.hash)
        participantAdminConnection.lookupDar(hash).map(_.map(Dar(hash, _)))
      }
    } yield DomainMigrationDump(
      migrationId,
      svcStore.key.svParty,
      svcStore.key.svcParty,
      domainAlias,
      globalDomain,
      DomainNodeIdentities(
        participantIdentities,
        sequencerIdentities,
        mediatorIdentities,
      ),
      topologySnapshot = topologySnapshot,
      acsSnapshot = acsSnapshot,
      dars.flatten,
    )
  }

  def getDomainPausedTime(
      participantAdminConnection: ParticipantAdminConnection,
      svcStore: SvSvcStore,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[Instant]] = for {
    globalDomain <- svcStore.getSvcRules().map(_.domain)
    domainParamsTopologyResult <- participantAdminConnection.getDomainParametersState(
      globalDomain
    )
    isDomainPaused =
      domainParamsTopologyResult.mapping.parameters.maxRatePerParticipant == NonNegativeInt.zero
  } yield
    if (isDomainPaused) Some(domainParamsTopologyResult.base.validFrom)
    else None

  private def tryFromInstant(time: Instant) =
    CantonTimestamp
      .fromInstant(time)
      .fold(
        err =>
          throw new IllegalArgumentException(
            s"cannot parse the instant $time for the topology snapshot: $err"
          ),
        identity,
      )
}
