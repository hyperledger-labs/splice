// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.confirmation

import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.ElectionRequest
import org.lfdecentralizedtrust.splice.config.Thresholds
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.util.AssignedContract
import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

/** Trigger to react to the creation of `ElectionRequest` contracts and complete the election if there are enough of them. */
class ElectionRequestTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    connection: SpliceLedgerConnection,
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
      currentDsoDelegate = dsoRules.payload.dsoDelegate
      self = store.key.svParty.toProtoPrimitive

      requiredNumRequests = Thresholds.requiredNumVotes(dsoRules)
      requestCids <- store.listElectionRequests(dsoRules).map(_.map(_.contractId))
      taskOutcome <-
        if (requestCids.size >= requiredNumRequests) {
          val cmd = dsoRules.exercise(
            _.exerciseDsoRules_ElectDsoDelegate(
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
                show"Successfully completed a delegate election to replace the delegate $currentDsoDelegate with ${dsoRules.payload.dsoDelegate}"
              )
            })
        } else
          Future.successful(
            TaskSuccess(
              show"not yet electing new delegate," +
                show" as there are only ${requestCids.size} out of" +
                show" the required $requiredNumRequests requests."
            )
          )
    } yield taskOutcome
  }
}
