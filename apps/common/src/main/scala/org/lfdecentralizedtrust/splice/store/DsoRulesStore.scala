// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.QueryResult
import org.lfdecentralizedtrust.splice.util.{AssignedContract, Contract, ContractWithState}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.participant.pretty.Implicits.prettyContractId
import com.digitalasset.canton.topology.{SynchronizerId, MediatorId, Member, ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import com.digitalasset.canton.util.ShowUtil.*

import java.time.Instant

trait DsoRulesStore extends AppStore {

  def lookupDsoRulesWithStateWithOffset()(implicit tc: TraceContext): Future[
    QueryResult[Option[
      ContractWithState[splice.dsorules.DsoRules.ContractId, splice.dsorules.DsoRules]
    ]]
  ] = multiDomainAcsStore
    .findAnyContractWithOffset(splice.dsorules.DsoRules.COMPANION)

  def lookupDsoRulesWithState()(implicit
      tc: TraceContext
  ): Future[
    Option[ContractWithState[splice.dsorules.DsoRules.ContractId, splice.dsorules.DsoRules]]
  ] =
    lookupDsoRulesWithStateWithOffset().map(_.value)

  def lookupDsoRules()(implicit
      tc: TraceContext
  ): Future[
    Option[AssignedContract[splice.dsorules.DsoRules.ContractId, splice.dsorules.DsoRules]]
  ] =
    lookupDsoRulesWithState().map(
      _.map(c =>
        c.toAssignedContract.getOrElse(
          throw Status.FAILED_PRECONDITION
            .withDescription(
              s"Could not convert DsoRules with cid ${c.contractId} to AssignedContract as it is in state ${c.state}"
            )
            .asRuntimeException
        )
      )
    )

  def getDsoRulesWithOffset()(implicit tc: TraceContext): Future[QueryResult[
    ContractWithState[splice.dsorules.DsoRules.ContractId, splice.dsorules.DsoRules]
  ]] = lookupDsoRulesWithStateWithOffset().map(_.sequence getOrElse (throw noActiveDsoRules))

  def getDsoRulesWithSvNodeState(svParty: PartyId)(implicit
      tc: TraceContext
  ): Future[DsoRulesStore.DsoRulesWithSvNodeState] = {
    for {
      dsoRules <- getDsoRules()
      svNodeState <- getSvNodeState(svParty)
    } yield DsoRulesStore.DsoRulesWithSvNodeState(dsoRules, svParty, svNodeState.contract)
  }

  def getDsoRulesWithState()(implicit
      tc: TraceContext
  ): Future[ContractWithState[splice.dsorules.DsoRules.ContractId, splice.dsorules.DsoRules]] =
    lookupDsoRulesWithState().map(
      _.getOrElse(
        throw Status.NOT_FOUND
          .withDescription("No active DsoRules contract")
          .asRuntimeException()
      )
    )

  def getDsoRules()(implicit
      tc: TraceContext
  ): Future[AssignedContract[splice.dsorules.DsoRules.ContractId, splice.dsorules.DsoRules]] =
    getDsoRulesWithState().map(c =>
      c.toAssignedContract.getOrElse(
        throw Status.FAILED_PRECONDITION
          .withDescription(
            s"Could not convert DsoRules with cid ${c.contractId} to AssignedContract as it is in state ${c.state}"
          )
          .asRuntimeException
      )
    )

  def lookupSvNodeState(svPartyId: PartyId)(implicit
      tc: TraceContext
  ): Future[Option[
    ContractWithState[
      splice.dso.svstate.SvNodeState.ContractId,
      splice.dso.svstate.SvNodeState,
    ]
  ]]

  def getSvNodeState(svPartyId: PartyId)(implicit
      tc: TraceContext
  ): Future[
    ContractWithState[
      splice.dso.svstate.SvNodeState.ContractId,
      splice.dso.svstate.SvNodeState,
    ]
  ] =
    lookupSvNodeState(svPartyId).map(
      _.getOrElse(
        throw Status.NOT_FOUND
          .withDescription(show"No SvNodeState found for $svPartyId")
          .asRuntimeException()
      )
    )

  def getDsoRulesWithSvNodeStates()(implicit
      tc: TraceContext
  ): Future[DsoRulesStore.DsoRulesWithSvNodeStates] = {
    for {
      // Note: at a certain size of the DSO, we'll be better off doing this join in the DB. We'll find out from our logs and performance tests.
      dsoRules <- getDsoRules()
      dsoSvParties = dsoRules.payload.svs.keySet().asScala.toSeq
      svNodeStates <- Future
        .traverse(dsoSvParties) { svPartyStr =>
          val svParty = PartyId.tryFromProtoPrimitive(svPartyStr)
          getSvNodeState(svParty).map(co => svParty -> co)
        }
        .map(_.toMap)
    } yield DsoRulesStore.DsoRulesWithSvNodeStates(dsoRules, svNodeStates)
  }

  def getDsoRulesWithStateWithSvNodeStates()(implicit
      tc: TraceContext
  ): Future[DsoRulesStore.DsoRulesWithStateWithSvNodeStates] = {
    for {
      // Note: at a certain size of the DSO, we'll be better off doing this join in the DB. We'll find out from our logs and performance tests.
      dsoRules <- getDsoRulesWithState()
      dsoSvParties = dsoRules.payload.svs.keySet().asScala.toSeq
      svNodeStates <- Future
        .traverse(dsoSvParties) { svPartyStr =>
          val svParty = PartyId.tryFromProtoPrimitive(svPartyStr)
          getSvNodeState(svParty).map(co => svParty -> co)
        }
        .map(_.toMap)
    } yield DsoRulesStore.DsoRulesWithStateWithSvNodeStates(dsoRules, svNodeStates)
  }

  private def noActiveDsoRules =
    Status.NOT_FOUND.withDescription("No active DsoRules contract").asRuntimeException()
}

object DsoRulesStore {

  case class DsoRulesWithStateWithSvNodeStates(
      dsoRules: ContractWithState[splice.dsorules.DsoRules.ContractId, splice.dsorules.DsoRules],
      svNodeStates: Map[
        PartyId,
        ContractWithState[splice.dso.svstate.SvNodeState.ContractId, splice.dso.svstate.SvNodeState],
      ],
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("state", _.dsoRules.state),
        param("dsoRulesCid", _.dsoRules.contractId),
        param("svNodeStates", _.svNodeStates),
      )
  }

  case class DsoRulesWithSvNodeStates(
      dsoRules: AssignedContract[splice.dsorules.DsoRules.ContractId, splice.dsorules.DsoRules],
      svNodeStates: Map[
        PartyId,
        ContractWithState[splice.dso.svstate.SvNodeState.ContractId, splice.dso.svstate.SvNodeState],
      ],
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("domain", _.dsoRules.domain),
        param("dsoRulesCid", _.dsoRules.contractId),
        param("svNodeStates", _.svNodeStates),
      )

    def currentSynchronizerNodeConfigs()
        : Seq[splice.dso.decentralizedsynchronizer.SynchronizerNodeConfig] = {
      // TODO(#998): make its callers work with soft-domain migration
      svNodeStates.values
        .flatMap(_.payload.state.synchronizerNodes.asScala.get(dsoRules.domain.toProtoPrimitive))
        .toSeq
    }

    def activeSvParticipantAndMediatorIds(synchronizerId: SynchronizerId): Seq[Member] = {
      val svParticipants = dsoRules.contract.payload.svs
        .values()
        .asScala
        .map(_.participantId)
        .toSeq
        .map(ParticipantId.tryFromProtoPrimitive)
      val offboardedSvParticipants = dsoRules.contract.payload.offboardedSvs
        .values()
        .asScala
        .map(_.participantId)
        .toSeq
        .map(ParticipantId.tryFromProtoPrimitive)
      val svMediators = svNodeStates.values
        .flatMap(
          _.payload.state.synchronizerNodes.asScala.get(synchronizerId.toProtoPrimitive).toList
        )
        .flatMap(_.mediator.toScala)
        .map(m =>
          MediatorId
            .fromProtoPrimitive(m.mediatorId, "mediator")
            .fold(err => throw new IllegalArgumentException(err.message), identity)
        )
      svParticipants.filterNot(offboardedSvParticipants.contains) ++ svMediators
    }

    def getSvNameInDso(svParty: PartyId): Future[String] =
      dsoRules.contract.payload.svs.asScala
        .get(svParty.toProtoPrimitive)
        .fold(
          Future.failed[String](
            Status.NOT_FOUND
              .withDescription(show"$svParty is not an active SV")
              .asRuntimeException()
          )
        )(info => Future.successful(info.name))

  }

  case class DsoRulesWithSvNodeState(
      dsoRules: AssignedContract[splice.dsorules.DsoRules.ContractId, splice.dsorules.DsoRules],
      svParty: PartyId,
      svNodeState: Contract[
        splice.dso.svstate.SvNodeState.ContractId,
        splice.dso.svstate.SvNodeState,
      ],
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("synchronizerId", _.dsoRules.domain),
        param("dsoRulesCid", _.dsoRules.contractId),
        param("svParty", _.svParty),
        param("svNodeState", _.svNodeState),
      )

    def isStale(
        store: MultiDomainAcsStore
    )(implicit tc: TraceContext, ec: ExecutionContext): Future[Boolean] =
      for {
        // TODO(#998): check whether we also need to compare the domain-id to detect staleness
        checkDsoRules <- store.lookupContractById(splice.dsorules.DsoRules.COMPANION)(
          dsoRules.contractId
        )
        checkSvNodeState <- store
          .lookupContractById(splice.dso.svstate.SvNodeState.COMPANION)(svNodeState.contractId)
      } yield checkDsoRules.isEmpty || checkSvNodeState.isEmpty

    def lookupSequencerConfigFor(
        decentralizedSynchronizerId: SynchronizerId,
        domainTimeLowerBound: Instant,
        migrationId: Long,
    ): Option[splice.dso.decentralizedsynchronizer.SequencerConfig] = {
      for {
        synchronizerNodeConfig <- svNodeState.payload.state.synchronizerNodes.asScala
          .get(decentralizedSynchronizerId.toProtoPrimitive)
        sequencerConfig <- synchronizerNodeConfig.sequencer.toScala
        if sequencerConfig.migrationId == migrationId && sequencerConfig.url.nonEmpty && sequencerConfig.availableAfter.toScala
          .exists(availableAfter => domainTimeLowerBound.isAfter(availableAfter))
      } yield sequencerConfig
    }
  }

}
