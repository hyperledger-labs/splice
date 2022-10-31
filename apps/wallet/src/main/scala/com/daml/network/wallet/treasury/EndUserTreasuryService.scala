package com.daml.network.wallet.treasury

import akka.stream.QueueOfferResult.{Dropped, Enqueued, QueueClosed}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{BoundedSourceQueue, Materializer, QueueOfferResult}
import cats.syntax.traverse.*
import com.daml.network.codegen.CC.{
  Coin as coinCodegen,
  CoinRules as coinRulesCodegen,
  Round as roundCodegen,
}
import com.daml.network.codegen.CN.Wallet as walletCodegen
import com.daml.network.environment.{CoinLedgerConnection, CoinRetries}
import com.daml.network.util.Contract
import com.daml.network.wallet.store.{EndUserWalletStore, WalletStore}
import com.daml.network.wallet.treasury.EndUserTreasuryService.*
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.FlagCloseable
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.{Status, StatusRuntimeException}

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}
// TODO(#1351): Add PrettyPrinting

/** Wrapper class for the treasury service.
  *
  * Inside the treasury service, we want to avoid a batch being aborted for anything other
  * than contention errors. Some errors can be caught within Daml to avoid aborting the transaction. However, contract
  * activeness cannot be caught in Daml. Therefore, we check that certain contracts required for the operation
  * are active via lookups. That way, we know that if a batch failed it can only be due to contention and
  * we can safely resubmit it.
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
case class CoinOperationRequest(
    operation: walletCodegen.CoinOperation,
    tryLookups: () => Future[Unit],
)

/** This class encapsulates the logic that sequences all operations which change the coin holdings of an user such
  * that concurrent manipulations don't conflict.
  *
  * For the design, please see https://github.com/DACH-NY/the-real-canton-coin/issues/913 and the documentation on
  * [[CoinOperationRequest]].
  */
