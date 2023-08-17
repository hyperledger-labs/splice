package com.daml.network.sv.automation.singlesv

import akka.stream.Materializer
import com.daml.network.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cc
import com.daml.network.environment.ParticipantAdminConnection
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.topology.{DomainId, Member}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class ReconcileSequencerLimitWithMemberTrafficTrigger(
    override protected val context: TriggerContext,
    store: SvSvcStore,
    participantAdminConnection: ParticipantAdminConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      cc.globaldomain.MemberTraffic.ContractId,
      cc.globaldomain.MemberTraffic,
    ](
      store,
      cc.globaldomain.MemberTraffic.COMPANION,
    ) {

  override def completeTask(
      memberTraffic: AssignedContract[
        cc.globaldomain.MemberTraffic.ContractId,
        cc.globaldomain.MemberTraffic,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val memberId = Member
      .fromProtoPrimitive(memberTraffic.payload.memberId, "")
      .fold(
        err => throw new IllegalArgumentException(err.message),
        identity,
      )
    val domainId = DomainId.tryFromString(memberTraffic.payload.domainId)
    for {
      // Compute new extra traffic limit
      svcRules <- store.getSvcRules()
      trafficLimitOffset = svcRules.payload.initialTrafficState.asScala
        .get(memberId.toProtoPrimitive)
        .fold(0L)(_.consumedTraffic)
      totalPurchasedTraffic <- store.getTotalPurchasedMemberTraffic(memberId, domainId)
      newExtraTrafficLimit = trafficLimitOffset + totalPurchasedTraffic

      // Fetch current extra traffic limit
      currentExtraTrafficLimit <- participantAdminConnection
        .lookupTrafficControlState(domainId, memberId)
        .map(_.fold(0L)(_.mapping.totalExtraTrafficLimit.value))

      // Compare and reconcile old and new limits
      taskOutcome <-
        if (currentExtraTrafficLimit < newExtraTrafficLimit)
          participantAdminConnection
            .getParticipantId()
            .flatMap(svParticipantId =>
              participantAdminConnection
                .ensureTrafficControlState(
                  domainId,
                  memberId,
                  newExtraTrafficLimit,
                  svParticipantId.uid.namespace.fingerprint,
                )
                .map(_ =>
                  TaskSuccess(
                    s"Updated extra traffic limit for member ${memberId} to ${newExtraTrafficLimit}"
                  )
                )
            )
        else {
          Future(TaskSuccess("Skipping since traffic limit is already up to date"))
        }
    } yield taskOutcome
  }

}
