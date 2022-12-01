package com.daml.network.wallet.automation

import akka.stream.Materializer
import cats.syntax.traverse.*
import com.daml.network.automation.{AcsIngestionService, AutomationService}
import com.daml.network.codegen.java.cn.wallet.install.coinoperation.CO_CompleteAcceptedTransfer
import com.daml.network.codegen.java.cn.wallet.transferoffer.AcceptedTransferOffer
import com.daml.network.codegen.java.cn.wallet.{
  install as installCodegen,
  transferoffer as transferOffersCodegen,
}
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.util.JavaContract
import com.daml.network.wallet.store.EndUserWalletStore
import com.daml.network.wallet.treasury.{CoinOperationRequest, EndUserTreasuryService}
import com.digitalasset.canton.config.{ClockConfig, ProcessingTimeout}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

class EndUserWalletAutomationService(
    store: EndUserWalletStore,
    treasury: EndUserTreasuryService,
    ledgerClient: CoinLedgerClient,
    automationConfig: AutomationConfig,
    clockConfig: ClockConfig,
    retryProvider: CoinRetries,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends AutomationService(automationConfig, clockConfig, retryProvider) {

  // TODO(M3-02) this should be a configuration option that gets overridden in tests
  private val pendingTransferOffersRetryInterval = 1.second

  private val connection = registerResource(ledgerClient.connection(this.getClass.getSimpleName))

  registerService(
    new AcsIngestionService(
      s"EndUserWalletStore(${store.key.endUserName})",
      store.acsIngestionSink,
      connection,
      retryProvider,
      loggerFactory,
      timeouts,
    )
  )

  private def completeAcceptedTransferOffer(
      acceptedOffer: JavaContract[AcceptedTransferOffer.ContractId, AcceptedTransferOffer]
  )(implicit tc: TraceContext): Future[Either[String, String]] = {
    def lookups = () => {
      for {
        _ <- store.lookupAcceptedTransferOfferById(acceptedOffer.contractId)
      } yield ()
    }
    val operation = CoinOperationRequest(
      (_: Unit) => new CO_CompleteAcceptedTransfer(acceptedOffer.contractId),
      lookups,
    )
    treasury
      .enqueueCoinOperation(operation)
      .map {
        case failedOperation: installCodegen.coinoperationoutcome.COO_Error =>
          val msg =
            show"Failed making a transfer with accepted offer ${acceptedOffer}: ${failedOperation.stringValue.singleQuoted}"
          logger.info(
            msg
          ) // We report this only at an info level, because it will be retried automatically
          Left(msg)
        case _ =>
          val msg = s"Completed a transfer with accepted offer ${acceptedOffer}"
          logger.info(msg)
          Right(msg)
      }
  }

  private def completeAcceptedTransferOfferIfSender(
      acceptedOffer: JavaContract[AcceptedTransferOffer.ContractId, AcceptedTransferOffer]
  )(implicit tc: TraceContext): Future[Either[String, String]] = {
    store.key.endUserParty.toProtoPrimitive match {
      case acceptedOffer.payload.sender =>
        logger.info("Transfer offer accepted, trying to complete the transfer...")
        completeAcceptedTransferOffer(acceptedOffer)
      case acceptedOffer.payload.receiver =>
        val msg = "AcceptedTransferOffer ignored as the receiver"
        logger.debug(msg)
        Future(Right(msg))
      case _ =>
        val msg =
          s"end user party (${store.key.endUserParty}) is unexpectedly neither the sender (${acceptedOffer.payload.sender}) nor the receiver (${acceptedOffer.payload.receiver})"
        logger.error(
          msg
        )
        Future(Left(msg))
    }
  }

  // TODO(M3-02): consider, in addition to the periodic attempts, also triggering the transfer
  //  from ingestion of a new accepted offer. On the happy path, this will complete the transfer quicker,
  //  but might be a source for contention and other races between that attempt and the periodic retry

  registerTimeHandler(
    "RetryPendingTransferOffers",
    automationConfig.pollingInterval,
    connection,
  )(_ => { implicit traceContext =>
    {
      logger.info("Looking for accepted transfer offers to complete...")
      store.listContracts(transferOffersCodegen.AcceptedTransferOffer.COMPANION).flatMap {
        case QueryResult(_, acceptedOffers) =>
          logger.info(s"Attempting to complete ${acceptedOffers.length} accepted transfer offers")
          acceptedOffers
            .map(acceptedOffer => completeAcceptedTransferOfferIfSender(acceptedOffer))
            .toList
            .sequence
            .map(_.partitionMap(r => r) match {
              case (lefts, rights) => {
                Some(
                  s"Successfully completed ${rights.size} accepted transfer offers; ${lefts.size} failed."
                )
              }
            })
      }
    }
  })

}
