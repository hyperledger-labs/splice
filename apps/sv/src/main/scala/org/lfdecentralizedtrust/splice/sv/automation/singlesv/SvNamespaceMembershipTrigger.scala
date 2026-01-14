// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv

import org.lfdecentralizedtrust.splice.automation.*
import org.lfdecentralizedtrust.splice.environment.{ParticipantAdminConnection, RetryFor}
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.SvNamespaceMembershipTrigger.{
  AddToNamespace,
  NamespaceDiff,
  RemoveFromNamespace,
}
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.store.DsoRulesStore.DsoRulesWithSvNodeStates
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyTransactionType.AuthorizedState

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.CollectionHasAsScala

private class NamespaceMembership(
    dsoParty: PartyId,
    participantAdminConnection: ParticipantAdminConnection,
    logger: TracedLogger,
    val svDsoStore: SvDsoStore,
) extends DsoRulesTopologyStateReconciler[NamespaceDiff] {

  override def diffDsoRulesWithTopology(
      dsoRulesAndState: DsoRulesWithSvNodeStates
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Seq[NamespaceDiff]] = {
    val dsoRules = dsoRulesAndState.dsoRules
    participantAdminConnection
      .getDecentralizedNamespaceDefinition(
        dsoRules.domain,
        dsoParty.uid.namespace,
        AuthorizedState,
      )
      .map { decentralizedNamespace =>
        val dsoRulesSvs = dsoRules.contract.payload.svs
          .keySet()
          .asScala
          .map(PartyId.tryFromProtoPrimitive)
          .toSeq
        val namespaceAdditions = dsoRulesSvs
          .filter(svParty => !decentralizedNamespace.mapping.owners.contains(svParty.uid.namespace))
        // Parties are only hosted on participants with the same namespace which is also the namespace that is used in the decentralized namespace.
        // Therefore we remove the namespace from the decentralized namespace only if
        // a namespace is present in the offboardedSvs list and not present in the members list
        val namespaceRemovals = dsoRules.contract.payload.offboardedSvs
          .keySet()
          .asScala
          .map(PartyId.tryFromProtoPrimitive)
          .toSeq
          .filter(svParty =>
            decentralizedNamespace.mapping.owners
              .contains(svParty.uid.namespace) && !dsoRulesSvs
              .map(_.uid.namespace)
              .contains(svParty.uid.namespace)
          )
        namespaceAdditions.map[NamespaceDiff](
          AddToNamespace(dsoRules.domain, _)
        ) ++ namespaceRemovals
          .map[NamespaceDiff](
            RemoveFromNamespace(dsoRules.domain, _)
          )
      }
  }

  override def reconcileTask(
      task: NamespaceDiff
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[TaskOutcome] = task match {
    case AddToNamespace(domain, partyId) =>
      logger.info(
        s"Proposing $partyId as member of the decentralized namespace, will wait for it to take effect."
      )
      participantAdminConnection
        .ensureDecentralizedNamespaceDefinitionProposalAccepted(
          domain,
          dsoParty.uid.namespace,
          partyId.uid.namespace,
          // use lower retry number to just allow the trigger to retry instead of blocking
          RetryFor.ClientCalls,
        )
        .map(_ =>
          TaskSuccess(
            show"Party $partyId was add to the decentralized namespace ${dsoParty.uid.namespace}"
          )
        )
    case RemoveFromNamespace(domain, partyId) =>
      logger.info(
        s"Proposing $partyId to be removed from the decentralized namespace, will wait for it to take effect."
      )
      participantAdminConnection
        .ensureDecentralizedNamespaceDefinitionRemovalProposal(
          domain,
          dsoParty.uid.namespace,
          partyId.uid.namespace,
          // use lower retry number to just allow the trigger to retry instead of blocking
          RetryFor.ClientCalls,
        )
        .map(_ =>
          TaskSuccess(
            show"Party $partyId was removed from the decentralized namespace ${dsoParty.uid.namespace}"
          )
        )
  }

}

/** Trigger that checks the svs of the DSO as defined by the DsoRules svs property,
  * with the members of the dso decentralized namespace as defined by the DecentralizedNamespaceDefinition,
  * and adds to the decentralized namespace any members that are missing.
  *
  * Adding the sv to the decentralized namespace only after it's already part of the dso guarantees that party migration has finished
  */
class SvNamespaceMembershipTrigger(
    baseContext: TriggerContext,
    store: SvDsoStore,
    participantAdminConnection: ParticipantAdminConnection,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends SvTopologyStatePollingAndAssignedTrigger[NamespaceDiff](
      baseContext,
      store,
      Some(participantAdminConnection),
    ) {

  override val reconciler: DsoRulesTopologyStateReconciler[NamespaceDiff] =
    new NamespaceMembership(
      store.key.dsoParty,
      participantAdminConnection,
      logger,
      store,
    )

}

object SvNamespaceMembershipTrigger {

  sealed trait NamespaceDiff extends PrettyPrinting {
    def domain: SynchronizerId
  }

  case class AddToNamespace(domain: SynchronizerId, partyId: PartyId) extends NamespaceDiff {
    override def pretty: Pretty[this.type] =
      prettyOfClass(param("domain", _.domain), param("partyId", _.partyId))
  }

  case class RemoveFromNamespace(domain: SynchronizerId, partyId: PartyId) extends NamespaceDiff {
    override def pretty: Pretty[this.type] =
      prettyOfClass(param("domain", _.domain), param("partyId", _.partyId))
  }
}
