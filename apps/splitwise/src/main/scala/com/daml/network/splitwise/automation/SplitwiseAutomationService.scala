package com.daml.network.splitwise.automation

import akka.stream.Materializer
import com.daml.network.automation.{AcsIngestionService, AutomationService}
import com.daml.network.codegen.CN.Splitwise as splitwiseCodegen
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.splitwise.store.SplitwiseStore
import com.daml.network.store.AcsStore.QueryResult
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import io.opentelemetry.api.trace.Tracer

import java.security.MessageDigest
import scala.concurrent.ExecutionContextExecutor

/** Manages background automation that runs on an splitwise app. */
class SplitwiseAutomationService(
    store: SplitwiseStore,
    ledgerClient: CoinLedgerClient,
    readAs: Set[PartyId],
    scanConnection: ScanConnection,
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

  registerRequestHandler("handleSplitwiseInstallRequest", store.streamInstallRequests())(req => {
    implicit tc =>
      {
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
      }
  })

  registerRequestHandler("handleAcceptedAppPaymentRequests", store.streamAcceptedAppPayments())(
    payment => { implicit traceContext =>
      val sender = PartyId.tryFromPrim(payment.payload.sender)
      val transferInProgressId = payment.payload.deliveryOffer
        .unsafeToTemplate[splitwiseCodegen.TransferInProgress]
      store.lookupInstall(sender).flatMap {
        case QueryResult(_, None) =>
          val msg = s"Install contract not found for sender party $sender"
          logger.warn(msg)
          val cmd = payment.contractId.exerciseAcceptedAppPayment_Reject()
          connection
            .submitCommand(
              actAs = Seq(provider),
              readAs = Seq.empty,
              command = Seq(cmd.command),
            )
            .map(_ => s"rejected accepted app payment: $msg")
        case QueryResult(_, Some(install)) =>
          for {
            transferContext <- scanConnection.getAppTransferContext()
            transferInProgress <- store
              .lookupTransferInProgressById(transferInProgressId)
              .map(
                _.value.getOrElse(
                  throw new IllegalStateException(
                    s"Invariant violation: transfer in progress $transferInProgressId not known"
                  )
                )
              )
            cmd = install.contractId.exerciseSplitwiseInstall_CompleteTransfer(
              groupKey = splitwiseCodegen.GroupKey(
                owner = transferInProgress.payload.group.owner,
                provider = provider.toPrim,
                id = transferInProgress.payload.group.id,
              ),
              acceptedPaymentCid = payment.contractId,
              transferContext = transferContext,
            )
            _ <- connection.submitCommand(
              actAs = Seq(provider),
              readAs = readAs.toSeq,
              command = Seq(cmd.command),
            )
          } yield "Completed transfer"
      }
    }
  )

  registerRequestHandler(
    "handleAcceptedAppMultiPaymentRequests",
    store.streamAcceptedAppMultiPayments(),
  )(payment => { implicit traceContext =>
    val sender = PartyId.tryFromPrim(payment.payload.sender)
    val transferInProgressId = payment.payload.deliveryOffer
      .unsafeToTemplate[splitwiseCodegen.MultiTransferInProgress]
    store.lookupInstall(sender).flatMap {
      case QueryResult(_, None) =>
        val msg = s"Install contract not found for sender party $sender"
        logger.warn(msg)
        val cmd = payment.contractId.exerciseAcceptedAppMultiPayment_Reject()
        connection
          .submitCommand(
            actAs = Seq(provider),
            readAs = Seq.empty,
            command = Seq(cmd.command),
          )
          .map(_ => s"rejected accepted app payment: $msg")
      case QueryResult(_, Some(install)) =>
        for {
          transferContext <- scanConnection.getAppTransferContext()
          transferInProgress <- store
            .lookupMultiTransferInProgressById(transferInProgressId)
            .map(
              _.value.getOrElse(
                throw new IllegalStateException(
                  s"Invariant violation: multi transfer in progress $transferInProgressId not known"
                )
              )
            )
          cmd = install.contractId.exerciseSplitwiseInstall_CompleteMultiTransfer(
            groupKey = splitwiseCodegen.GroupKey(
              owner = transferInProgress.payload.group.owner,
              provider = provider.toPrim,
              id = transferInProgress.payload.group.id,
            ),
            acceptedPaymentCid = payment.contractId,
            transferContext = transferContext,
          )
          _ <- connection.submitCommand(
            actAs = Seq(provider),
            readAs = readAs.toSeq,
            command = Seq(cmd.command),
          )
        } yield "Completed transfer"
    }
  })
}
