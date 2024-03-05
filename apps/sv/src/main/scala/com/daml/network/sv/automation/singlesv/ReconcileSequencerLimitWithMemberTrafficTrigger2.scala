package com.daml.network.sv.automation.singlesv

import com.daml.network.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cc
import com.daml.network.environment.SequencerAdminConnection
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.sv.util.SvUtil
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.config.RequireTypes.NonNegativeLong
import com.digitalasset.canton.topology.{DomainId, Member}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

/** This trigger currently relies on enough SVs working on the same set traffic balance request around the same time.
  * TODO(#10597): Remove this constraint by retrying the reconciliation task every setBalanceRequestSubmissionWindowSize if not successful.
  */
class ReconcileSequencerLimitWithMemberTrafficTrigger2(
    override protected val context: TriggerContext,
    store: SvSvcStore,
    sequencerAdminConnection: SequencerAdminConnection,
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
    Member
      .fromProtoPrimitive_(memberTraffic.payload.memberId)
      .fold(
        err => {
          // Skip contracts with invalid member ids
          Future.successful(TaskSuccess(s"Skipping MemberTraffic with invalid memberId: ${err}"))
        },
        memberId => {
          store
            .getSvcRules()
            .flatMap(svcRules => {
              if (SvUtil.listActiveSvParticipantsAndMediators(svcRules).contains(memberId)) {
                // SVs are granted unlimited traffic and do not need to purchase it via MemberTraffic contracts.
                // While the top-up trigger for SV validators is disabled by default, we also explicitly ignore
                // SV related MemberTraffic contracts here as a safeguard for the case of 3rd party top-ups
                // of SV nodes or an SV validator misconfiguration that changes the defaults.
                Future
                  .successful(TaskSuccess(s"Skipping MemberTraffic contract for SV node $memberId"))
              } else {
                val domainId = DomainId.tryFromString(memberTraffic.payload.domainId)
                val trafficLimitOffset = svcRules.payload.initialTrafficState.asScala
                  .get(memberId.toProtoPrimitive)
                  .fold(0L)(_.consumedTraffic)
                reconcileExtraTrafficLimitForMember(memberId, domainId, trafficLimitOffset)
              }
            })
        },
      )
  }

  private def reconcileExtraTrafficLimitForMember(
      memberId: Member,
      domainId: DomainId,
      trafficLimitOffset: Long,
  )(implicit tc: TraceContext): Future[TaskSuccess] = {
    for {
      // Compute new extra traffic limit
      totalPurchasedTraffic <- store.getTotalPurchasedMemberTraffic(memberId, domainId)
      newExtraTrafficLimit = NonNegativeLong.tryCreate(trafficLimitOffset + totalPurchasedTraffic)

      // Fetch current extra traffic limit
      trafficState <- sequencerAdminConnection.lookupSequencerTrafficControlState(memberId)
      currentExtraTrafficLimit = trafficState
        .flatMap(_.trafficState.extraTrafficLimit)
        .fold(NonNegativeLong.zero)(_.toNonNegative)

      // Compare and reconcile old and new limits
      taskOutcome <-
        if (currentExtraTrafficLimit < newExtraTrafficLimit) {
          sequencerAdminConnection
            .ensureSequencerTrafficControlState(
              memberId,
              newExtraTrafficLimit,
            )
            .map(_ =>
              TaskSuccess(
                s"Updated extra traffic limit for member ${memberId} from ${currentExtraTrafficLimit} to ${newExtraTrafficLimit}"
              )
            )
        } else {
          Future(
            TaskSuccess(
              s"Skipping since traffic limit is already up to date (previous limit = ${currentExtraTrafficLimit}, new limit = ${newExtraTrafficLimit})."
            )
          )
        }
    } yield taskOutcome
  }

}
