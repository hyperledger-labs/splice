package com.daml.network.sv.automation

import akka.stream.Materializer
import com.daml.network.automation.{
  OnReadyContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn.svcrules.ElectionRequest
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.store.MultiDomainAcsStore.ReadyContract
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.sv.util.SvUtil
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

/** Trigger to react to the creation of `ElectionRequest` contracts and complete the election if there are enough of them. */
class ElectionRequestTrigger(
    override protected val context: TriggerContext,
    store: SvSvcStore,
    connection: CNLedgerConnection,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnReadyContractTrigger.Template[ElectionRequest.ContractId, ElectionRequest](
      store,
      ElectionRequest.COMPANION,
    ) {

  override protected def completeTask(
      task: ReadyContract[
        ElectionRequest.ContractId,
        ElectionRequest,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      domainId <- store.domains.signalWhenConnected(store.defaultAcsDomain)
      svcRules <- store.getSvcRules()
      currentLeader = svcRules.payload.leader
      self = store.key.svParty.toProtoPrimitive

      requiredNumRequests = SvUtil.requiredNumVotes(svcRules)
      requestCids <- store.listElectionRequests(svcRules).map(_.map(_.contractId))
      taskOutcome <-
        if (requestCids.size >= requiredNumRequests) {
          val cmd = svcRules.contractId.exerciseSvcRules_ElectLeader(
            self,
            requestCids.asJava,
          )
          connection
            .submitWithResultAndOffsetNoDedup(
              Seq(store.key.svParty),
              Seq(store.key.svcParty),
              cmd,
              domainId,
            )
            .flatMap(_ => store.getSvcRules())
            .map(svcRules => {

              TaskSuccess(
                show"Successfully completed a leader election to replace the leader $currentLeader with ${svcRules.payload.leader}"
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
