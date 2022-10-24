package com.daml.network.wallet.admin.grpc

import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{BoundedSourceQueue, Materializer}
import cats.syntax.traverse.*
import com.daml.network.codegen.CC.{Coin as coinCodegen, CoinRules as coinRulesCodegen}
import com.daml.network.codegen.CN.Wallet as walletCodegen
import com.daml.network.environment.{CoinLedgerConnection, CoinRetries}
import com.daml.network.util.Contract
import com.daml.network.wallet.store.{EndUserWalletStore, WalletStore}
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.FlagCloseable
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.retry
import com.digitalasset.canton.util.retry.Backoff
import EndUserTreasuryService.*

import java.util.UUID
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}
// TODO(#756): Add PrettyPrinting

/** Wrapper helper class.
  * We want to avoid a batch being aborted for anything other than contention errors. Some errors can be caught
  * within Daml to avoid aborting the transaction. However, contract activeness cannot be caught in Daml. Therefore,
  * we check that certain contracts required for the operation are active. That way, a batch can only fail
  * due to contention and we can retry it (including retrying the lookups).
  *
  * Note that we don't check that contracts managed by the SVC (CoinRules, IssuanceState, OpenMiningRound etc.) are
  * active. These contracts should always be active (even under contention) and a non-SVC CN user isn't able
  * to make changes to his system that would lead to these contracts being active on the next submission.
  *
  * @param tryLookups runs activeness checks on all contracts that are needed for the corresponding
  * coin operation. If a require contract is not found, the future should fail with an appropriate
  * [[io.grpc.StatusRuntimeException]]. We execute `tryLookups` run on every submission of the corresponding
  * coin operation (also for retries).
  */
case class CoinOperationWithLookups(
    operation: walletCodegen.CoinOperation,
    tryLookups: () => Future[Unit],
)

/** This class encapsulates the logic that takes operations changing the coin holdings of an user and sequences them
  * such that concurrent manipulations don't conflict.
  *
  * For the design, please see https://github.com/DACH-NY/the-real-canton-coin/issues/913.
  */
case class EndUserTreasuryService(
    connection: CoinLedgerConnection,
    install: Contract[walletCodegen.WalletAppInstall],
    walletStoreKey: WalletStore.Key,
    userStore: EndUserWalletStore,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val timeouts: ProcessingTimeout,
)(implicit ec: ExecutionContext, mat: Materializer)
    extends NamedLogging
    with FlagCloseable {

  // Magic numbers
  private val batchSize = 10L
  private val bufferSize = 1000
  private val waitTimeForStore = 300.millis

  private val queue: BoundedSourceQueue[CoinOperationAndPromise] = Source
    .queue[CoinOperationAndPromise](bufferSize)
    // TODO(#756): Possible extension: re-order commands according to urgency
    .batch(batchSize, operation => Seq(operation))((operations, operation) =>
      operations :+ operation
    )
    .mapAsync(1)(executeBatchWithRetry)
    .toMat(Sink.foreach(res => {
      // TODO(#756): Add synchronization that uses the store's state instead of an arbitrary wait
      logger.debug(s"Waiting for $waitTimeForStore ms to get the store time to catch-up")(
        TraceContext.empty
      )
      Threading.sleep(waitTimeForStore.toMillis)
    }))(Keep.left)
    .run()

  /** Enqueues a coin operation into an internal task queue.
    * The [[EndUserTreasuryService]] will schedule the operation and then complete the returned with its result.
    */
  def enqueueCoinOperation(
      operation: CoinOperationWithLookups
  )(implicit tc: TraceContext): Future[walletCodegen.CoinOperationOutcome] = {
    // TODO(#756): possibly allow callers to assign semantically meaningful ids (see the discussion
    //  at https://github.com/DACH-NY/the-real-canton-coin/pull/1145#discussion_r994508807)
    val id = UUID.randomUUID().toString
    // TODO(#756): error handling
    val p = Promise[walletCodegen.CoinOperationOutcome]()
    queue.offer(CoinOperationAndPromise(CoinOperationWithIdAndLookups(id, operation), p)): Unit
    logger.trace(s"received ${operation}, queue now: ${queue.size()}")
    p.future
  }

  /** Runs the lookups, only returns the operation whose lookup succeeded and completes the promises for
    * the coin operations with failed lookups.
    */
  private def runLookups(
      batch: Seq[CoinOperationAndPromise]
  ): Future[Seq[CoinOperationAndPromise]] = {
    batch
      .traverse { case CoinOperationAndPromise(coinOperation, p) =>
        coinOperation.operationWithLookups
          .tryLookups()
          .transform { lookupResult =>
            lookupResult match {
              case Failure(ex) =>
                // if the lookup fails, complete the promise with the failed future
                p.failure(ex)
                // but still run the rest of the operations whose lookup didn't fail
                Success(None)
              case Success(operation) => Success(Some(CoinOperationAndPromise(coinOperation, p)))
            }
          }
      }
      .map(_.flatten)
  }

  /** In case of contention, the `executeBatch` function may fail. This function adds retries so that a single coin
    * operation, that failed due to contention, does not require a whole batch of coin operations to be resubmitted
    * to the wallet app.
    */
  private def executeBatchWithRetry(
      batch: Seq[CoinOperationAndPromise]
  ): Future[Unit] =
    TraceContext.withNewTraceContext { implicit tc =>
      val maxRetries: Int = 3
      val initialDelay: FiniteDuration = 1.seconds
      val maxDelay: Duration = 5.seconds

      implicit val success: retry.Success[Unit] = retry.Success.always
      val res = Backoff(
        logger,
        this,
        maxRetries,
        initialDelay,
        maxDelay,
        "coin operation batch",
      ).apply(executeBatch(batch), CoinRetries.RetryableError("coin operation batch"))
      res
    }

  private def executeBatch(
      batch: Seq[CoinOperationAndPromise]
  )(implicit tc: TraceContext): Future[Unit] = {

    logger.debug(
      s"Running batch of coin operations for user ${userStore.key.endUserParty} with length ${batch.size}: $batch"
    )

    for {
      validOperations <- runLookups(batch)
      _ = logger.debug(
        s"After lookups, the batch contains ${validOperations.size} coin operations."
      )
      // TODO(#756): smarter coin/input selection?
      inputs <- userStore
        .listContracts(coinCodegen.Coin)
        .map(cs => cs.value.map(c => coinRulesCodegen.TransferInput.InputCoin(c.contractId)))
      cmd =
        install.contractId.exerciseWalletAppInstall_ExecuteBatch(
          inputs,
          validOperations.map(_.operationWithIdAndLookups.operationWithLookups.operation),
        )
      outcomes <- connection
        .submitWithResult(
          Seq(walletStoreKey.walletServiceParty),
          Seq(walletStoreKey.validatorParty, userStore.key.endUserParty),
          cmd,
        )
      _ = (outcomes zip validOperations).map {
        case (outcome, CoinOperationAndPromise(operation, p)) =>
          p.success(outcome)
      }
    } yield ()
  }

  override def onClosed(): Unit = {
    // TODO(M1-92 - Tech Debt): add more robust shutdown, e.g., similar to shutdown for ledger subscriptions
    queue.complete()
  }
}

object EndUserTreasuryService {

  /** Wrapper helper classes. */
  private case class CoinOperationWithIdAndLookups(
      id: String,
      operationWithLookups: CoinOperationWithLookups,
  )

  private case class CoinOperationAndPromise(
      operationWithIdAndLookups: CoinOperationWithIdAndLookups,
      promise: Promise[walletCodegen.CoinOperationOutcome],
  )

}
