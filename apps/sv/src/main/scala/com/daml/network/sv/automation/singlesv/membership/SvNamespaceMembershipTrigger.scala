package com.daml.network.sv.automation.singlesv.membership

import cats.implicits.catsSyntaxParallelTraverse1
import com.daml.network.automation.*
import com.daml.network.codegen.java.cn
import com.daml.network.codegen.java.cn.svcrules.SvcRules
import com.daml.network.environment.{ParticipantAdminConnection, RetryFor}
import com.daml.network.sv.automation.singlesv.membership.SvNamespaceMembershipTrigger.{
  AddToNamespace,
  NamespaceDiff,
  RemoveFromNamespace,
}
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.FutureInstances.parallelFuture
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
) {

  def diffSvcRulesWithTopology(
      svcRules: AssignedContract[SvcRules.ContractId, SvcRules]
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Seq[NamespaceDiff]] = {
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
  def reconcileDiff(
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
class SvNamespaceMembershipParallelTrigger(
    override protected val context: TriggerContext,
    svcStore: SvSvcStore,
    participantAdminConnection: ParticipantAdminConnection,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[NamespaceDiff] {

  private val svcParty = svcStore.key.svcParty
  private val svParty = svcStore.key.svParty
  private val namespaceMembership =
    new NamespaceMembership(svParty, svcParty, participantAdminConnection, logger)

  override protected def retrieveTasks()(implicit tc: TraceContext): Future[Seq[NamespaceDiff]] = {
    svcStore.getSvcRules().flatMap { svcRules =>
      namespaceMembership.diffSvcRulesWithTopology(svcRules)
    }
  }

  override protected def completeTask(
      task: NamespaceDiff
  )(implicit tc: TraceContext): Future[TaskOutcome] = namespaceMembership.reconcileDiff(task)

  override protected def isStaleTask(
      task: NamespaceDiff
  )(implicit tc: TraceContext): Future[Boolean] = {
    for {
      svcRules <- svcStore.getSvcRules()
      decentralizedNamespace <- participantAdminConnection.getDecentralizedNamespaceDefinition(
        svcRules.domain,
        svcParty.uid.namespace,
      )
    } yield {
      task match {
        case AddToNamespace(_, partyId) =>
          decentralizedNamespace.mapping.owners.contains(partyId.uid.namespace)
        case RemoveFromNamespace(_, partyId) =>
          !decentralizedNamespace.mapping.owners.contains(partyId.uid.namespace)
      }
    }
  }
}

class SvNamespaceMembershipAssignedTrigger(
    override protected val context: TriggerContext,
    store: SvSvcStore,
    participantAdminConnection: ParticipantAdminConnection,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      cn.svcrules.SvcRules.ContractId,
      cn.svcrules.SvcRules,
    ](
      store,
      cn.svcrules.SvcRules.COMPANION,
    ) {

  private val namespaceMembership = new NamespaceMembership(
    store.key.svParty,
    store.key.svcParty,
    participantAdminConnection,
    logger,
  )

  override protected def completeTask(task: AssignedContract[SvcRules.ContractId, SvcRules])(
      implicit tc: TraceContext
  ): Future[TaskOutcome] = {
    namespaceMembership.diffSvcRulesWithTopology(task).flatMap { diff =>
      if (diff.nonEmpty) {
        logger.info(s"Applying namespace membership diff: $diff")
      }
      diff
        .parTraverse(namespaceMembership.reconcileDiff)
        .map(results =>
          TaskSuccess(
            s"Reconciled namespace membership: $results"
          )
        )
    }
  }

}

object SvNamespaceMembershipTrigger {

  sealed trait NamespaceDiff extends PrettyPrinting

  case class AddToNamespace(domain: DomainId, partyId: PartyId) extends NamespaceDiff {
    override def pretty: Pretty[this.type] =
      prettyOfClass(param("domain", _.domain), param("partyId", _.partyId))
  }

  case class RemoveFromNamespace(domain: DomainId, partyId: PartyId) extends NamespaceDiff {
    override def pretty: Pretty[this.type] =
      prettyOfClass(param("domain", _.domain), param("partyId", _.partyId))
  }
}
