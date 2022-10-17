package com.daml.network.wallet.admin.grpc

import akka.stream.{BoundedSourceQueue, Materializer}
import akka.stream.scaladsl.{Keep, Sink, Source}
import com.daml.network.codegen.CC.{Coin as coinCodegen, CoinRules as coinRulesCodegen}
import com.daml.network.codegen.CN.Wallet as walletCodegen
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.util.Contract
import com.daml.network.wallet.store.{EndUserWalletStore, WalletStore}
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ErrorUtil

import java.util.UUID
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future, Promise}

// TODO(#756): Add PrettyPrinting
case class CoinOperationWithId(id: String, operation: walletCodegen.CoinOperation)

/** This class encapsulates the logic that takes operations changing the coin holdings of an user and sequences them
  * such that concurrent manipulations don't conflict.
  *
  * For the design, please see https://github.com/DACH-NY/the-real-canton-coin/issues/913.
  */
// TODO(#756): error handling.
case class EndUserTreasuryService(
    connection: CoinLedgerConnection,
    install: Contract[walletCodegen.WalletAppInstall],
    walletStoreKey: WalletStore.Key,
    userStore: EndUserWalletStore,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext, mat: Materializer)
    extends NamedLogging
    with AutoCloseable {

  // Maps from an unique identifier for each coin operation to a promise for completing the coin operation.
  // Once an operation is completed, it is removed from the map
  private val outstandingOperations
      : scala.collection.concurrent.Map[String, Promise[walletCodegen.CoinOperationOutcome]] =
    TrieMap()

  // Magic numbers
  private val batchSize = 10L
  private val bufferSize = 1000
  private val waitTimeForStore = 300.millis

  private val queue: BoundedSourceQueue[CoinOperationWithId] = Source
    .queue[CoinOperationWithId](bufferSize)
    // TODO(#756): Possible extension: re-order commands according to urgency
    .batch(batchSize, operation => Seq(operation))((operations, operation) =>
      operations :+ operation
    )
    .mapAsync(1)(runBatch)
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
      operation: walletCodegen.CoinOperation
  )(implicit tc: TraceContext): Future[walletCodegen.CoinOperationOutcome] = {
    // TODO(#756): possibly allow callers to assign semantically meaningful ids (see also discussion
    //  at https://github.com/DACH-NY/the-real-canton-coin/pull/1145#discussion_r994508807)
    val id = UUID.randomUUID().toString
    // TODO(#756): error handling
    queue.offer(CoinOperationWithId(id, operation)): Unit
    val p = Promise[walletCodegen.CoinOperationOutcome]()
    outstandingOperations.addOne((id, p))
    logger.trace(s"received ${operation}, queue now: ${queue.size()}")
    p.future
  }

  private def runBatch(batch: Seq[CoinOperationWithId]): Future[Unit] = {
    TraceContext.withNewTraceContext { implicit tc =>
      logger.debug(
        s"Running batch of coin operations for user ${userStore.key.endUserParty.show} with length ${batch.size}: $batch"
      )
      val outcomes =
        for {
          // TODO(#756): smarter coin/input selection?
          inputs <- userStore
            .listContracts(coinCodegen.Coin)
            .map(cs => cs.value.map(c => coinRulesCodegen.TransferInput.InputCoin(c.contractId)))
          cmd =
            install.contractId.exerciseWalletAppInstall_ExecuteBatch(
              inputs,
              batch.map(_.operation),
            )
          res <- connection
            .submitWithResult(
              Seq(walletStoreKey.walletServiceParty),
              Seq(walletStoreKey.validatorParty, userStore.key.endUserParty),
              cmd,
            )
        } yield res
      outcomes
        .map(outcomes => {
          (outcomes zip batch).map { case (outcome, operation) =>
            val p = outstandingOperations
              .remove(operation.id)
              .getOrElse(
                ErrorUtil.invalidState(
                  s"couldn't find a promise for operation with id ${operation.id} and value: $operation"
                )
              )
            p.success(outcome)
          }
        })
        .map(_ => ())
    }
  }

  override def close(): Unit = {
    // TODO(M1-92 - Tech Debt): add more robust shutdown, e.g., similar to shutdown for ledger subscriptions
    queue.complete()
  }

}
