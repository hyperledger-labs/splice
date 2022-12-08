package com.daml.network.wallet.treasury

import akka.Done
import akka.stream.QueueOfferResult.{Dropped, Enqueued, QueueClosed}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{BoundedSourceQueue, Materializer, QueueOfferResult}
import cats.syntax.traverse.*
import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cn.wallet.install.coinoperation
import com.daml.network.codegen.java.cn.wallet.{
  install as installCodegen,
  payment as walletCodegen,
  paymentchannel as channelCodegen,
  subscriptions as subsCodegen,
  transferoffer as transferOffersCodegen,
}
import com.daml.network.environment.{CoinLedgerConnection, CoinRetries}
import com.daml.network.util.PrettyInstances.*
import com.daml.network.util.{HasHealth, TimeUtil}
import com.daml.network.wallet.UserWalletManager
import com.daml.network.wallet.config.TreasuryConfig
import com.daml.network.wallet.store.UserWalletStore
import com.daml.network.wallet.treasury.TreasuryService.*
import com.digitalasset.canton.config.{ClockConfig, ProcessingTimeout}
import com.digitalasset.canton.lifecycle.FlagCloseable
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success}

/** This class encapsulates the logic that sequences all operations which change the coin holdings of an user such
  * that concurrent manipulations don't conflict.
  *
  * For the design, please see https://github.com/DACH-NY/the-real-canton-coin/issues/913
  */
class TreasuryService(
    connection: CoinLedgerConnection,
    treasuryConfig: TreasuryConfig,
    clockConfig: ClockConfig,
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
          .batch(treasuryConfig.batchSize.toLong, operation => Seq(operation))(
            (operations, operation) => operations :+ operation
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
              show"Aborted operation as there are too many (${queue.size()}) already in flight: $operation"
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

  /** Runs the lookups, only returns the operation whose lookup succeeded and completes the promises for
    * the coin operations with failed lookups.
    */
  private def runLookups(
      batch: Seq[EnqueuedCoinOperation]
  )(implicit tc: TraceContext): Future[Seq[EnqueuedCoinOperation]] = {
    batch
      .traverse { op =>
        tryLookupCoinOperation(op.operation)
          .transform { lookupResult =>
            lookupResult match {
              case Failure(ex) =>
                logger.debug(show"Failing operation due to failed lookup: $op", ex)
                // if the lookup fails, complete the promise with the failed future
                op.outcomePromise.failure(ex)
                // but still run the rest of the operations whose lookup didn't fail
                Success(None)
              case Success(()) =>
                Success(Some(op))
            }
          }
      }
      .map(_.flatten)
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
    * [[io.grpc.StatusRuntimeException]]. We execute `tryLookupCoinOperation` run on every submission of the corresponding
    * coin operation (also for retries).
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

    case op: coinoperation.CO_ChannelPayment =>
      for {
        _ <- userStore.acs.getContractById(channelCodegen.OnChannelPaymentRequest.COMPANION)(
          op.contractIdValue
        )
      } yield ()

    case op: coinoperation.CO_ChannelTransfer =>
      for {
        _ <- userStore.acs.getContractById(channelCodegen.PaymentChannel.COMPANION)(
          op.channel
        )
      } yield ()

    case op: coinoperation.CO_CompleteAcceptedTransfer =>
      for {
        _ <- userStore.acs.getContractById(transferOffersCodegen.AcceptedTransferOffer.COMPANION)(
          op.contractIdValue
        )
      } yield ()

    case _: coinoperation.CO_Tap => Future.unit

    case op => throw new NotImplementedError(show"Unexpected coin operation: $op")
  }

  /** In case of contention, the `executeBatch` function may fail. This function adds retries so that a single coin
    * operation, that failed due to contention, does not require a whole batch of coin operations to be resubmitted
    * to the wallet app.
    */
  private def executeBatchWithRetry(
      batch: Seq[EnqueuedCoinOperation]
  )(implicit tc: TraceContext): Future[Done] =
    withSpan("executeBatchWithRetry") { implicit tc => _ =>
      retryProvider
        .retryForAutomation(
          "execute coin operation batch",
          executeBatch(batch),
          this,
        )
        .recover(ex => {
          logger.error("Skipping batch due to unexpected execution failure", ex)
          batch.foreach(op =>
            op.outcomePromise.failure(
              Status.INTERNAL
                .withDescription("Unexpected coin operation execution failure.")
                .asRuntimeException()
            )
          )
          Done
        })
    }

  private def executeBatch(
      batch: Seq[EnqueuedCoinOperation]
  )(implicit tc: TraceContext): Future[Done] = {
    logger.debug(
      show"Running batch of coin operations:${System.lineSeparator()}$batch"
    )

    def isErrorOutcome(outcome: installCodegen.CoinOperationOutcome): Boolean = outcome match {
      case _: installCodegen.coinoperationoutcome.COO_Error => true
      case _ => false
    }

    runLookups(batch).flatMap {
      // skip batches that are empty after lookups.
      case validOperations if (validOperations.isEmpty) =>
        logger.debug("Found no valid coin operations after running lookups.")
        Future.successful(Done)
      case validOperations =>
        for {
          now <- TimeUtil.getTime(connection, clockConfig)
          transferContext <- walletManager.store.getPaymentTransferContext(retryProvider, now)
          activeIssuingRounds = transferContext.context.issuingMiningRounds.asScala.keys.toSet
          install <- getInstall
          (inputs, readAs) <- selectTransferInputs(activeIssuingRounds)
          cmd =
            install.contractId.exerciseWalletAppInstall_ExecuteBatch(
              transferContext,
              inputs.asJava,
              validOperations.map(_.operation).asJava,
            )

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
          _ = (outcomes.exerciseResult.asScala zip validOperations).map { case (outcome, op) =>
            logger.debug(show"Completing operation $op with result ${outcome.toValue}")
            op.outcomePromise.success(outcome)
          }
          // wait for store to ingest the new coin holdings *provided* they were updated
          case () <- if (outcomes.exerciseResult.asScala.forall(isErrorOutcome)) {
            // We must not wait in this case, as the store won't see that offset until the next action comes,
            // as the transaction filter is in the way
            // TODO(M1-92): remove this fragility of depending on the exact daml transaction to determine whether to wait or not
            Future.unit
          } else {
            logger.debug(show"Waiting for store to ingest offset ${offset.singleQuoted}")
            userStore.signalWhenIngested(offset)
          }
        } yield Done
    }
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
