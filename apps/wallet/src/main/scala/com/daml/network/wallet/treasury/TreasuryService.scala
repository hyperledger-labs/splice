package com.daml.network.wallet.treasury

import akka.Done
import akka.stream.QueueOfferResult.{Dropped, Enqueued, QueueClosed}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{BoundedSourceQueue, Materializer, QueueOfferResult}
import cats.syntax.traverse.*
import com.daml.ledger.javaapi.data.codegen.Exercised
import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.codegen.java.cc.api.v1.coin.{PaymentTransferContext, TransferInput}
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cn.wallet.install.coinoperationoutcome.COO_MergeTransferInputs
import com.daml.network.codegen.java.cn.wallet.install.{
  CoinOperationOutcome,
  WalletAppInstall,
  coinoperation,
}
import com.daml.network.codegen.java.cn.wallet.{
  install as installCodegen,
  payment as walletCodegen,
  subscriptions as subsCodegen,
  transferoffer as transferOffersCodegen,
}
import com.daml.network.environment.{CoinLedgerConnection, CoinRetries}
import com.daml.network.util.PrettyInstances.*
import com.daml.network.util.{HasHealth, JavaContract}
import com.daml.network.wallet.UserWalletManager
import com.daml.network.wallet.config.TreasuryConfig
import com.daml.network.wallet.store.UserWalletStore
import com.daml.network.wallet.treasury.TreasuryService.*
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.FlagCloseable
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging, TracedLogger}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.{Failure, Success}

/** This class encapsulates the logic that sequences all operations which change the coin holdings of an user such
  * that concurrent manipulations don't conflict.
  *
  * For the design, please see https://github.com/DACH-NY/the-real-canton-coin/issues/913
  */
