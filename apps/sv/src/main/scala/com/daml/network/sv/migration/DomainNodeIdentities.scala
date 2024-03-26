package com.daml.network.sv.migration

import com.daml.network.environment.{ParticipantAdminConnection, TopologyAdminConnection}
import com.daml.network.http.v0.definitions as http
import com.daml.network.identities.{NodeIdentitiesDump, NodeIdentitiesStore}
import com.daml.network.sv.LocalDomainNode
import com.daml.network.sv.store.SvDsoStore
import com.daml.network.util.Codec
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.*
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

case class DomainNodeIdentities(
    svPartyId: PartyId,
    dsoPartyId: PartyId,
    domainAlias: DomainAlias,
    domainId: DomainId,
    participant: NodeIdentitiesDump,
    sequencer: NodeIdentitiesDump,
    mediator: NodeIdentitiesDump,
) {
  def toHttp(): http.DomainNodeIdentities = http.DomainNodeIdentities(
    svPartyId.toProtoPrimitive,
    dsoPartyId.toProtoPrimitive,
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
    dsoPartyId <- Codec.decode(Codec.Party)(src.dsoPartyId)
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
      dsoPartyId,
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
      dsoStore: SvDsoStore,
      domainAlias: DomainAlias,
      loggerFactory: NamedLoggerFactory,
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[DomainNodeIdentities] = {
    def getNodeIdentitiesDump(adminConnection: TopologyAdminConnection) =
      new NodeIdentitiesStore(
        adminConnection,
        None,
        loggerFactory,
      ).getNodeIdentitiesDump()

    for {
      globalDomain <- dsoStore.getDsoRules().map(_.domain)
      participant <- getNodeIdentitiesDump(participantAdminConnection)
      sequencer <- getNodeIdentitiesDump(domainNode.sequencerAdminConnection)
      mediator <- getNodeIdentitiesDump(domainNode.mediatorAdminConnection)
    } yield DomainNodeIdentities(
      dsoStore.key.svParty,
      dsoStore.key.dsoParty,
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
