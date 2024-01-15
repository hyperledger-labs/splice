package com.daml.network.sv.automation.singlesv.membership

import cats.implicits.catsSyntaxParallelTraverse1
import com.daml.network.automation.*
import com.daml.network.codegen.java.cn.svcrules.SvcRules
import com.daml.network.environment.{ParticipantAdminConnection, RetryFor}
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.FutureInstances.parallelFuture
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.CollectionHasAsScala

/** Trigger that reacts to the assignment of SvcRules
  * It checks the members of the SVC as defined by the SvcRules members property,
  * with the members of the svc decentralized namespace as defined by the DecentralizedNamespaceDefinitionX,
  * and adds to the decentralized namespace any members that are missing.
  *
  * Adding the sv to the decentralized namespace only after it's already part of the svc guarantees that party migration has finished
  */
class SvOnboardingNamespaceProposalTrigger(
    override protected val context: TriggerContext,
    svcStore: SvSvcStore,
    participantAdminConnection: ParticipantAdminConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      SvcRules.ContractId,
      SvcRules,
    ](
      svcStore,
      SvcRules.COMPANION,
    ) {

  private val svcParty = svcStore.key.svcParty
  private val svParty = svcStore.key.svParty

  override protected def completeTask(
      task: AssignedContract[SvcRules.ContractId, SvcRules]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    participantAdminConnection
      .getDecentralizedNamespaceDefinition(task.domain, svcParty.uid.namespace)
      .flatMap { decentralizedNamespace =>
        val svcRulesPayload = task.contract.payload
        val svcRulesMembers = svcRulesPayload.members
          .keySet()
          .asScala
          .map(PartyId.tryFromProtoPrimitive)
          .toSeq

        val namespaceAdditions = svcRulesMembers
          .filter(svcMemberParty =>
            !decentralizedNamespace.mapping.owners.contains(svcMemberParty.uid.namespace)
          )
          // parallel to ensure that if one proposal is never accepted the rest of them are eventually accepted
          // this increases contention but the call will always redo the proposal when a new state is accepted
          .parTraverse { svcMemberParty =>
            logger.info(
              s"Proposing $svcMemberParty as member of the decentralized namespace, will wait for it to take effect."
            )
            participantAdminConnection
              .ensureDecentralizedNamespaceDefinitionAdditionProposalAccepted(
                task.domain,
                svcParty.uid.namespace,
                svcMemberParty.uid.namespace,
                svParty.uid.namespace.fingerprint,
                RetryFor.Automation,
              )
          }

        // TODO(#9256): enforce the following statement during onboarding
        // Parties are only hosted on participants with the same namespace which is also the namespace that is used in the decentralized namespace.
        // Therefore we remove the namespace from the decentralized namespace only if
        // a namespace is present in the offboardedMembers list and not present in the members list
        val namespaceRemovals = svcRulesPayload.offboardedMembers
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
          // parallel to ensure that if one proposal is never accepted the rest of them are eventually accepted
          // this increases contention but the call will always redo the proposal when a new state is accepted
          .parTraverse { svcMemberParty =>
            logger.info(
              s"Proposing $svcMemberParty to be removed from the decentralized namespace, will wait for it to take effect."
            )
            participantAdminConnection.ensureDecentralizedNamespaceDefinitionRemovalProposal(
              task.domain,
              svcParty.uid.namespace,
              svcMemberParty.uid.namespace,
              svParty.uid.namespace.fingerprint,
              RetryFor.Automation,
            )
          }

        val combinedProposals = namespaceAdditions.flatMap { additions =>
          namespaceRemovals.map { removals => additions ++ removals }
        }

        combinedProposals.map { proposals =>
          if (proposals.nonEmpty) TaskSuccess(show"Created proposals $proposals") else TaskStale
        }
      }
  }
}
