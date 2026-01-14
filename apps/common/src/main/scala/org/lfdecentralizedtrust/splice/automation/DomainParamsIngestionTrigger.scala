// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import org.lfdecentralizedtrust.splice.environment.ParticipantAdminConnection
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyTransactionType.AuthorizedState
import org.lfdecentralizedtrust.splice.store.DomainParamsStore
import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

final class DomainParamsIngestionTrigger(
    synchronizerAlias: SynchronizerAlias,
    domainParamsStore: DomainParamsStore,
    participantAdminConnection: ParticipantAdminConnection,
    context: TriggerContext,
)(implicit ec: ExecutionContext, tracer: Tracer)
    extends PeriodicTaskTrigger(
      context.copy(triggerEnabledSync = TriggerEnabledSynchronization.Noop),
      quiet = true,
    ) {

  override def completeTask(
      task: PeriodicTaskTrigger.PeriodicTask
  )(implicit tc: TraceContext): Future[TaskOutcome] =
    participantAdminConnection.getSynchronizerId(synchronizerAlias).transformWith {
      case Failure(s: StatusRuntimeException) if s.getStatus.getCode == Status.Code.NOT_FOUND =>
        // This can happen during initialization, just skip it to reduce log noise.
        Future.successful(TaskNoop)
      case Failure(e) => Future.failed(e)
      case Success(synchronizerId) =>
        participantAdminConnection
          .lookupSynchronizerParametersState(
            TopologyStoreId.Synchronizer(synchronizerId),
            synchronizerId,
            AuthorizedState,
          )
          .flatMap {
            case None => Future.successful(TaskNoop)
            case Some(params) =>
              domainParamsStore
                .ingestDomainParams(params)
                .map(_ => TaskSuccess(show"Updated domain params to ${params.base.serial}"))
          }
    }
}
