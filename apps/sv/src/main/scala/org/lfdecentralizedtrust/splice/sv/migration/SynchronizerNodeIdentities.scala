// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.migration

import org.lfdecentralizedtrust.splice.environment.{
  ParticipantAdminConnection,
  TopologyAdminConnection,
}
import org.lfdecentralizedtrust.splice.http.v0.definitions as http
import org.lfdecentralizedtrust.splice.identities.{NodeIdentitiesDump, NodeIdentitiesStore}
import org.lfdecentralizedtrust.splice.sv.LocalSynchronizerNode
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.util.Codec
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.*
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

case class SynchronizerNodeIdentities(
    svPartyId: PartyId,
    dsoPartyId: PartyId,
    domainAlias: DomainAlias,
    domainId: DomainId,
    participant: NodeIdentitiesDump,
    sequencer: NodeIdentitiesDump,
    mediator: NodeIdentitiesDump,
) {
  def toHttp(): http.SynchronizerNodeIdentities = http.SynchronizerNodeIdentities(
    svPartyId.toProtoPrimitive,
    dsoPartyId.toProtoPrimitive,
    domainAlias.toProtoPrimitive,
    domainId.toProtoPrimitive,
    participant.toHttp,
    sequencer.toHttp,
    mediator.toHttp,
  )
}

object SynchronizerNodeIdentities {
  def fromHttp(
      src: http.SynchronizerNodeIdentities
  ): Either[String, SynchronizerNodeIdentities] = for {
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
    SynchronizerNodeIdentities(
      svPartyId,
      dsoPartyId,
      domainAlias,
      domainId,
      participant,
      sequencer,
      mediator,
    )
  }

  def getSynchronizerNodeIdentities(
      participantAdminConnection: ParticipantAdminConnection,
      synchronizerNode: LocalSynchronizerNode,
      dsoStore: SvDsoStore,
      domainAlias: DomainAlias,
      loggerFactory: NamedLoggerFactory,
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[SynchronizerNodeIdentities] = {
    def getNodeIdentitiesDump(adminConnection: TopologyAdminConnection) =
      new NodeIdentitiesStore(
        adminConnection,
        None,
        loggerFactory,
      ).getNodeIdentitiesDump()

    for {
      domainId <- dsoStore.getDsoRules().map(_.domain)
      participant <- getNodeIdentitiesDump(participantAdminConnection)
      sequencer <- getNodeIdentitiesDump(synchronizerNode.sequencerAdminConnection)
      mediator <- getNodeIdentitiesDump(synchronizerNode.mediatorAdminConnection)
    } yield SynchronizerNodeIdentities(
      dsoStore.key.svParty,
      dsoStore.key.dsoParty,
      domainAlias,
      domainId,
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
