package com.daml.network.sv.migration

import com.daml.network.environment.{ParticipantAdminConnection, TopologyAdminConnection}
import com.daml.network.http.v0.definitions as http
import com.daml.network.identities.{NodeIdentitiesDump, NodeIdentitiesStore}
import com.daml.network.sv.LocalDomainNode
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.util.Codec
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.*
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

case class DomainNodeIdentities(
    svPartyId: PartyId,
    svcPartyId: PartyId,
    domainAlias: DomainAlias,
    domainId: DomainId,
    participant: NodeIdentitiesDump,
    sequencer: NodeIdentitiesDump,
    mediator: NodeIdentitiesDump,
) {
  def toHttp(): http.DomainNodeIdentities = http.DomainNodeIdentities(
    svPartyId.toProtoPrimitive,
    svcPartyId.toProtoPrimitive,
    domainAlias.toProtoPrimitive,
    domainId.toProtoPrimitive,
    participant.toHttp,
    sequencer.toHttp,
    mediator.toHttp,
  )
}

object DomainNodeIdentities {
  def fromHttp(
      src: http.DomainNodeIdentities
  ): Either[String, DomainNodeIdentities] = for {
    svPartyId <- Codec.decode(Codec.Party)(src.svPartyId)
    svcPartyId <- Codec.decode(Codec.Party)(src.svcPartyId)
    domainAlias <- DomainAlias.create(src.domainAlias)
    domainId <- Codec.decode(Codec.DomainId)(src.domainId)
    participant <- NodeIdentitiesDump.fromHttp(
      ParticipantId.tryFromProtoPrimitive,
      src.participant,
    )
    sequencer <- NodeIdentitiesDump.fromHttp(
      tryFromSequencerIdProtoPrimitive,
      src.sequencer,
    )
    mediator <- NodeIdentitiesDump.fromHttp(
      tryFromMediatorIdProtoPrimitive,
      src.mediator,
    )
  } yield {
    DomainNodeIdentities(
      svPartyId,
      svcPartyId,
      domainAlias,
      domainId,
      participant,
      sequencer,
      mediator,
    )
  }

  def getDomainNodeIdentities(
      participantAdminConnection: ParticipantAdminConnection,
      domainNode: LocalDomainNode,
      svcStore: SvSvcStore,
      domainAlias: DomainAlias,
      clock: Clock,
      loggerFactory: NamedLoggerFactory,
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[DomainNodeIdentities] = {
    def getNodeIdentitiesDump(adminConnection: TopologyAdminConnection) =
      new NodeIdentitiesStore(
        adminConnection,
        None,
        clock,
        loggerFactory,
      ).getNodeIdentitiesDump()

    for {
      globalDomain <- svcStore.getSvcRules().map(_.domain)
      participant <- getNodeIdentitiesDump(participantAdminConnection)
      sequencer <- getNodeIdentitiesDump(domainNode.sequencerAdminConnection)
      mediator <- getNodeIdentitiesDump(domainNode.mediatorAdminConnection)
    } yield DomainNodeIdentities(
      svcStore.key.svParty,
      svcStore.key.svcParty,
      domainAlias,
      globalDomain,
      participant,
      sequencer,
      mediator,
    )
  }

  def tryFromSequencerIdProtoPrimitive(sequencerId: String) = SequencerId
    .fromProtoPrimitive(sequencerId, "sequencerId")
    .fold(
      err => throw new IllegalArgumentException(err.message),
      identity,
    )

  def tryFromMediatorIdProtoPrimitive(mediatorId: String) = MediatorId
    .fromProtoPrimitive(mediatorId, "mediatorId")
    .fold(
      err => throw new IllegalArgumentException(err.message),
      identity,
    )
}
