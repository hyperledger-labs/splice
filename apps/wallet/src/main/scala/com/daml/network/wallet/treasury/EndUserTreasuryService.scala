package com.daml.network.wallet.treasury

import akka.stream.QueueOfferResult.{Dropped, Enqueued, QueueClosed}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{BoundedSourceQueue, Materializer, QueueOfferResult}
import cats.syntax.traverse.*
import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.codegen.java.cc.{coin => coinCodegen}
import com.daml.network.codegen.java.cn.wallet.install as installCodegen
import com.daml.network.environment.{CoinLedgerConnection, CoinRetries}
import com.daml.network.util.{JavaContract as Contract}
import com.daml.network.wallet.store.{EndUserWalletStore, WalletStore}
import com.daml.network.wallet.treasury.EndUserTreasuryService.*
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.FlagCloseable
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.{Status, StatusRuntimeException}

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.*
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
case class CoinOperationRequest[T](
    operation: T => installCodegen.CoinOperation,
    tryLookups: () => Future[T],
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
    install: Contract[
      installCodegen.WalletAppInstall.ContractId,
      installCodegen.WalletAppInstall,
    ],
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

  private val queue: BoundedSourceQueue[AnyEnqueuedCoinOperation] = Source
    .queue[AnyEnqueuedCoinOperation](bufferSize)
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
  def enqueueCoinOperation[T](
      operation: CoinOperationRequest[T]
  )(implicit tc: TraceContext): Future[installCodegen.CoinOperationOutcome] = {
    // TODO(#1351): possibly allow callers to assign semantically meaningful ids (see the discussion
    //  at https://github.com/DACH-NY/the-real-canton-coin/pull/1145#discussion_r994508807)
    val p = Promise[installCodegen.CoinOperationOutcome]()
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
      batch: Seq[AnyEnqueuedCoinOperation]
  ): Future[Seq[ValidCoinOperation]] = {
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
              case Success(r) =>
                Success(Some(ValidCoinOperation(coinOperation.operation(r), p)))
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
      batch: Seq[AnyEnqueuedCoinOperation]
  ): Future[String] =
    TraceContext.withNewTraceContext { implicit tc =>
      retryProvider.retryForAutomation(
        "execute coin operation batch",
        executeBatch(batch),
        this,
      )
    }

  private def executeBatch(
      batch: Seq[AnyEnqueuedCoinOperation]
  )(implicit tc: TraceContext): Future[String] = {

    logger.debug(
      s"Running batch of coin operations for user ${userStore.key.endUserParty} with length ${batch.size}: $batch"
    )

    for {
      validOperations <- runLookups(batch)
      _ = logger.debug(
        s"After lookups, the batch contains ${validOperations.size} coin operations."
      )
      transferContext <- getValidatorStore().getPaymentTransferContext(retryProvider)
      activeIssuingRounds = transferContext.context.issuingMiningRounds.asScala.keys.toSet
      (inputs, readAs) <- selectTransferInputs(activeIssuingRounds)
      cmd =
        install.contractId.exerciseWalletAppInstall_ExecuteBatch(
          transferContext,
          inputs.asJava,
          validOperations.map(_.operation).asJava,
        )
      (offset, outcomes) <- connection
        .submitWithResultAndOffset(
          Seq(walletStoreKey.walletServiceParty),
          walletStoreKey.validatorParty +: userStore.key.endUserParty +: readAs.toSeq,
          cmd,
        )
      _ = (outcomes.exerciseResult.asScala zip validOperations).map {
        case (outcome, ValidCoinOperation(_, p)) =>
          p.success(outcome)
      }
    } yield offset
  }

  /** Select transfer inputs to satisfy the coin operations.
    * Currently, this function selects all unlocked coins and all currently redeemable app- and validator rewards.
    * Also returns the set of readAs parties required for the selected inputs.
    */
  private def selectTransferInputs(
      activeIssuingRounds: Set[v1.round.Round]
  )(implicit tc: TraceContext): Future[(Seq[v1.coin.TransferInput], Set[PartyId])] = for {
    coinInputs <- userStore
      .listContracts(coinCodegen.Coin.COMPANION)
      .map(cs =>
        cs.value.map(c =>
          new v1.coin.transferinput.InputCoin(c.contractId.toInterface(v1.coin.Coin.INTERFACE))
        )
      )
    validatorRewardsRaw <- walletStore
      .listValidatorRewardsCollectableBy(userStore)
    validatorRewards = validatorRewardsRaw
      .filter(rw => activeIssuingRounds.contains(rw.payload.round))
    validatorRewardUsers = validatorRewards
      .map(c => PartyId.tryFromProtoPrimitive(c.payload.user))
      .toSet
    validatorRewardInputs = validatorRewards
      .map(rw =>
        new v1.coin.transferinput.InputValidatorReward(
          rw.contractId.toInterface(v1.coin.ValidatorReward.INTERFACE)
        )
      )
    appRewardInputs <- userStore
      .listContracts(coinCodegen.AppReward.COMPANION)
      .map(rws =>
        rws.value
          .filter(rw => activeIssuingRounds.contains(rw.payload.round))
          .map(rw =>
            new v1.coin.transferinput.InputAppReward(
              rw.contractId.toInterface(v1.coin.AppReward.INTERFACE)
            )
          )
      )
  } yield (coinInputs ++ validatorRewardInputs ++ appRewardInputs, validatorRewardUsers)

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
  private case class EnqueuedCoinOperation[T](
      override val operationWithLookups: CoinOperationRequest[T],
      override val promise: Promise[installCodegen.CoinOperationOutcome],
  ) extends AnyEnqueuedCoinOperation {
    override type LookupResult = T
  }

  // Existential trait that hides the type parameter.
  // We cannot use EnqueuedCOinOperation[Any] since the type param
  // is invariant.
  sealed trait AnyEnqueuedCoinOperation {
    type LookupResult
    def operationWithLookups: CoinOperationRequest[LookupResult]
    def promise: Promise[installCodegen.CoinOperationOutcome]
  }

  private case class ValidCoinOperation(
      operation: installCodegen.CoinOperation,
      promise: Promise[installCodegen.CoinOperationOutcome],
  )
}
