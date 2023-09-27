package com.daml.network.wallet.treasury

import akka.Done
import akka.stream.{BoundedSourceQueue, Materializer, QueueOfferResult}
import akka.stream.QueueOfferResult.{Dropped, Enqueued, QueueClosed}
import akka.stream.scaladsl.{Keep, Sink, Source}
import com.daml.ledger.javaapi.data.codegen.Exercised
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.codegen.java.cc.api.v1.round as roundApi
import com.daml.network.codegen.java.cc.api.v1.coin.{
  PaymentTransferContext,
  TransferContext,
  TransferInput,
  ValidatorRight,
}
import com.daml.network.codegen.java.cc.api.v1.coin.transferinput.{
  InputAppRewardCoupon,
  InputCoin,
  InputValidatorRewardCoupon,
}
import com.daml.network.codegen.java.cc.round.IssuingMiningRound
import com.daml.network.codegen.java.cn.wallet.{
  install as installCodegen,
  payment as walletCodegen,
  subscriptions as subsCodegen,
  transferoffer as transferOffersCodegen,
}
import com.daml.network.codegen.java.cn.wallet.install.{
  ExecuteBatchResult,
  WalletAppInstall,
  coinoperation,
}
import com.daml.network.codegen.java.cn.wallet.install.coinoperationoutcome.COO_MergeTransferInputs
import com.daml.network.environment.{CNLedgerConnection, CommandPriority, RetryProvider}
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.util.{CNNodeUtil, Contract, DisclosedContracts, HasHealth}
import com.daml.network.util.PrettyInstances.*
import com.daml.network.wallet.UserWalletManager
import com.daml.network.wallet.config.TreasuryConfig
import com.daml.network.wallet.store.UserWalletStore
import com.daml.network.wallet.treasury.TreasuryService.*
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.{
  AsyncCloseable,
  AsyncOrSyncCloseable,
  FlagCloseableAsync,
  RunOnShutdown,
}
import com.digitalasset.canton.logging.{
  ErrorLoggingContext,
  NamedLoggerFactory,
  NamedLogging,
  TracedLogger,
}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.{Failure, Success}

/** This class encapsulates the logic that sequences all operations which change the coin holdings of an user such
  * that concurrent manipulations don't conflict.
  *
  * For the design, please see https://github.com/DACH-NY/canton-network-node/issues/913
  */
