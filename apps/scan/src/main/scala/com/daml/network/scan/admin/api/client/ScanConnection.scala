package com.daml.network.scan.admin.api.client

import com.daml.network.admin.api.client.AppConnection
import com.daml.network.codegen.CC.{CoinRules as coinRulesCodegen}
import com.daml.network.scan.admin.api.client.commands.GrpcScanAppClient
import com.digitalasset.canton.config.{ClientConfig, ProcessingTimeout}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.{Status, StatusRuntimeException}

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContextExecutor, Future}

/** Connection to the admin API of CC Scan. This is used by other apps
  * to query for the SVC party id.
  */
final class ScanConnection(
    config: ClientConfig,
    timeouts: ProcessingTimeout,
    loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContextExecutor)
    extends AppConnection(config, timeouts, loggerFactory) {
  // cached SVC reference.
  private val svcRef: AtomicReference[Option[PartyId]] = new AtomicReference(None)

  override val serviceName = "scan"

  /** Query for the SVC party id. This caches the result internally so
    * clients can call this repeatedly without having to implement caching themselves.
    */
  def getSvcPartyId()(implicit traceContext: TraceContext): Future[PartyId] = {
    val prev = svcRef.get()
    prev match {
      case Some(partyId) => Future.successful(partyId)
      case None =>
        for {
          partyId <- runCmd(GrpcScanAppClient.GetSvcPartyId())
        } yield {
          // The party id never changes so we don’t need to worry about concurrent setters writing different values.
          svcRef.set(Some(partyId))
          partyId
        }
    }
  }

  def getTransferContext()(implicit
      traceContext: TraceContext
  ): Future[GrpcScanAppClient.TransferContext] = {
    runCmd(GrpcScanAppClient.GetTransferContext())
  }

  def getAppTransferContext()(implicit
      traceContext: TraceContext
  ): Future[coinRulesCodegen.AppTransferContext] =
    getTransferContext().map { context =>
      val coinRules = context.coinRules.getOrElse(throw notFound("No active CoinRules contract"))
      val openMiningRound = context.latestOpenMiningRound.getOrElse(
        throw notFound("No active OpenMiningRound contract")
      )
      coinRulesCodegen.AppTransferContext(
        coinRules.contractId,
        openMiningRound.contractId,
      )
    }

  private def notFound(description: String) = new StatusRuntimeException(
    Status.NOT_FOUND.withDescription(description)
  )
}