case class EndUserTreasuryService(
    connection: CoinLedgerConnection,
    // TODO(#1351): don't pass WalletAppInstall contract along but look it up before each batch submission to support users updating their
    // WalletAppInstall contract
    install: Contract[walletCodegen.WalletAppInstall],
    walletStoreKey: WalletStore.Key,
    userStore: EndUserWalletStore,
    walletStore: WalletStore,
    retryProvider: CoinRetries,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val timeouts: ProcessingTimeout,
)(implicit ec: ExecutionContext, mat: Materializer)
    extends NamedLogging
    with FlagCloseable {

  // Magic numbers
  private val batchSize = 10L
  private val bufferSize = 1000
  private val waitTimeForStore = 300.millis

  private val queue: BoundedSourceQueue[EnqueuedCoinOperation] = Source
    .queue[EnqueuedCoinOperation](bufferSize)
    // TODO(#1351): Possible extension: re-order commands according to urgency
    .batch(batchSize, operation => Seq(operation))((operations, operation) =>
      operations :+ operation
    )
    .toMat(
      Sink.foreachAsync(1)(batch =>
        executeBatchWithRetry(batch).flatMap(offset => {
          userStore
            .signalWhenIngested(offset)
            .map(_ =>
              logger
                .debug(s"Finished waiting for store to ingest offset $offset")(TraceContext.empty)
            )
        })
      )
    )(Keep.left)
    .run()

  /** Enqueues a coin operation into an internal task queue.
    * The [[EndUserTreasuryService]] will schedule the operation and then complete the returned with its result.
    */
  def enqueueCoinOperation(
      operation: CoinOperationRequest
  )(implicit tc: TraceContext): Future[walletCodegen.CoinOperationOutcome] = {
    // TODO(#1351): possibly allow callers to assign semantically meaningful ids (see the discussion
    //  at https://github.com/DACH-NY/the-real-canton-coin/pull/1145#discussion_r994508807)
    val p = Promise[walletCodegen.CoinOperationOutcome]()
    queue.offer(EnqueuedCoinOperation(operation, p)) match {
      case Enqueued =>
        logger.trace(s"received ${operation}, queue now: ${queue.size()}")
        p.future
      // TODO(M3-90): add tests for the failure cases.
      case Dropped =>
        Future.failed(
          new StatusRuntimeException(
            Status.ABORTED.withDescription(
              s"operation $operation was aborted, probably as there are too many (${queue.size()}) already in flight"
            )
          )
        )
      case QueueOfferResult.Failure(cause) => Future.failed(cause)
      case QueueClosed =>
        Future.failed(
          new StatusRuntimeException(
            Status.RESOURCE_EXHAUSTED.withDescription(
              s"operation $operation was rejected because the queue is already closed. This indicates a shutdown."
            )
          )
        )
    }
  }

  /** Runs the lookups, only returns the operation whose lookup succeeded and completes the promises for
    * the coin operations with failed lookups.
    */
  private def runLookups(
      batch: Seq[EnqueuedCoinOperation]
  ): Future[Seq[EnqueuedCoinOperation]] = {
    batch
      .traverse { case EnqueuedCoinOperation(coinOperation, p) =>
        coinOperation
          .tryLookups()
          .transform { lookupResult =>
            lookupResult match {
              case Failure(ex) =>
                // if the lookup fails, complete the promise with the failed future
                p.failure(ex)
                // but still run the rest of the operations whose lookup didn't fail
                Success(None)
              case Success(operation) =>
                Success(Some(EnqueuedCoinOperation(coinOperation, p)))
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
      batch: Seq[EnqueuedCoinOperation]
  ): Future[String] =
    TraceContext.withNewTraceContext { implicit tc =>
      retryProvider.retry("execute coin operation batch", executeBatch(batch))
    }

  private def executeBatch(
      batch: Seq[EnqueuedCoinOperation]
  )(implicit tc: TraceContext): Future[String] = {

    logger.debug(
      s"Running batch of coin operations for user ${userStore.key.endUserParty} with length ${batch.size}: $batch"
    )

    for {
      validOperations <- runLookups(batch)
      _ = logger.debug(
        s"After lookups, the batch contains ${validOperations.size} coin operations."
      )
      // TODO(#1351): smarter coin/input selection?
      inputs <- userStore
        .listContracts(coinCodegen.Coin)
        .map(cs => cs.value.map(c => coinRulesCodegen.TransferInput.InputCoin(c.contractId)))
      transferContext <- getValidatorStore().getPaymentTransferContext()
      activeIssuingRounds = transferContext.context.issuingMiningRounds.keys.toSet
      inputs <- selectTransferInputs(activeIssuingRounds)
      cmd =
        install.contractId.exerciseWalletAppInstall_ExecuteBatch(
          transferContext,
          inputs,
          validOperations.map(_.operationWithLookups.operation),
        )
      (offset, outcomes) <- connection
        .submitWithResultAndOffset(
          Seq(walletStoreKey.walletServiceParty),
          Seq(walletStoreKey.validatorParty, userStore.key.endUserParty),
          cmd,
        )
      _ = (outcomes zip validOperations).map {
        case (outcome, EnqueuedCoinOperation(operation, p)) =>
          p.success(outcome)
      }
    } yield offset
  }

  /** Select transfer inputs to satisfy the coin operations.
    * Currently, this function selects all unlocked coins and all currently redeemable app- and validator rewards.
    */
  private def selectTransferInputs(
      activeIssuingRounds: Set[roundCodegen.Round]
  )(implicit tc: TraceContext): Future[Seq[coinRulesCodegen.TransferInput]] = for {
    coins <- userStore
      .listContracts(coinCodegen.Coin)
      .map(cs => cs.value.map(c => coinRulesCodegen.TransferInput.InputCoin(c.contractId)))
    validatorRewardsRaw <- walletStore
      .listValidatorRewardsCollectableBy(userStore)
    validatorRewards = validatorRewardsRaw
      .filter(rw => activeIssuingRounds.contains(rw.payload.round))
      .map(rw => coinRulesCodegen.TransferInput.InputValidatorReward(rw.contractId))
    appRewards <- userStore
      .listContracts(coinCodegen.AppReward)
      .map(rws =>
        rws.value
          .filter(rw => activeIssuingRounds.contains(rw.payload.round))
          .map(rw => coinRulesCodegen.TransferInput.InputAppReward(rw.contractId))
      )
  } yield coins ++ validatorRewards ++ appRewards

  // We fetch this on demand here to avoid a dependency of the validator store being
  // setup before other user’s stores.
  private def getValidatorStore(): EndUserWalletStore =
    walletStore
      .lookupEndUserStore(walletStore.key.validatorUserName)
      .getOrElse(
        throw new StatusRuntimeException(
          Status.FAILED_PRECONDITION.withDescription("Validator store not setup yet")
        )
      )

  override def onClosed(): Unit = {
    // TODO(M1-92 - Tech Debt): add more robust shutdown, e.g., similar to shutdown for ledger subscriptions
    queue.complete()
  }
}

object EndUserTreasuryService {

  /** Wrapper helper class. */
  private case class EnqueuedCoinOperation(
      operationWithLookups: CoinOperationRequest,
      promise: Promise[walletCodegen.CoinOperationOutcome],
  )

}