class TreasuryService(
    connection: CNLedgerConnection,
    treasuryConfig: TreasuryConfig,
    clock: Clock,
    userStore: UserWalletStore,
    walletManager: UserWalletManager,
    override protected[this] val retryProvider: RetryProvider,
    scanConnection: ScanConnection,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext, mat: Materializer, tracer: Tracer)
    extends NamedLogging
    with FlagCloseableAsync
    with RetryProvider.Has
    with Spanning
    with HasHealth {

  private implicit val elc: ErrorLoggingContext =
    ErrorLoggingContext(logger, Map.empty, TraceContext.empty)

  private val queueTerminationResult: Promise[Done] = Promise()

  private val queue: BoundedSourceQueue[EnqueuedCoinOperation] = {
    val queue = Source
      .queue[EnqueuedCoinOperation](treasuryConfig.queueSize)
      .batchWeighted(
        treasuryConfig.batchSize.toLong,
        operation =>
          if (operation.priority == CommandPriority.High) treasuryConfig.batchSize.toLong + 1L
          else 1L,
        operation => CoinOperationBatch(operation),
      )((batch, operation) => batch.addCOToBatch(operation))
      // Execute the batches sequentially to avoid contention
      .mapAsync(1)(filterAndExecuteBatch)
      .toMat(
        Sink.onComplete(result0 => {
          val result =
            retryProvider
              .logTerminationAndRecoverOnShutdown("coin operation batch executor", logger)(
                result0
              )(TraceContext.empty)
          val _ = queueTerminationResult.tryComplete(result)
        })
      )(Keep.left)
      .run()
    logger.debug(
      show"Started coin operation operation batch executor with ${treasuryConfig.toString.unquoted}"
    )(TraceContext.empty)
    queue
  }

  retryProvider.runOnShutdown_(new RunOnShutdown {
    override def name: String = s"terminate coin operation batch executor"
    override def done: Boolean = queueTerminationResult.isCompleted
    override def run(): Unit = {
      logger.debug("Terminating coin operation batch executor, as we are shutting down.")(
        TraceContext.empty
      )
      try queue.complete()
      catch {
        // thrown on completing an already-complete queue
        case _: IllegalStateException => ()
      }
    }
  })(TraceContext.empty)

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] =
    Seq[AsyncOrSyncCloseable](
      AsyncCloseable(
        "waiting for coin operation batch executor shutdown",
        queueTerminationResult.future,
        timeouts.shutdownShort.unwrap,
      )
    )

  override def isHealthy: Boolean = !queueTerminationResult.isCompleted

  // Overriding, as this method is used in "FlagCloseable" to pretty-print the object being closed.
  override def toString: String =
    show"TreasureService(endUserParty=${userStore.key.endUserParty})"

  /** Enqueues a coin operation into an internal task queue.
    * The [[TreasuryService]] will schedule the operation and then complete the returned with its result.
    */
  def enqueueCoinOperation[T](
      operation: installCodegen.CoinOperation,
      priority: CommandPriority = CommandPriority.Low,
  )(implicit tc: TraceContext): Future[installCodegen.CoinOperationOutcome] = {
    val p = Promise[installCodegen.CoinOperationOutcome]()
    logger.debug(
      show"Received operation (queue size before adding this: ${queue.size()}): $operation"
    )
    queue.offer(EnqueuedCoinOperation(operation, p, tc, priority)) match {
      case Enqueued =>
        logger.debug(show"Operation $operation enqueued successfully")
        p.future
      case Dropped =>
        Future.failed(
          Status.ABORTED
            .withDescription(
              show"Aborted operation - likely because there are too many operations (currently ${queue
                  .size()}, max ${treasuryConfig.queueSize}) already in flight: $operation"
            )
            .asRuntimeException()
        )
      case QueueOfferResult.Failure(cause) => Future.failed(cause)
      case QueueClosed =>
        Future.failed(
          closingException(operation)
        )
    }
  }

  private def closingException(operation: installCodegen.CoinOperation) =
    Status.UNAVAILABLE
      .withDescription(
        show"Rejected operation because the coin operation batch executor is shutting down: $operation"
      )
      .asRuntimeException

  // Try looking up the contracts relevant for identifying operation staleness. Will throw a [[io.grpc.StatusRuntimeException]] if not found.
  private def tryLookupCoinOperation(
      op0: installCodegen.CoinOperation
  )(implicit tc: TraceContext): Future[Unit] =
    userStore.domains.waitForDomainConnection(userStore.defaultAcsDomain).flatMap { domainId =>
      op0 match {
        case op: coinoperation.CO_SubscriptionAcceptAndMakeInitialPayment =>
          for {
            subscriptionRequest <- userStore.multiDomainAcsStore.getContractByIdOnDomain(
              subsCodegen.SubscriptionRequest.COMPANION
            )(domainId, op.contractIdValue)
            _ <- userStore.multiDomainAcsStore.getContractByIdOnDomain(
              subsCodegen.SubscriptionContext.INTERFACE
            )(domainId, subscriptionRequest.payload.subscriptionData.context)
          } yield ()

        case op: coinoperation.CO_SubscriptionMakePayment =>
          for {
            subscriptionState <- userStore.multiDomainAcsStore.getContractByIdOnDomain(
              subsCodegen.SubscriptionIdleState.COMPANION
            )(domainId, op.contractIdValue)
            _ <- userStore.multiDomainAcsStore
              .getContractByIdOnDomain(subsCodegen.SubscriptionContext.INTERFACE)(
                domainId,
                subscriptionState.payload.subscriptionData.context,
              )
          } yield ()

        case op: coinoperation.CO_AppPayment =>
          for {
            paymentRequest <- userStore.multiDomainAcsStore.getContractByIdOnDomain(
              walletCodegen.AppPaymentRequest.COMPANION
            )(domainId, op.contractIdValue)
            _ <- userStore.multiDomainAcsStore.getContractByIdOnDomain(
              walletCodegen.DeliveryOffer.INTERFACE
            )(domainId, paymentRequest.payload.deliveryOffer)
          } yield ()

        case op: coinoperation.CO_CompleteAcceptedTransfer =>
          for {
            _ <- userStore.multiDomainAcsStore.getContractByIdOnDomain(
              transferOffersCodegen.AcceptedTransferOffer.COMPANION
            )(domainId, op.contractIdValue)
          } yield ()

        case _: coinoperation.CO_MergeTransferInputs => Future.unit

        case _: coinoperation.CO_Tap => Future.unit

        // TODO(tech-debt): Ideally, we should modify the BuyMemberTraffic choice to also return
        //  the ValidatorTopUpState contract Id in the COOutcome and ingest these into the store
        //  in order to do the staleness check here. BuyMemberTraffic txs are always placed into
        //  their own batch so a failure of this tx should not impact other coin txs.
        case _: coinoperation.CO_BuyMemberTraffic => Future.unit

        case op => throw new NotImplementedError(show"Unexpected coin operation: $op")
      }
    }

  private def isErrorOutcome(outcome: installCodegen.CoinOperationOutcome): Boolean =
    outcome match {
      case _: installCodegen.coinoperationoutcome.COO_Error => true
      case _ => false
    }

  // Checks an operation for staleness. If it is stale - completes it with a failure, and returns None.
  // Otherwise, returns the operation.
  private def completeIfStale(
      op: EnqueuedCoinOperation
  )(implicit tc: TraceContext): Future[Option[EnqueuedCoinOperation]] =
    for {
      res <- tryLookupCoinOperation(op.operation).transform {
        case Failure(ex) =>
          logger.debug(show"Failing operation due to failed lookup: $op", ex)
          // if the lookup fails, complete the promise with the failed future
          op.outcomePromise.failure(ex)
          Success(None)
        case Success(_) =>
          Success(Some(op))
      }
    } yield res

  // Due to contention, an operation may get queued for a while, and become stale. Since a DB lookup is significantly cheaper than
  // a failed batch, we filter out stale operations before submitting the batch.
  private def filterBatch(
      unfilteredBatch: CoinOperationBatch
  )(implicit tc: TraceContext): Future[CoinOperationBatch] =
    for {
      filteredNonMergeOperations <- Future.traverse(unfilteredBatch.nonMergeOperations)(
        completeIfStale(_)
      )

    } yield CoinOperationBatch(
      unfilteredBatch.mergeOperationOpt,
      filteredNonMergeOperations
        .filter(_.isDefined)
        .map(_.getOrElse(throw new RuntimeException("Unexpected None value"))),
    )

  private def filterAndExecuteBatch(
      unfilteredBatch: CoinOperationBatch
  ): Future[Done] = TraceContext.withNewTraceContext(implicit tc => {
    withSpan("executeBatch") { implicit tc => _ =>
      for {
        filteredBatch <- filterBatch(unfilteredBatch)
        res <- executeBatch(filteredBatch)
      } yield res
    }
  })

  private def executeBatch(
      batch: CoinOperationBatch
  )(implicit tc: TraceContext): Future[Done] = {
    // Remove all operations from the batch whose promise has already been completed.
    // We use this approach as the retry infrastructure retries a fixed operation, and we want to avoid
    // introducing more mutable state that can go awry.
    // We accept the cost of repeatedly filtering the batch, as batches are expected to be small.
    logger.debug(
      show"Running batch of coin operations:\n$batch"
    )

    def completeWithFailure(
        op: EnqueuedCoinOperation,
        throwable: Throwable,
    ) = {
      op.outcomePromise.complete(
        Failure(throwable)
      )
    }

    if (batch.isEmpty) {
      logger.debug("Coin operation batch was empty after filtering. ")
      Future.successful(Done)
    } else {

      val now = clock.now
      val batchExecutionF = for {
        install <- userStore.getInstall()
        contextAndInputsOpt <- getTransferContextAndInputs(
          now,
          batch.isMergeOnly,
          batch.numTapOperations,
        )
        res <-
          contextAndInputsOpt match {
            // if we returned None from getTransferContextAndInputs, this is (1) a batch that only contains a "merge" operation, but
            // (2) there is nothing to merge (e.g. only 1 coin + no rewards already, or rewards are too small)
            // in this case, we just complete the merge-operation immediately without sending any data to the ledger.
            case None =>
              batch.mergeOperationOpt.foreach(
                _.outcomePromise.trySuccess(new COO_MergeTransferInputs(None.toJava))
              )
              Future.successful(Done)
            case Some((inputs, readAs, transferContext, disclosedContracts)) =>
              doExecuteBatch(
                install,
                transferContext,
                inputs,
                batch,
                readAs,
                disclosedContracts,
              )
          }
      } yield res
      batchExecutionF.recover(ex => {
        logger.info(s"Batch failed with ${ex.getMessage()}, failing all operations")
        // Fail all operations of this batch
        batch.nonMergeOperations.foreach(completeWithFailure(_, ex))
        batch.mergeOperationOpt.foreach(completeWithFailure(_, ex))
        Done
      })
    }
  }

  /** Helper method to execute a batch.
    */
  private def doExecuteBatch(
      install: Contract[WalletAppInstall.ContractId, WalletAppInstall],
      transferContext: PaymentTransferContext,
      inputs: Seq[TransferInput],
      batch: CoinOperationBatch,
      readAs: Set[PartyId],
      disclosedContracts: DisclosedContracts.NE,
  )(implicit tc: TraceContext): Future[Done] = {
    val cmd = batch.computeExecuteBatchCmd(install, transferContext, inputs)
    logger.debug(s"executing batch $batch with inputs $inputs")
    for {
      (offset, result) <- connection
        .submit(
          Seq(walletManager.store.walletKey.validatorParty),
          userStore.key.endUserParty +: readAs.toSeq,
          cmd,
          priority = batch.priority,
        )
        .withDisclosedContracts(disclosedContracts)
        // The only operation that is not self-conflicting is Tap, therefore
        // batch execution w/o command dedup is safe.
        .noDedup
        .yieldResultAndOffset()

      // wait for store to ingest the new coin holdings, then return all outcomes to the callers
      _ <- waitForIngestion(offset, result).map(_ =>
        batch.completeBatchOperations(result)(logger, tc)
      )
    } yield Done
  }

  private def waitForIngestion(
      offset: String,
      outcomes: Exercised[ExecuteBatchResult],
  )(implicit tc: TraceContext): Future[Unit] =
    if (outcomes.exerciseResult.outcomes.asScala.forall(isErrorOutcome)) {
      // We must not wait in this case, as the store won't see that offset until the next action comes,
      // as the transaction filter is in the way
      // TODO(tech-debt): remove this fragility of depending on the exact daml transaction to determine whether to wait or not
      Future.unit
    } else {
      logger.debug(show"Waiting for store to ingest offset ${offset.singleQuoted}")
      userStore.signalWhenIngestedOrShutdown(offset)
    }

  private def shouldMergeOnlyTransferRun(
      totalRewardsQuantity: BigDecimal,
      coinInputsAndQuantity: Seq[(BigDecimal, InputCoin)],
      createFeeUsd: BigDecimal,
  )(implicit tc: TraceContext): Boolean = {
    val numCoinInputs = coinInputsAndQuantity.length
    val totalCoinQuantity = coinInputsAndQuantity.map(_._1).sum
    if (numCoinInputs <= 1) {
      val run = totalRewardsQuantity > createFeeUsd
      // only log when there are actually some rewards to possibly collect.
      if (!run && totalRewardsQuantity != 0)
        logger.debug(
          "Not executing a merge operation because there no coins to merge " +
            s"and the totalRewardsQuantity $totalRewardsQuantity is smaller than the create-fee $createFeeUsd"
        )
      run
    } else {
      val totalQuantity = totalRewardsQuantity + totalCoinQuantity
      val run = totalQuantity > createFeeUsd
      if (!run && totalQuantity != 0)
        logger.debug(
          "Not executing a merge operation because " +
            s"the total rewards and coin quantity ${totalQuantity} is smaller than the create-fee $createFeeUsd"
        )
      run
    }
  }

  private def getCoinRules()(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ) =
    for {
      rules <- scanConnection.getCoinRules()
    } yield (rules, rules.contractId.toInterface(v1.coin.CoinRules.INTERFACE))

  /** Select transfer inputs and transfer context to satisfy the coin operations.
    * Currently, this function selects all unlocked coins and all currently redeemable app- and validator rewards.
    * It checks that if the coin-operation batch only consists of a merge operation, it makes
    * sense to run this operation (e.g. it doesn't cost more to collect rewards in fees than they grant).
    * Also returns the set of readAs parties required for the selected inputs.
    */
  private def getTransferContextAndInputs(
      now: CantonTimestamp,
      isMergeOny: Boolean,
      numTapOperations: Int,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Option[
    (
        Seq[v1.coin.TransferInput],
        Set[PartyId],
        v1.coin.PaymentTransferContext,
        DisclosedContracts.NE,
    )
  ]] = {
    for {
      (disclosedCoinRules, coinRulesInterface) <- getCoinRules()
      (openRounds, issuingMiningRounds) <- scanConnection.getOpenAndIssuingMiningRounds()
      openRound = CNNodeUtil.selectLatestOpenMiningRound(now, openRounds)
      configUsd = openRound.payload.transferConfigUsd
      maxNumInputs = configUsd.maxNumInputs.intValue()
      openIssuingRounds = issuingMiningRounds.filter(c => c.payload.opensAt.isBefore(now.toInstant))
      issuingRoundsMap = openIssuingRounds.view.map { r =>
        val imr = r.payload
        (imr.round, imr)
      }.toMap
      contractsToDisclose = DisclosedContracts(
        disclosedCoinRules,
        openRound,
      ) addAll openIssuingRounds
      validatorRights <- walletManager.store.multiDomainAcsStore
        .listContracts(coinCodegen.ValidatorRight.COMPANION)
      coinInputsAndQuantity <- userStore.listSortedCoinsAndQuantity(
        maxNumInputs,
        openRound.payload.round.number,
      )
      (validatorRewardsCoinQuantity, validatorRewardCouponUsers, validatorRewardInputs) <-
        getValidatorRewardsAndQuantity(
          maxNumInputs,
          issuingRoundsMap,
        )
      (appRewardsTotalCoinQuantity, appRewardInputs) <- getAppRewardsAndQuantity(
        maxNumInputs,
        issuingRoundsMap,
      )
      validatorFeaturedAppRight <- walletManager.store.lookupValidatorFeaturedAppRight()
    } yield {
      val createFeeUsd = configUsd.createFee.fee
      if (
        isMergeOny && !shouldMergeOnlyTransferRun(
          appRewardsTotalCoinQuantity + validatorRewardsCoinQuantity,
          coinInputsAndQuantity,
          createFeeUsd,
        )
      ) {
        None
      } else {
        val inputs = constructTransferInputs(
          maxNumInputs,
          coinInputsAndQuantity.map(_._2),
          validatorRewardInputs,
          appRewardInputs,
          numTapOperations,
        )
        val rewardInputRounds =
          appRewardInputs.map(_._1).toSet ++ validatorRewardInputs.map(_._1).toSet
        val transferContext = new TransferContext(
          openRound.contractId
            .toInterface(v1.round.OpenMiningRound.INTERFACE),
          openIssuingRounds.view
            // only provide rounds that are actually used in transfer context to avoid unnecessary fetching.
            .filter(r => rewardInputRounds.contains(r.payload.round))
            .map(r =>
              (
                r.payload.round,
                r.contractId.toInterface(v1.round.IssuingMiningRound.INTERFACE),
              )
            )
            .toMap[roundApi.Round, roundApi.IssuingMiningRound.ContractId]
            .asJava,
          validatorRights
            // only provide validator rights that are actually used in transfer context to avoid unnecessary fetching.
            .filter(r =>
              validatorRewardCouponUsers.contains(PartyId.tryFromProtoPrimitive(r.payload.user))
            )
            .map(r => (r.payload.user, r.contractId.toInterface(v1.coin.ValidatorRight.INTERFACE)))
            .toMap[String, ValidatorRight.ContractId]
            .asJava,
          // The first (locking coin) leg of app rewards issues rewards to the wallet operator, and respects featured app rights.
          // We consider the validator to be the wallet operator, hence use the validator's featured app right (if it exists).
          validatorFeaturedAppRight
            .map(r => r.contractId.toInterface(v1.coin.FeaturedAppRight.INTERFACE))
            .toJava,
        )
        Some(
          (
            inputs,
            validatorRewardCouponUsers,
            new PaymentTransferContext(
              coinRulesInterface,
              transferContext,
            ),
            contractsToDisclose,
          )
        )
      }

    }
  }

  /** Selects the transfer inputs. Uses a heuristics that aims to:
    * - minimize fees,
    * - reduce spurious out-of-funds errors,
    * - and minimizes expirations rewards that could have been avoided.
    */
  private def constructTransferInputs(
      maxNumInputs: Int,
      coinInputs: Seq[InputCoin],
      validatorRewardInputs: Seq[(roundApi.Round, BigDecimal, InputValidatorRewardCoupon)],
      appRewardInputs: Seq[(roundApi.Round, BigDecimal, InputAppRewardCoupon)],
      numTapOperations: Int,
  ): Seq[TransferInput] = {
    val sortedRewardInputs = (validatorRewardInputs ++ appRewardInputs)
      .sorted(
        // prioritize the soonest-to-expire, most-valuable rewards.
        Ordering[(Long, BigDecimal)].on((rw: (roundApi.Round, BigDecimal, _)) =>
          (rw._1.number, -rw._2)
        )
      )
      .map(_._3)
    // Since taps in the batch increase the number of transfer inputs, we need to subtract them from the maxNumInputs limit.
    // (We could theoretically check/adjust the ordering of the batch such that if a non-tap operation is
    // before a tap-operation, we don't need to adjust the maxNumInput limit since the inputs would be merged
    // by the non-tap operation. However, the non-tap operation may fail in which case we would have again too
    // many inputs and thus we decided to always simply subtract the number of tap operations in a batch.)
    // In general, we refrain from merging more inputs in later steps in the batch to minimize code complexity.
    // We prioritize including a small amount of large coins in transfers, so we avoid unnecessary out-of-funds errors
    // for larger transfers.

    val numCoinsToPrioritize =
      Math
        .max(2, Math.max(coinInputs.length + sortedRewardInputs.length, maxNumInputs) * 0.1)
        .round
        .intValue
    val inputs =
      (coinInputs.take(numCoinsToPrioritize) ++ sortedRewardInputs ++ coinInputs.drop(
        numCoinsToPrioritize
      ))
        .take(maxNumInputs - numTapOperations)
    inputs
  }

  private def getValidatorRewardsAndQuantity(
      maxNumInputs: Int,
      issuingRoundsMap: Map[roundApi.Round, IssuingMiningRound],
  )(implicit
      tc: TraceContext
  ): Future[
    (BigDecimal, Set[PartyId], Seq[(roundApi.Round, BigDecimal, InputValidatorRewardCoupon)])
  ] = {
    for {
      validatorRewardCouponsRaw <- walletManager
        .listValidatorRewardCouponsCollectableBy(
          userStore,
          Some(maxNumInputs),
          Some(issuingRoundsMap.keySet.map(_.number)),
        )
      validatorRewardCouponUsers = validatorRewardCouponsRaw
        .map(c => PartyId.tryFromProtoPrimitive(c.payload.user))
        .toSet
      validatorRewardsWithCoinQuantity = validatorRewardCouponsRaw.flatMap(rw => {
        val issuingO = issuingRoundsMap.get(rw.payload.round)
        issuingO
          .map(i => {
            val quantity = rw.payload.amount.multiply(i.issuancePerValidatorRewardCoupon)
            (rw.payload.round, rw, BigDecimal(quantity))
          })
      })
      validatorRewardsCoinQuantity = validatorRewardsWithCoinQuantity.map(_._3).sum
      validatorRewardInputs = validatorRewardsWithCoinQuantity.map(rw =>
        (
          rw._2.payload.round,
          rw._3,
          new v1.coin.transferinput.InputValidatorRewardCoupon(
            rw._2.contractId.toInterface(v1.coin.ValidatorRewardCoupon.INTERFACE)
          ),
        )
      )
    } yield (validatorRewardsCoinQuantity, validatorRewardCouponUsers, validatorRewardInputs)
  }

  private def getAppRewardsAndQuantity(
      maxNumInputs: Int,
      issuingRoundsMap: Map[roundApi.Round, IssuingMiningRound],
  )(implicit
      tc: TraceContext
  ): Future[(BigDecimal, Seq[(roundApi.Round, BigDecimal, InputAppRewardCoupon)])] = {
    for {
      appRewardCouponInputs <- userStore.listSortedAppRewards(
        maxNumInputs,
        issuingRoundsMap,
      )
      appRewardsCoinQuantity = appRewardCouponInputs.map(_._2).sum
      appRewardInputs = appRewardCouponInputs.map(rw =>
        (
          rw._1.payload.round,
          rw._2,
          new v1.coin.transferinput.InputAppRewardCoupon(
            rw._1.contractId.toInterface(v1.coin.AppRewardCoupon.INTERFACE)
          ),
        )
      )
    } yield (appRewardsCoinQuantity, appRewardInputs)
  }
}

