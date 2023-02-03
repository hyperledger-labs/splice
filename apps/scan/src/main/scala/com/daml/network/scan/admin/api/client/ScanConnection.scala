package com.daml.network.scan.admin.api.client

import com.daml.network.admin.api.client.AppConnection
import com.daml.network.codegen.java.cc.coin.{CoinRules, FeaturedAppRight}
import com.daml.network.codegen.java.cc.api.v1.{coin as coinCodegen, round as roundCodegen}
import com.daml.network.codegen.java.cc.round.{IssuingMiningRound, OpenMiningRound}
import com.daml.network.scan.admin.api.client.commands.GrpcScanAppClient
import com.daml.network.util.Contract
import com.digitalasset.canton.config.{ClientConfig, ProcessingTimeout}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.{Status, StatusRuntimeException}

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.OptionConverters.*

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

  def getCoinRules()(implicit
      traceContext: TraceContext
  ): Future[Contract[CoinRules.ContractId, CoinRules]] = {
    runCmd(GrpcScanAppClient.GetCoinRules())
  }

  def getLatestOpenAndIssuingMiningRounds()(implicit
      traceContext: TraceContext
  ): Future[
    (
        Contract[OpenMiningRound.ContractId, OpenMiningRound],
        Seq[Contract[IssuingMiningRound.ContractId, IssuingMiningRound]],
    )
  ] = {
    runCmd(GrpcScanAppClient.GetLatestOpenAndIssuingMiningRounds())
  }

  def lookupFeaturedAppRight(providerPartyId: PartyId)(implicit
      traceContext: TraceContext
  ): Future[Option[Contract[FeaturedAppRight.ContractId, FeaturedAppRight]]] = {
    runCmd(GrpcScanAppClient.LookupFeaturedAppRight(providerPartyId))
  }

  def getAppTransferContext(providerPartyId: PartyId)(implicit
      traceContext: TraceContext
  ): Future[coinCodegen.AppTransferContext] = {
    for {
      context <- getTransferContext()
      featured <- lookupFeaturedAppRight(providerPartyId)
    } yield {
      val coinRules = context.coinRules.getOrElse(throw notFound("No active CoinRules contract"))
      val openMiningRound = context.latestOpenMiningRound.getOrElse(
        throw notFound("No active OpenMiningRound contract")
      )
      new coinCodegen.AppTransferContext(
        coinRules.contractId.toInterface(coinCodegen.CoinRules.INTERFACE),
        openMiningRound.contractId.toInterface(roundCodegen.OpenMiningRound.INTERFACE),
        featured.map(_.contractId.toInterface(coinCodegen.FeaturedAppRight.INTERFACE)).toJava,
      )
    }
  }

  private def notFound(description: String) = new StatusRuntimeException(
    Status.NOT_FOUND.withDescription(description)
  )
}
