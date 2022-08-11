package com.daml.network.scan.admin

import akka.actor.ActorSystem
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.admin.LedgerAutomationServiceOrchestrator
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, Lifecycle, SyncCloseable}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.participant.config.RemoteParticipantConfig
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TracerProvider
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContextExecutor

/** Manages background automation that runs on a CC Scan app.
  */
class ScanAutomationService(
    svcParty: PartyId,
    remoteParticipant: RemoteParticipantConfig,
    loggerFactory: NamedLoggerFactory,
    tracerProvider: TracerProvider,
    processingTimeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContextExecutor,
    actorSystem: ActorSystem,
    tracer: Tracer,
    executionSequencerFactory: ExecutionSequencerFactory,
) extends LedgerAutomationServiceOrchestrator(remoteParticipant, loggerFactory, tracerProvider)(
      ec,
      actorSystem,
      tracer,
      executionSequencerFactory,
    ) {
  override protected def timeouts: ProcessingTimeout = processingTimeouts

  override def readAs: PartyId = svcParty

  val (coinFlatStreamSubscription, readCcTransfersService) =
    // TODO(Arne): the subscription here should read from ledger start
    createService("ScanReadCcTransfersService") { connection =>
      new ReadCcTransfersService(connection, loggerFactory)
    }

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = Seq[AsyncOrSyncCloseable](
    SyncCloseable(
      "SVC automation services",
      Lifecycle.close(
        coinFlatStreamSubscription,
        readCcTransfersService,
      )(logger),
    )
  )
}