object TreasuryService {

  /** Helper class for the batches of coin operations executed by the treasury service.
    * Mainly introduced to handle to cleanly separate the logic around managing CO_MergeTransferInputs.
    *
    * @param mergeOperationOpt tracks the CO_MergeTransferInputs operation if there is one as part of the batch.
    *                          tracked separately because it doesn't make sense for there
    *                          to be multiple merge operations in a single batch.
    */
  private case class CoinOperationBatch(
      mergeOperationOpt: Option[EnqueuedCoinOperation],
      nonMergeOperations: Seq[EnqueuedCoinOperation],
  ) extends PrettyPrinting {
    override def pretty: Pretty[CoinOperationBatch.this.type] = prettyOfClass(
      paramIfDefined("mergeOperationOpt", _.mergeOperationOpt),
      paramIfNonEmpty("nonMergeOperations", _.nonMergeOperations),
    )

    lazy val numTapOperations: Int = nonMergeOperations.count(_.isCO_Tap)

    /** Computes the coin operations that should be run on the ledger given the current batch state. */
    lazy val operationsToRun: Seq[EnqueuedCoinOperation] = {
      // if the batch is only a merge-operation - run that - else use the nonMergeOperations and don't include the
      // mergeOperation
      if (isMergeOnly) mergeOperationOpt.toList else nonMergeOperations
    }

    lazy val isEmpty: Boolean = operationsToRun.isEmpty

    def isMergeOnly: Boolean = mergeOperationOpt.isDefined && nonMergeOperations.isEmpty

    def priority: CommandPriority = {
      if (
        mergeOperationOpt.isEmpty && nonMergeOperations.forall(_.priority == CommandPriority.High)
      ) CommandPriority.High
      else CommandPriority.Low
    }

    def addCOToBatch(operation: EnqueuedCoinOperation): CoinOperationBatch = {
      if (
        (mergeOperationOpt.isEmpty && nonMergeOperations.isEmpty) || priority == operation.priority
      ) {
        val isMergeOp = operation.isCO_MergeTransferInputs
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
      } else sys.error("Cannot mix operations of different priorities in batch")
    }

    def computeExecuteBatchCmd(
        install: Contract[WalletAppInstall.ContractId, WalletAppInstall],
        transferContext: PaymentTransferContext,
        inputs: Seq[TransferInput],
    ) =
      install.exercise(
        _.exerciseWalletAppInstall_ExecuteBatch(
          transferContext,
          inputs.asJava,
          operationsToRun.map(_.operation).asJava,
        )
      )

    def completeBatchOperations(
        outcomes: Exercised[ExecuteBatchResult]
    )(implicit logger: TracedLogger, tc: TraceContext) = {
      (outcomes.exerciseResult.outcomes.asScala zip operationsToRun).foreach { case (outcome, op) =>
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

  private case class EnqueuedCoinOperation(
      operation: installCodegen.CoinOperation,
      outcomePromise: Promise[installCodegen.CoinOperationOutcome],
      submittedFrom: TraceContext,
      priority: CommandPriority = CommandPriority.Low,
  ) extends PrettyPrinting {
    override def pretty: Pretty[EnqueuedCoinOperation.this.type] =
      prettyNode(
        "CoinOperation",
        param("from", _.submittedFrom.showTraceId),
        param("op", _.operation.toValue),
      )

    lazy val isCO_MergeTransferInputs: Boolean =
      operation match {
        case _: coinoperation.CO_MergeTransferInputs => true
        case _ => false
      }

    lazy val isCO_Tap: Boolean =
      operation match {
        case _: coinoperation.CO_Tap => true
        case _ => false
      }
  }
}