class TreasuryService(
    connection: CoinLedgerConnection,
    treasuryConfig: TreasuryConfig,
    clock: Clock,
    userStore: UserWalletStore,
    walletManager: UserWalletManager,
    retryProvider: CoinRetries,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val timeouts: ProcessingTimeout,
)(implicit ec: ExecutionContext, mat: Materializer, tracer: Tracer)
    extends NamedLogging
    with FlagCloseable
    with Spanning
    with HasHealth {

  private val batchExecutorRunning = new AtomicBoolean(true)

  private val queue: BoundedSourceQueue[EnqueuedCoinOperation] =
    withNewTrace(this.getClass.getSimpleName)(implicit tc =>
      _ => {
        val queue = Source
          .queue[EnqueuedCoinOperation](treasuryConfig.queueSize)
          .batch(treasuryConfig.batchSize.toLong, operation => CoinOperationBatch(operation))(
            (batch, operation) => batch.addCOToBatch(operation)
          )
          // Execute the batches sequentially to avoid contention
          .mapAsync(1)(executeBatchWithRetry)
          .toMat(
            Sink.onComplete(result => {
              if (isClosing)
                logger.debug(
                  show"Coin operation batch executor shutting down with result: ${result.toString.unquoted}"
                )
              else
                logger.error(
                  show"Unexpected termination of coin operation batch executor with result: ${result.toString.unquoted}"
                )
              batchExecutorRunning.set(false)
            })
          )(Keep.left)
          .run()
        logger.debug(
          show"Started coin operation operation batch executor with ${treasuryConfig.toString.unquoted}"
        )
        queue
      }
    )

  override def isHealthy: Boolean = batchExecutorRunning.get()

  // Overriding, as this method is used in "FlagCloseable" to pretty-print the object being closed.
  override def toString: String =
    show"TreasureService(endUserParty=${userStore.key.endUserParty})"

  /** Enqueues a coin operation into an internal task queue.
    * The [[TreasuryService]] will schedule the operation and then complete the returned with its result.
    */
  def enqueueCoinOperation[T](
      operation: installCodegen.CoinOperation
  )(implicit tc: TraceContext): Future[installCodegen.CoinOperationOutcome] = {
    val p = Promise[installCodegen.CoinOperationOutcome]()
    queue.offer(EnqueuedCoinOperation(operation, p, tc)) match {
      case Enqueued =>
        logger.debug(show"Received operation (queue size: ${queue.size()}): $operation")
        p.future
      // TODO(M3-90): add tests for the failure cases.
      case Dropped =>
        Future.failed(
          new StatusRuntimeException(
            Status.ABORTED.withDescription(
              show"Aborted operation as there are too many (currently ${queue
                  .size()}, max ${treasuryConfig.queueSize}) already in flight: $operation"
            )
          )
        )
      case QueueOfferResult.Failure(cause) => Future.failed(cause)
      case QueueClosed =>
        Future.failed(
          new StatusRuntimeException(
            Status.UNAVAILABLE.withDescription(
              show"Rejected operation because the coin operation batch executor is shutting down: $operation"
            )
          )
        )
    }
  }

  /** Find all operations that have become stale and complete them with their failure.
    * This effectively removes these operations from the next retry of executing the batch.
    */
  private def completeStaleOperations(
      batch: CoinOperationBatch
  )(implicit tc: TraceContext): Future[Done] = {
    batch.operationsToRun
      .traverse { op =>
        tryLookupCoinOperation(op.operation)
          .transform {
            case Failure(ex) =>
              logger.debug(show"Failing operation due to failed lookup: $op", ex)
              // if the lookup fails, complete the promise with the failed future
              op.outcomePromise.failure(ex)
              Success(Done)
            case Success(_) =>
              Success(Done)
          }
      }
      .map(_ => Done)
  }

  /** Background behind lookups:
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
    * Implementation:
    * If a required contract is not found, the future should fail with an appropriate
    * [[io.grpc.StatusRuntimeException]].
    * We execute `completeStaleOperations` after every batch that failed.
    */
  private def tryLookupCoinOperation(op0: installCodegen.CoinOperation): Future[Unit] = op0 match {
    case op: coinoperation.CO_SubscriptionAcceptAndMakeInitialPayment =>
      for {
        subscriptionRequest <- userStore.acs.getContractById(
          subsCodegen.SubscriptionRequest.COMPANION
        )(op.contractIdValue)
        _ <- userStore.acs.getContractById(subsCodegen.SubscriptionContext.INTERFACE)(
          subscriptionRequest.value.payload.subscriptionData.context
        )
      } yield ()

    case op: coinoperation.CO_SubscriptionMakePayment =>
      for {
        subscriptionState <- userStore.acs.getContractById(
          subsCodegen.SubscriptionIdleState.COMPANION
        )(op.contractIdValue)
        _ <- userStore.acs.getContractById(subsCodegen.SubscriptionContext.INTERFACE)(
          subscriptionState.value.payload.subscriptionData.context
        )
      } yield ()

    case op: coinoperation.CO_AppPayment =>
      for {
        paymentRequest <- userStore.acs.getContractById(walletCodegen.AppPaymentRequest.COMPANION)(
          op.contractIdValue
        )
        _ <- userStore.acs.getContractById(walletCodegen.DeliveryOffer.INTERFACE)(
          paymentRequest.value.payload.deliveryOffer
        )
      } yield ()

    case op: coinoperation.CO_CompleteAcceptedTransfer =>
      for {
        _ <- userStore.acs.getContractById(transferOffersCodegen.AcceptedTransferOffer.COMPANION)(
          op.contractIdValue
        )
      } yield ()

    case _: coinoperation.CO_MergeTransferInputs => Future.unit

    case _: coinoperation.CO_Tap => Future.unit

    case op => throw new NotImplementedError(show"Unexpected coin operation: $op")
  }

  /** In case of contention, the `executeBatch` function may fail. This function adds retries so that a single coin
    * operation, that failed due to contention, does not require a whole batch of coin operations to be resubmitted
    * to the wallet app.
    */
  private def executeBatchWithRetry(
      batch: CoinOperationBatch
  )(implicit tc: TraceContext): Future[Done] = {
    def completeWithUnexpectedFailure(op: EnqueuedCoinOperation) =
      // Need to use tryComplete as some of them might have been completed already due to being stale
      op.outcomePromise.tryComplete(
        Failure(
          Status.INTERNAL
            .withDescription("Unexpected coin operation execution failure.")
            .asRuntimeException()
        )
      )

    withSpan("executeBatchWithRetry") { implicit tc => _ =>
      retryProvider
        .retryForAutomation(
          "execute coin operation batch",
          executeBatch(batch),
          this,
        )
        .recover(ex => {
          if (this.isClosing) {
            // TODO(tech-debt): we have too many of these guards for closing -- see whether there is a better way, and thereby squeeze out the lurking concurrency and shutdown problems.
            logger.info("Ignoring batch execution failure, as we are shutting down", ex)
          } else
            logger.error("Skipping batch due to unexpected execution failure", ex)
          // Complete all operations of this batch
          batch.nonMergeOperations.foreach(completeWithUnexpectedFailure)
          batch.mergeOperationOpt.foreach(completeWithUnexpectedFailure)
          Done
        })
    }
  }

  private def isErrorOutcome(outcome: installCodegen.CoinOperationOutcome): Boolean =
    outcome match {
      case _: installCodegen.coinoperationoutcome.COO_Error => true
      case _ => false
    }

  private def executeBatch(
      unfilteredBatch: CoinOperationBatch
  )(implicit tc: TraceContext): Future[Done] = {

    // Remove all operations from the batch whose promise has already been completed.
    // We use this approach as the retry infrastructure retries a fixed operation, and we want to avoid
    // introducing more mutable state that can go awry.
    // We accept the cost of repeatedly filtering the batch, as batches are expected to be small.
    val filteredBatch = unfilteredBatch.computeFilteredBatch
    logger.debug(
      show"Running batch of coin operations:\n$filteredBatch"
    )

    if (filteredBatch.isEmpty) {
      logger.debug("Coin operation batch was empty after filtering. ")
      Future.successful(Done)
    } else {
      val now = clock.now
      val batchExecutionF = for {
        transferContext <- walletManager.store.getPaymentTransferContext(retryProvider, now)
        activeIssuingRounds = transferContext.context.issuingMiningRounds.asScala.keys.toSet
        install <- getInstall
        (inputs, readAs) <- selectTransferInputs(activeIssuingRounds)
        res <-
          // skip execution of the batch, if its only purpose is to merge the inputs, but the inputs are already merged.
          if (inputs.length == 1 && filteredBatch.isMergeOnly) {
            filteredBatch.mergeOperationOpt.foreach(
              _.outcomePromise.trySuccess(new COO_MergeTransferInputs(None.toJava))
            )
            Future.successful(Done)
          } else {
            executeFilteredBatch(install, transferContext, inputs, filteredBatch, readAs)
          }
      } yield res
      batchExecutionF.recoverWith(ex => {
        logger.info("Checking staleness of coin operations, as batch execution failed", ex)
        completeStaleOperations(filteredBatch).transform {
          case Failure(exStale) =>
            logger.error("Ignoring unexpected exception during staleness check", exStale)
            throw ex
          case Success(_) =>
            throw ex
        }
      })
    }
  }

  /** Helper method to execute a batch that already has been filtered to only contain uncompleted operations.
    * Note that for performance reasons, we assume that all operations in a batch are uncompleted on its initial
    * submission and only look for stale operations that can be marked as complete after the initial batch execution
    * submission failed.
    */
  private def executeFilteredBatch(
      install: JavaContract[WalletAppInstall.ContractId, WalletAppInstall],
      transferContext: PaymentTransferContext,
      inputs: Seq[TransferInput],
      batch: CoinOperationBatch,
      readAs: Set[PartyId],
  )(implicit tc: TraceContext) = {
    val cmd = batch.computeExecuteBatchCmd(install, transferContext, inputs)
    for {
      (offset, outcomes) <- connection
        // TODO(M3-02): as of 2022-11-25 there are two operations that are not self-conflicting: Tap and DirectTransfer,
        // which implies that network problems might lead to duplicate 'DirectTransfer' calls. They will be replaced by
        // TransferOffers as part of M3-02, which will consume the TransferOffer, and thus make the batch-execution w/o command dedup safe.
        .submitWithResultAndOffsetNoDedup(
          Seq(walletManager.store.key.walletServiceParty),
          walletManager.store.key.validatorParty +: userStore.key.endUserParty +: readAs.toSeq,
          cmd,
        )
      // return all outcomes to the callers
      _ = batch.completeBatchOperations(outcomes)(logger, tc)

      // wait for store to ingest the new coin holdings *provided* they were updated
      case () <- if (outcomes.exerciseResult.asScala.forall(isErrorOutcome)) {
        // We must not wait in this case, as the store won't see that offset until the next action comes,
        // as the transaction filter is in the way
        // TODO(tech-debt): remove this fragility of depending on the exact daml transaction to determine whether to wait or not
        Future.unit
      } else {
        logger.debug(show"Waiting for store to ingest offset ${offset.singleQuoted}")
        userStore.signalWhenIngested(offset)
      }
    } yield Done
  }

  /** Select transfer inputs to satisfy the coin operations.
    * Currently, this function selects all unlocked coins and all currently redeemable app- and validator rewards.
    * Also returns the set of readAs parties required for the selected inputs.
    */
  private def selectTransferInputs(
      activeIssuingRounds: Set[v1.round.Round]
  )(implicit tc: TraceContext): Future[(Seq[v1.coin.TransferInput], Set[PartyId])] = for {
    coinInputs <- userStore.acs
      .listContracts(coinCodegen.Coin.COMPANION)
      .map(cs =>
        cs.value.map(c =>
          new v1.coin.transferinput.InputCoin(c.contractId.toInterface(v1.coin.Coin.INTERFACE))
        )
      )
    validatorRewardsRaw <- walletManager
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
    appRewardInputs <- userStore.acs
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

  private def getInstall =
    userStore
      .lookupInstall()
      .map(
        _.value.getOrElse(
          throw Status.NOT_FOUND.withDescription("WalletAppInstall contract").asRuntimeException()
        )
      )

  override def onClosed(): Unit = {
    logger.debug(
      show"Shutdown initiated, closing coin operation batch execution queue, which currently contains ${queue.size()} elements)."
    )(
      TraceContext.empty
    )
    queue.complete()
  }
}

