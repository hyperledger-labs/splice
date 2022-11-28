package com.daml.network.wallet.automation

import akka.stream.Materializer
import cats.syntax.either.*
import cats.syntax.traverse.*
import com.daml.network.automation.{AcsIngestionService, AutomationService}
import com.daml.network.codegen.java.cn.wallet.{
  install as installCodegen,
  subscriptions as subsCodegen,
}
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.wallet.store.{EndUserWalletStore, WalletStore}
import com.daml.network.wallet.treasury.{CoinOperationRequest, TreasuryServices}
import com.digitalasset.canton.config.{ClockConfig, ProcessingTimeout}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, Lifecycle, SyncCloseable}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import java.time.temporal.ChronoUnit
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.DurationConverters.*

/** Manages background automation that runs on an Wallet app.
  */
class WalletAutomationService(
    automationConfig: AutomationConfig,
    clockConfig: ClockConfig,
    walletStore: WalletStore,
    treasuryServices: TreasuryServices,
    ledgerClient: CoinLedgerClient,
    retryProvider: CoinRetries,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends AutomationService(automationConfig, clockConfig, retryProvider) {

  // TODO(#1692) both of these should be configuration options that get overridden in tests
  private val canMakeSubscriptionPaymentCheckInterval = 1.second
  // How long to delay a subscription payment after it has become payable;
  // keeping this above 15s helps avoid payment failures due to clock skew
  private val subscriptionPaymentMinDelay = 1.second

  private val connection = registerResource(ledgerClient.connection(this.getClass.getSimpleName))

  registerService(
    new AcsIngestionService(
      "WalletStore",
      walletStore.acsIngestionSink,
      connection,
      retryProvider,
      loggerFactory,
      timeouts,
    )
  )

  private val ingestionServices: TrieMap[String, AutoCloseable] = TrieMap.empty

  // TODO(#763): not handling archive events, uninstalling wallets without a restart is not supported yet
  registerRequestHandler("WalletAppInstall", walletStore.streamInstalls)(install => {
    implicit traceContext =>
      Future {
        val endUserName = install.payload.endUserName
        val endUserParty = PartyId.tryFromProtoPrimitive(install.payload.endUserParty)
        val endUserStore = walletStore.getOrCreateEndUserStore(endUserName, endUserParty, timeouts)
        val ingestionService = new AcsIngestionService(
          s"EndUserWalletStore($endUserName)",
          endUserStore.acsIngestionSink,
          connection,
          retryProvider,
          loggerFactory,
          timeouts,
        )
        treasuryServices.addOrCreateTreasuryService(install, endUserStore): Unit
        Some(registerService(ingestionServices, endUserName, ingestionService))
      }
  })

  registerTimeHandler(
    "handleCanMakeSubscriptionPayment",
    canMakeSubscriptionPaymentCheckInterval,
    connection,
  )(now => { implicit traceContext =>
    {
      // process each store separately
      walletStore.endUserStores
        .map(makeDueSubscriptionPaymentsForStore(_, now))
        .toList
        .sequence
        // join results
        .map(_.flatten.partitionMap(r => r) match {
          case (lefts, rights) => {
            Some(s"created ${rights.size} subscription payments (${lefts.size} failures).")
          }
        })
    }
  })

  private def registerService(
      services: TrieMap[String, AutoCloseable],
      endUserName: String,
      service: AutoCloseable,
  )(implicit tc: TraceContext): String = {
    services.putIfAbsent(endUserName, service) match {
      case None =>
        s"onboarded wallet end-user '$endUserName'"
      case Some(_) =>
        logger.warn(s"Unexpected duplicate on-boarding of wallet user '$endUserName'")
        service.close()
        s"skipped duplicate on-boarding wallet end-user '$endUserName'"
    }
  }

  private def makeDueSubscriptionPaymentsForStore(
      userStore: EndUserWalletStore,
      now: CantonTimestamp,
  )(implicit tc: TraceContext): Future[Seq[Either[String, Unit]]] = {
    userStore
      .listContracts(subsCodegen.SubscriptionIdleState.COMPANION)
      .flatMap { case QueryResult(_, subStates) =>
        subStates
          // extract subscriptions we can make payments on now
          .filter(state =>
            now.toInstant.isAfter(
              state.payload.nextPaymentDueAt
                .minus(
                  state.payload.payData.paymentDuration.microseconds,
                  ChronoUnit.MICROS,
                )
                // we don't pay immediately to account for potential clock skew
                .plus(subscriptionPaymentMinDelay.toJava)
            )
          )
          // initiate payment via treasury
          .map(readyState => makeSubscriptionPayment(readyState.contractId, userStore))
          .sequence
      }
  }

  // TODO(#1247) consider reducing duplication with exerciseWalletCoinAction from GrpcWalletService
  private def makeSubscriptionPayment(
      stateCid: subsCodegen.SubscriptionIdleState.ContractId,
      userStore: EndUserWalletStore,
  )(implicit tc: TraceContext): Future[Either[String, Unit]] = {
    def lookups = () =>
      for {
        subscriptionStateO <- userStore.lookupSubscriptionIdleStateById(stateCid)
        subscriptionState = getQueryResult(
          subscriptionStateO,
          s"subscription idle state cid $stateCid",
        )
        _ <- userStore.lookupSubscriptionContextById(
          subscriptionState.payload.subscriptionData.context
        )
      } yield ()

    val operation = CoinOperationRequest(
      (_: Unit) => new installCodegen.coinoperation.CO_SubscriptionMakePayment(stateCid),
      lookups,
    )
    (treasuryServices
      .lookupEndUserTreasury(userStore.key.endUserName) match {
      case None => {
        Future(Left(s"missing end-user treasury"))
      }
      case Some(userTreasury) =>
        userTreasury
          .enqueueCoinOperation(operation)
          .map(_ match {
            case failedOperation: installCodegen.coinoperationoutcome.COO_Error => {
              Left(
                s"the coin operation failed with a Daml exception: ${failedOperation.stringValue}."
              )
            }
            case _ => {
              logger.info(s"Made a subscription payment on state $stateCid")
              Right(())
            }
          })
    }).map(_.leftMap(error => {
      logger.warn(s"Failed making a subscription payment on state $stateCid: $error")
      s"state $stateCid: $error"
    }))
  }

  // TODO(#1247) consider reducing duplication with GrpcWallet service
  private def getUserInstallContract(
      userWalletStore: EndUserWalletStore,
      userParty: PartyId,
  ): Future[
    com.daml.network.util.JavaContract[
      installCodegen.WalletAppInstall.ContractId,
      installCodegen.WalletAppInstall,
    ]
  ] =
    userWalletStore
      .lookupInstall()
      .map(getQueryResult(_, s"WalletAppInstall contract of user $userParty"))

  // TODO(#1247) consider reducing duplication with GrpcWallet service / moving into the `QueryResult` class itself
  private def getQueryResult[T](
      result: QueryResult[Option[T]],
      errorMsg: String,
  ): T = result match {
    case QueryResult(_, None) =>
      throw new StatusRuntimeException(Status.NOT_FOUND.withDescription(errorMsg))
    case QueryResult(_, Some(x)) => x
  }

  override def closeAsync(): Seq[AsyncOrSyncCloseable] =
    Seq(
      SyncCloseable(
        "EndUserIngestionServices",
        Lifecycle.close(ingestionServices.values.toSeq: _*)(logger),
      )
    ) ++ super.closeAsync()
}
