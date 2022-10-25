package com.daml.network.splitwise.automation

import akka.stream.Materializer
import com.daml.network.automation.{AcsIngestionService, AutomationService}
import com.daml.network.codegen.CN.{Splitwise => splitwiseCodegen}
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.splitwise.store.SplitwiseStore
import com.daml.network.store.AcsStore.QueryResult
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import java.security.MessageDigest
import scala.concurrent.ExecutionContextExecutor

/** Manages background automation that runs on an splitwise app. */
class SplitwiseAutomationService(
    store: SplitwiseStore,
    ledgerClient: CoinLedgerClient,
    retryProvider: CoinRetries,
    protected val loggerFactory: NamedLoggerFactory,
    processingTimeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends AutomationService(retryProvider) {

  override protected def timeouts: ProcessingTimeout = processingTimeouts

  private val connection = ledgerClient.connection("SplitwiseAutomationService")
  private val provider = store.providerParty

  registerService(
    new AcsIngestionService(
      this.getClass.getSimpleName,
      store.acsIngestionSink,
      connection,
      loggerFactory,
      timeouts,
    )
  )

  private val hashFun = MessageDigest.getInstance("SHA-256")

  // TODO(#790): generalize this so that it can be shared with other command dedup calls
  private def mkCommandId(
      methodName: String,
      parties: Seq[PartyId],
      suffix: String = "",
  ): String = {
    require(!methodName.contains('/'))
    val str = parties.map(_.toProtoPrimitive).appended(suffix).mkString("/")
    val hash = hashFun.digest(str.getBytes("UTF-8")).map("%02x".format(_)).mkString
    s"${methodName}_$hash"
  }

  registerRequestHandler("handleSplitwiseInstallRequest", store.streamInstallRequests())(
    req => req.toString,
    (req, traceContext) => {
      // TODO(#790): figure out how to avoid this redeclaration
      implicit val tc: TraceContext = traceContext
      val user = PartyId.tryFromPrim(req.payload.user)
      store.lookupInstall(user).flatMap {
        case QueryResult(_, Some(_)) =>
          logger.info(s"Rejecting duplicate install request from user party $user")
          val cmd = req.contractId.exerciseSplitwiseInstallRequest_Reject()
          connection
            .submitWithResult(Seq(provider), Seq(), cmd)
            .map(_ => "rejected request for already existing installation.")

        case QueryResult(off, None) =>
          val arg = splitwiseCodegen.SplitwiseInstallRequest_Accept()
          val commandId =
            mkCommandId("com.daml.network.splitwise.SplitwiseInstall", Seq(provider, user))

          val acceptCmd = req.contractId.exerciseSplitwiseInstallRequest_Accept(arg).command
          // TODO(#790): should we really discard the result here? if yes, can we avoid parsing it?
          connection
            .submitCommandWithDedup(
              actAs = Seq(provider),
              readAs = Seq(),
              command = Seq(acceptCmd),
              commandId = commandId,
              deduplicationOffset = off,
            )
            .map(_ => "accepted install request.")
      }
    },
  )
}