object TreasuryService {

  /** Helper class for the batches of coin operations executed by the treasury service.
    * Mainly introduced to handle to cleanly separate the logic around managing CO_MergeTransferInputs.
    *
    * @param mergeOperationOpt tracks the CO_MergeTransferInputs operation if there is one as part of the batch.
    *                           tracked separately because it doesn't make sense for there
    *                           to be multiple merge operations in a single batch.
    */
  private case class CoinOperationBatch(
      mergeOperationOpt: Option[EnqueuedCoinOperation],
      nonMergeOperations: Seq[EnqueuedCoinOperation],
  ) extends PrettyPrinting {
    override def pretty: Pretty[CoinOperationBatch.this.type] = prettyOfClass(
      param("mergeOperationOpt", _.mergeOperationOpt),
      param("nonMergeOperations", _.nonMergeOperations),
    )

    /** Computes the coin operations that should be run on the ledger given the current batch state. */
    lazy val operationsToRun: Seq[EnqueuedCoinOperation] = {
      // if the batch is only a merge-operation - run that - else use the nonMergeOperations and don't include the
      // mergeOperation
      if (isMergeOnly) mergeOperationOpt.toList else nonMergeOperations
    }

    lazy val isEmpty: Boolean = operationsToRun.isEmpty

    def computeFilteredBatch: CoinOperationBatch = {
      CoinOperationBatch(
        mergeOperationOpt,
        nonMergeOperations.filter(!_.outcomePromise.isCompleted),
      )
    }

    def isMergeOnly: Boolean = mergeOperationOpt.isDefined && nonMergeOperations.isEmpty
    def addCOToBatch(operation: EnqueuedCoinOperation): CoinOperationBatch = {
      val isMergeOp = isCO_MergeTransferInputs(operation)
      mergeOperationOpt match {
        case None if isMergeOp => CoinOperationBatch(Some(operation), nonMergeOperations)
        case Some(_) if isMergeOp =>
          // if we already have a merge operation in this batch; complete the new one immediately and
          // don't add it to the batch
          operation.outcomePromise.success(new COO_MergeTransferInputs(None.toJava))
          this
        case _ =>
          CoinOperationBatch(mergeOperationOpt, nonMergeOperations :+ operation)
      }
    }

    def computeExecuteBatchCmd(
        install: JavaContract[WalletAppInstall.ContractId, WalletAppInstall],
        transferContext: PaymentTransferContext,
        inputs: Seq[TransferInput],
    ) = {
      install.contractId.exerciseWalletAppInstall_ExecuteBatch(
        transferContext,
        inputs.asJava,
        operationsToRun.map(_.operation).asJava,
      )
    }

    def completeBatchOperations(
        outcomes: Exercised[java.util.List[CoinOperationOutcome]]
    )(implicit logger: TracedLogger, tc: TraceContext) = {
      (outcomes.exerciseResult.asScala zip operationsToRun).foreach { case (outcome, op) =>
        logger.debug(show"Completing operation $op with result ${outcome.toValue}")
        op.outcomePromise.success(outcome)
      }
      // if this is not a merge-only batch, the maybe-existing merge wasn't included in the batch and thus still needs to be
      // completed.
      if (!isMergeOnly)
        mergeOperationOpt.foreach(op =>
          op.outcomePromise.success(new COO_MergeTransferInputs(None.toJava))
        )
    }
  }

  private object CoinOperationBatch {
    def apply(operation: EnqueuedCoinOperation): CoinOperationBatch = {
      CoinOperationBatch(None, Seq.empty).addCOToBatch(operation)
    }
  }

  private def isCO_MergeTransferInputs(enqueued: EnqueuedCoinOperation): Boolean =
    enqueued.operation match {
      case _: coinoperation.CO_MergeTransferInputs => true
      case _ => false
    }

  private case class EnqueuedCoinOperation(
      operation: installCodegen.CoinOperation,
      outcomePromise: Promise[installCodegen.CoinOperationOutcome],
      submittedFrom: TraceContext,
  ) extends PrettyPrinting {
    override def pretty: Pretty[EnqueuedCoinOperation.this.type] =
      prettyNode(
        "CoinOperation",
        param("from", _.submittedFrom.showTraceId),
        param("op", _.operation.toValue),
      )
  }
}
