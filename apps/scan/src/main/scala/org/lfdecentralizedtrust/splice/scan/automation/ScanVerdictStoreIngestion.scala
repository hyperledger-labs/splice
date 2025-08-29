// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.automation

import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.scan.config.ScanAppBackendConfig
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanVerdictStore
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.Clock
import org.lfdecentralizedtrust.splice.util.HasHealth
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.admin.api.client.commands.MediatorScanCommands
import scala.util.{Failure, Success}

import scala.concurrent.{ExecutionContextExecutor}

import com.digitalasset.canton.networking.grpc.ClientChannelBuilder

import com.digitalasset.canton.networking.grpc.ForwardingStreamObserver

import com.digitalasset.canton.tracing.TraceContext

import com.digitalasset.canton.mediator.admin.v30
import io.grpc.stub.StreamObserver
import com.digitalasset.canton.data.CantonTimestamp
import io.circe.Json

class ScanVerdictStoreIngestion(
    config: ScanAppBackendConfig,
    clock: Clock,
    retryProvider: RetryProvider,
    protected val loggerFactory: NamedLoggerFactory,
    store: DbScanVerdictStore,
    storage: Storage,
    migrationId: Long,
    synchronizerId: SynchronizerId,
) (implicit
    ec: ExecutionContextExecutor
)
    extends HasHealth
    with AutoCloseable
    with NamedLogging {

  override def isHealthy: Boolean = true

  override def close(): Unit = ()

  private def getMediatorEvents()(implicit tc: TraceContext): Unit = {
    val mc = config.mediatorAdminClient
    logger.debug(
      s"getMediatorEvents mediatorAdminClient: endpoint=${mc.endpointAsString}, address=${mc.address}, port=${mc.port.unwrap}"
    )
    val channel = ClientChannelBuilder
      .createChannelBuilderToTrustedServer(config.mediatorAdminClient)
      .build()

    val streamObserver = new StreamObserver[v30.Verdict] {
      def onNext(value: v30.Verdict) = {
        logger.debug("getMediatorEvents: onNext received verdict")
        handleVerdict(value)
      }
      def onError(t: Throwable) = {
        logger.error("getMediatorEvents: verdict stream onError", t)
      }
      def onCompleted() = {
        logger.debug("getMediatorEvents: verdict stream onCompleted")
      }
    }

    val mediatorVerdicts = MediatorScanCommands.MediatorVerdicts(None, streamObserver)
    val rawStreamObserver = new ForwardingStreamObserver[v30.VerdictsResponse, v30.Verdict](mediatorVerdicts.observer, mediatorVerdicts.extractResults)
    mediatorVerdicts.doRequest(mediatorVerdicts.createService(channel), v30.VerdictsRequest(None), rawStreamObserver)
  }

  private def handleVerdict(
    verdict: v30.Verdict
  )(implicit tc: TraceContext): Unit = {
    logger.warn(s"getMediatorEvents: handleVerdict start")
    val transactionRootViews = verdict.getTransactionViews.rootViews
    def constructVerdictT(reason: Short): store.VerdictT = new store.VerdictT(
      rowId = 0,
      migrationId = migrationId,
      domainId = synchronizerId,
      recordTime = CantonTimestamp.fromProtoTimestamp(verdict.getRecordTime).getOrElse(throw new IllegalArgumentException("Invalid timestamp")),
      finalizationTime = CantonTimestamp.fromProtoTimestamp(verdict.getFinalizationTime).getOrElse(throw new IllegalArgumentException("Invalid timestamp")),
      submittingParticipantUid = verdict.submittingParticipantUid,
      verdictResult = reason,
      mediatorGroup = verdict.mediatorGroup,
      updateId = verdict.updateId,
      submittingParties = verdict.submittingParties,
      transactionRootViews = transactionRootViews,
    )

    def insertBoth(reason: Short) = {
      store.insertVerdict(constructVerdictT(reason)).onComplete {
        case Success(row_id) => {
          val txViews: Seq[store.TransactionViewT] =
            verdict.getTransactionViews.views
              .map { case (viewId, txView) =>
                val confirmingPartiesJson: Json = io.circe.Json.fromValues(
                  txView.confirmingParties.map { q =>
                    Json.obj(
                      "parties" -> Json.fromValues(q.parties.map(Json.fromString)),
                      "threshold" -> Json.fromInt(q.threshold)
                    )
                  }
                )
                new store.TransactionViewT(
                  verdictRowId = row_id.toLong,
                  viewId = viewId,
                  informees = txView.informees,
                  confirmingParties = confirmingPartiesJson,
                  subViews = txView.subViews,
                )
              }
              .toSeq
          store.insertTransactionViews(txViews)
        }
        case Failure(e) => {}
      }
   }

    logger.debug(s"getMediatorEvents: handleVerdict: doing insert")
    verdict.verdict match {
      case v30.VerdictResult.VERDICT_RESULT_ACCEPTED => {
        insertBoth(1)
      }
      case v30.VerdictResult.VERDICT_RESULT_REJECTED => {
        insertBoth(2)
      }
      case _ => {
        insertBoth(0)
      }
    }
  }

  getMediatorEvents()(TraceContext.createNew())
}
