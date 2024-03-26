package com.daml.network.sv.automation.confirmation

import org.apache.pekko.stream.Materializer
import com.daml.network.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.splice.dsorules.ElectionRequest
import com.daml.network.config.CNThresholds
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.sv.store.SvDsoStore
import com.daml.network.util.AssignedContract
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

/** Trigger to react to the creation of `ElectionRequest` contracts and complete the election if there are enough of them. */
class ElectionRequestTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    connection: CNLedgerConnection,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[ElectionRequest.ContractId, ElectionRequest](
      store,
      ElectionRequest.COMPANION,
    ) {

  override protected def completeTask(
      task: AssignedContract[
        ElectionRequest.ContractId,
        ElectionRequest,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      dsoRules <- store.getDsoRules()
      currentLeader = dsoRules.payload.leader
      self = store.key.svParty.toProtoPrimitive

      requiredNumRequests = CNThresholds.requiredNumVotes(dsoRules)
      requestCids <- store.listElectionRequests(dsoRules).map(_.map(_.contractId))
      taskOutcome <-
        if (requestCids.size >= requiredNumRequests) {
          val cmd = dsoRules.exercise(
            _.exerciseDsoRules_ElectLeader(
              self,
              requestCids.asJava,
            )
          )
          connection
            .submit(
              Seq(store.key.svParty),
              Seq(store.key.dsoParty),
              cmd,
            )
            .noDedup
            .yieldResultAndOffset()
            .flatMap(_ => store.getDsoRules())
            .map(dsoRules => {

              TaskSuccess(
                show"Successfully completed a leader election to replace the leader $currentLeader with ${dsoRules.payload.leader}"
              )
            })
        } else
          Future.successful(
            TaskSuccess(
              show"not yet electing new leader," +
                show" as there are only ${requestCids.size} out of" +
                show" the required $requiredNumRequests requests."
            )
          )
    } yield taskOutcome
  }
}
