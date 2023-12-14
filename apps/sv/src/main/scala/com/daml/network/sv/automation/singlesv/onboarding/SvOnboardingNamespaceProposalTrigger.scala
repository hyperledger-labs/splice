package com.daml.network.sv.automation.singlesv.onboarding

import org.apache.pekko.stream.Materializer
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
        svcRulesPayload.members
          .keySet()
          .asScala
          .map(PartyId.tryFromProtoPrimitive)
          .toSeq
          .filter(svcMemberParty =>
            !decentralizedNamespace.mapping.owners.contains(svcMemberParty.uid.namespace)
          )
          // parallel to ensure that if one proposal is never accepted the rest of them are eventually accepted
          // this increases contention but the call will always redo the proposal when a new state is accepted
          .parTraverse { svcMemberParty =>
            logger.info(
              s"Proposing $svcMemberParty as member of the decentralized namespace, will wait for it to take effect."
            )
            participantAdminConnection.ensureDecentralizedNamespaceDefinitionProposalAccepted(
              task.domain,
              svcParty.uid.namespace,
              svcMemberParty.uid.namespace,
              svParty.uid.namespace.fingerprint,
              RetryFor.Automation,
            )
          }
          .map { proposals =>
            if (proposals.nonEmpty) TaskSuccess(show"Created proposals $proposals") else TaskStale
          }
      }
  }
}
