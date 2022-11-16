package com.daml.network.splitwise.automation

import akka.stream.Materializer
import com.daml.network.automation.{AcsIngestionService, AutomationService}
import com.daml.network.codegen.java.cn.splitwise as splitwiseCodegen
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.splitwise.store.SplitwiseStore
import com.daml.network.store.AcsStore.QueryResult
import com.digitalasset.canton.config.{ClockConfig, ProcessingTimeout}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import io.opentelemetry.api.trace.Tracer

import java.security.MessageDigest
import scala.concurrent.ExecutionContextExecutor
import scala.jdk.CollectionConverters.*

/** Manages background automation that runs on an splitwise app. */
class SplitwiseAutomationService(
    store: SplitwiseStore,
    ledgerClient: CoinLedgerClient,
    readAs: Set[PartyId],
    scanConnection: ScanConnection,
    clockConfig: ClockConfig,
    retryProvider: CoinRetries,
    protected val loggerFactory: NamedLoggerFactory,
    processingTimeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends AutomationService(clockConfig, retryProvider) {

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

  // TODO(#790): generalize this so that it can be shared with other command dedup calls
  private def mkCommandId(
      methodName: String,
      parties: Seq[PartyId],
      suffix: String = "",
  ): String = {
    require(!methodName.contains('/'))
    val str = parties.map(_.toProtoPrimitive).appended(suffix).mkString("/")
    // Digest is not thread safe, create a new one each time.
    val hashFun = MessageDigest.getInstance("SHA-256")
    val hash = hashFun.digest(str.getBytes("UTF-8")).map("%02x".format(_)).mkString
    s"${methodName}_$hash"
  }

  registerRequestHandler("handleSplitwiseInstallRequest", store.streamInstallRequests())(req => {
    implicit tc =>
      {
        val user = PartyId.tryFromProtoPrimitive(req.payload.user)
        store.lookupInstall(user).flatMap {
          case QueryResult(_, Some(_)) =>
            logger.info(s"Rejecting duplicate install request from user party $user")
            val cmd = req.contractId.exerciseSplitwiseInstallRequest_Reject()
            connection
              .submitWithResult(Seq(provider), Seq(), cmd)
              .map(_ => "rejected request for already existing installation.")

          case QueryResult(off, None) =>
            val commandId =
              mkCommandId("com.daml.network.splitwise.SplitwiseInstall", Seq(provider, user))

            val acceptCmd =
              req.contractId.exerciseSplitwiseInstallRequest_Accept().commands.asScala.toSeq
            // TODO(#790): should we really discard the result here? if yes, can we avoid parsing it?
            connection
              .submitCommandsWithDedup(
                actAs = Seq(provider),
                readAs = Seq(),
                commands = acceptCmd,
                commandId = commandId,
                deduplicationOffset = off,
              )
              .map(_ => "accepted install request.")
        }
      }
  })

  registerRequestHandler(
    "handleAcceptedAppPaymentRequests",
    store.streamAcceptedAppPayments(),
  )(payment => { implicit traceContext =>
    val sender = PartyId.tryFromProtoPrimitive(payment.payload.sender)
    val transferInProgressId = splitwiseCodegen.TransferInProgress.ContractId.unsafeFromInterface(
      payment.payload.deliveryOffer
    )
    store.lookupInstall(sender).flatMap {
      case QueryResult(_, None) =>
        val msg = s"Install contract not found for sender party $sender"
        logger.warn(msg)
        val cmd = payment.contractId.exerciseAcceptedAppPayment_Reject()
        connection
          .submitCommands(
            actAs = Seq(provider),
            readAs = Seq.empty,
            commands = cmd.commands.asScala.toSeq,
          )
          .map(_ => s"rejected accepted app payment: $msg")
      case QueryResult(_, Some(install)) =>
        for {
          transferContext <- scanConnection.getJavaAppTransferContext()
          transferInProgress <- store
            .lookupTransferInProgressById(transferInProgressId)
            .map(
              _.value.getOrElse(
                throw new IllegalStateException(
                  s"Invariant violation: transfer in progress $transferInProgressId not known"
                )
              )
            )
          group <- store.getGroup(
            PartyId.tryFromProtoPrimitive(transferInProgress.payload.group.owner),
            transferInProgress.payload.group.id,
          )
          cmd = install.contractId.exerciseSplitwiseInstall_CompleteTransfer(
            group.value.contractId,
            payment.contractId,
            transferContext,
          )
          _ <- connection.submitCommands(
            actAs = Seq(provider),
            readAs = readAs.toSeq,
            commands = cmd.commands.asScala.toSeq,
          )
        } yield "Completed transfer"
    }
  })

  registerRequestHandler("handleGroupRequest", store.streamGroupRequests())(req => { implicit tc =>
    {
      val user = PartyId.tryFromProtoPrimitive(req.payload.group.owner)
      val groupId = req.payload.group.id
      store.lookupGroup(user, groupId).flatMap {
        case QueryResult(_, Some(_)) =>
          logger.info(
            s"Rejecting duplicate group request from user party $user for group id ${groupId.unpack}"
          )
          val cmd = req.contractId.exerciseGroupRequest_Reject()
          connection
            .submitWithResult(Seq(provider), Seq(), cmd)
            .map(_ => "rejected request for already existing group.")

        case QueryResult(off, None) =>
          val commandId =
            mkCommandId(
              "com.daml.network.splitwise.GroupRequest",
              Seq(provider, user),
              groupId.unpack,
            )

          val acceptCmd = req.contractId.exerciseGroupRequest_Accept().commands.asScala.toSeq
          connection
            .submitCommandsWithDedup(
              actAs = Seq(provider),
              readAs = Seq(),
              commands = acceptCmd,
              commandId = commandId,
              deduplicationOffset = off,
            )
            .map(_ => "accepted group request.")
      }
    }
  })
}
