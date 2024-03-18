package com.daml.network.sv.automation.singlesv.membership

import com.daml.network.automation.*
import com.daml.network.environment.{ParticipantAdminConnection, RetryFor}
import com.daml.network.sv.automation.singlesv.membership.SvNamespaceMembershipTrigger.{
  AddToNamespace,
  NamespaceDiff,
  RemoveFromNamespace,
}
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.sv.store.SvSvcStore.SvcRulesWithMemberNodeStates
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.CollectionHasAsScala

private class NamespaceMembership(
    svParty: PartyId,
    svcParty: PartyId,
    participantAdminConnection: ParticipantAdminConnection,
    logger: TracedLogger,
    val svSvcStore: SvSvcStore,
) extends SvcRulesTopologyStateReconciler[NamespaceDiff] {

  override def diffSvcRulesWithTopology(
      svcRulesAndState: SvcRulesWithMemberNodeStates
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Seq[NamespaceDiff]] = {
    val svcRules = svcRulesAndState.svcRules
    participantAdminConnection
      .getDecentralizedNamespaceDefinition(
        svcRules.domain,
        svcParty.uid.namespace,
      )
      .map { decentralizedNamespace =>
        val svcRulesMembers = svcRules.contract.payload.members
          .keySet()
          .asScala
          .map(PartyId.tryFromProtoPrimitive)
          .toSeq
        val namespaceAdditions = svcRulesMembers
          .filter(svcMemberParty =>
            !decentralizedNamespace.mapping.owners.contains(svcMemberParty.uid.namespace)
          )
        // Parties are only hosted on participants with the same namespace which is also the namespace that is used in the decentralized namespace.
        // Therefore we remove the namespace from the decentralized namespace only if
        // a namespace is present in the offboardedMembers list and not present in the members list
        val namespaceRemovals = svcRules.contract.payload.offboardedMembers
          .keySet()
          .asScala
          .map(PartyId.tryFromProtoPrimitive)
          .toSeq
          .filter(svcMemberParty =>
            decentralizedNamespace.mapping.owners
              .contains(svcMemberParty.uid.namespace) && !svcRulesMembers
              .map(_.uid.namespace)
              .contains(svcMemberParty.uid.namespace)
          )
        namespaceAdditions.map[NamespaceDiff](
          AddToNamespace(svcRules.domain, _)
        ) ++ namespaceRemovals
          .map[NamespaceDiff](
            RemoveFromNamespace(svcRules.domain, _)
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
          svcParty.uid.namespace,
          partyId.uid.namespace,
          svParty.uid.namespace.fingerprint,
          // use lower retry number to just allow the trigger to retry instead of blocking
          RetryFor.ClientCalls,
        )
        .map(_ =>
          TaskSuccess(
            show"Party $partyId was add to the decentralized namespace ${svcParty.uid.namespace}"
          )
        )
    case RemoveFromNamespace(domain, partyId) =>
      logger.info(
        s"Proposing $partyId to be removed from the decentralized namespace, will wait for it to take effect."
      )
      participantAdminConnection
        .ensureDecentralizedNamespaceDefinitionRemovalProposal(
          domain,
          svcParty.uid.namespace,
          partyId.uid.namespace,
          svParty.uid.namespace.fingerprint,
          // use lower retry number to just allow the trigger to retry instead of blocking
          RetryFor.ClientCalls,
        )
        .map(_ =>
          TaskSuccess(
            show"Party $partyId was removed from the decentralized namespace ${svcParty.uid.namespace}"
          )
        )
  }

}

/** Trigger that checks the members of the SVC as defined by the SvcRules members property,
  * with the members of the svc decentralized namespace as defined by the DecentralizedNamespaceDefinitionX,
  * and adds to the decentralized namespace any members that are missing.
  *
  * Adding the sv to the decentralized namespace only after it's already part of the svc guarantees that party migration has finished
  */
class SvNamespaceMembershipTrigger(
    override protected val context: TriggerContext,
    store: SvSvcStore,
    participantAdminConnection: ParticipantAdminConnection,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends SvTopologyStatePollingAndAssignedTrigger[NamespaceDiff](
      context,
      store,
    ) {

  override val reconciler = new NamespaceMembership(
    store.key.svParty,
    store.key.svcParty,
    participantAdminConnection,
    logger,
    store,
  )

}

object SvNamespaceMembershipTrigger {

  sealed trait NamespaceDiff extends PrettyPrinting {
    def domain: DomainId
  }

  case class AddToNamespace(domain: DomainId, partyId: PartyId) extends NamespaceDiff {
    override def pretty: Pretty[this.type] =
      prettyOfClass(param("domain", _.domain), param("partyId", _.partyId))
  }

  case class RemoveFromNamespace(domain: DomainId, partyId: PartyId) extends NamespaceDiff {
    override def pretty: Pretty[this.type] =
      prettyOfClass(param("domain", _.domain), param("partyId", _.partyId))
  }
}
