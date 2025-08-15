// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.treasury

import com.daml.ledger.javaapi.data.codegen.{Exercised, Update}
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet as amuletCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.ValidatorRight
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.transferinput.{
  ExtTransferInput,
  InputAmulet,
  InputAppRewardCoupon,
  InputSvRewardCoupon,
  InputUnclaimedActivityRecord,
  InputValidatorLivenessActivityRecord,
  InputValidatorRewardCoupon,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.{
  PaymentTransferContext,
  TransferContext,
  TransferInput,
  TransferPreapproval,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.IssuingMiningRound
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.install.amuletoperationoutcome.COO_MergeTransferInputs
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.install.{
  WalletAppInstall,
  WalletAppInstall_ExecuteBatchResult,
  amuletoperation,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.{
  buytrafficrequest as trafficRequestCodegen,
  install as installCodegen,
  payment as walletCodegen,
  subscriptions as subsCodegen,
  transferoffer as transferOffersCodegen,
}
import org.lfdecentralizedtrust.splice.environment.{
  CommandPriority,
  RetryProvider,
  SpliceLedgerConnection,
}
import SpliceLedgerConnection.CommandId
import com.daml.ledger.api.v2.CommandsOuterClass
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.transferpreapproval.TransferPreapprovalProposal
import org.lfdecentralizedtrust.splice.environment.ledger.api.DedupConfig
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection
import org.lfdecentralizedtrust.splice.store.PageLimit
import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
import org.lfdecentralizedtrust.splice.util.{
  AssignedContract,
  DisclosedContracts,
  HasHealth,
  SpliceUtil,
  TokenStandardMetadata,
}
import org.lfdecentralizedtrust.splice.wallet.UserWalletManager
import org.lfdecentralizedtrust.splice.wallet.config.TreasuryConfig
import org.lfdecentralizedtrust.splice.wallet.store.UserWalletStore
import org.lfdecentralizedtrust.splice.wallet.treasury.TreasuryService.*
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.{
  AsyncCloseable,
  AsyncOrSyncCloseable,
  FlagCloseableAsync,
  RunOnClosing,
}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.logging.{
  ErrorLoggingContext,
  NamedLoggerFactory,
  NamedLogging,
  TracedLogger,
}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status
import org.apache.pekko.Done
import org.apache.pekko.stream.QueueOfferResult.{Dropped, Enqueued, QueueClosed}
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source}
import org.apache.pekko.stream.{BoundedSourceQueue, Materializer, QueueOfferResult}
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.{
  allocationinstructionv1,
  allocationv1,
  holdingv1,
  metadatav1,
  transferinstructionv1,
}

import java.time.Instant
import java.util.Optional
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.{Failure, Success}

/** This class encapsulates the logic that sequences all operations which change the amulet holdings of a user such
  * that concurrent manipulations don't conflict.
  *
  * For the design, please see https://github.com/DACH-NY/canton-network-node/issues/913
  */
class TreasuryService(
    connection: SpliceLedgerConnection,
    treasuryConfig: TreasuryConfig,
    clock: Clock,
    userStore: UserWalletStore,
    walletManager: UserWalletManager,
    override protected[this] val retryProvider: RetryProvider,
    scanConnection: BftScanConnection,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext, mat: Materializer)
    extends NamedLogging
    with FlagCloseableAsync
    with RetryProvider.Has
    with Spanning
    with HasHealth {

  private implicit val elc: ErrorLoggingContext =
    ErrorLoggingContext(logger, Map.empty, TraceContext.empty)

  private val queueTerminationResult: Promise[Done] = Promise()

  // Setting the weight > batch size ensures they go in a batch of their own
  private val BatchWithOneOperation = treasuryConfig.batchSize.toLong + 1L

  private val queue: BoundedSourceQueue[EnqueuedOperation] = {
    val queue = Source
      .queue[EnqueuedOperation](treasuryConfig.queueSize)
      .batchWeighted[OperationBatch](
        treasuryConfig.batchSize.toLong,
        {
          case amuletOp: EnqueuedAmuletOperation =>
            if (amuletOp.priority == CommandPriority.High || amuletOp.dedup.isDefined) {
              BatchWithOneOperation
            } else 1L
          case _: EnqueuedTokenStandardTransferOperation =>
            BatchWithOneOperation
          case _: EnqueuedAmuletAllocationOperation =>
            BatchWithOneOperation
        },
        {
          case amuletOp: EnqueuedAmuletOperation =>
            AmuletOperationBatch(amuletOp)
          case tsOp: EnqueuedTokenStandardTransferOperation =>
            TokenStandardOperationBatch(tsOp)
          case allOp: EnqueuedAmuletAllocationOperation =>
            AmuletAllocationOperationBatch(allOp)
        },
      ) {
        case (batch: AmuletOperationBatch, operation: EnqueuedAmuletOperation) =>
          batch.addCOToBatch(operation)
        case (_: TokenStandardOperationBatch, _: EnqueuedTokenStandardTransferOperation) =>
          throw new IllegalStateException(
            "Token standard batches cannot contain more than one element. This is a bug."
          )
        case (batch, operation) =>
          throw new IllegalStateException(
            s"Batch is ${batch.getClass.getName} while operation is ${operation.getClass.getName}. This is a bug."
          )
      }
      // Execute the batches sequentially to avoid contention
      .mapAsync(1) {
        case amuletBatch: AmuletOperationBatch => filterAndExecuteBatch(amuletBatch)
        case TokenStandardOperationBatch(operation) =>
          executeTokenStandardTransferOperation(operation)
        case AmuletAllocationOperationBatch(operation) =>
          executeAmuletAllocationOperation(operation)
      }
      .toMat(
        Sink.onComplete(result0 => {
          val result =
            retryProvider
              .logTerminationAndRecoverOnShutdown("amulet operation batch executor", logger)(
                result0
              )(TraceContext.empty)
          val _ = queueTerminationResult.tryComplete(result)
        })
      )(Keep.left)
      .run()
    logger.debug(
      show"Started amulet operation operation batch executor with ${treasuryConfig.toString.unquoted}"
    )(TraceContext.empty)
    queue
  }

  retryProvider.runOnOrAfterClose_(new RunOnClosing {
    override def name: String = s"terminate amulet operation batch executor"
    override def done: Boolean = queueTerminationResult.isCompleted
    override def run()(implicit tc: TraceContext): Unit = {
      logger.debug("Terminating amulet operation batch executor, as we are shutting down.")(
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
        "waiting for amulet operation batch executor shutdown",
        queueTerminationResult.future,
        timeouts.shutdownShort,
      )
    )

  override def isHealthy: Boolean = !queueTerminationResult.isCompleted

  // Overriding, as this method is used in "FlagCloseable" to pretty-print the object being closed.
  override def toString: String =
    show"TreasureService(endUserParty=${userStore.key.endUserParty})"

  /** Enqueues a amulet operation into an internal task queue.
    * The [[TreasuryService]] will schedule the operation and then complete the returned with its result.
    */
  def enqueueAmuletOperation[T](
      operation: installCodegen.AmuletOperation,
      priority: CommandPriority = CommandPriority.Low,
      dedup: Option[AmuletOperationDedupConfig] = None,
      extraDisclosedContracts: DisclosedContracts = DisclosedContracts.Empty,
  )(implicit tc: TraceContext): Future[installCodegen.AmuletOperationOutcome] = {
    val p = Promise[installCodegen.AmuletOperationOutcome]()
    enqueue(EnqueuedAmuletOperation(operation, p, tc, priority, dedup, extraDisclosedContracts))
  }

  def enqueueTokenStandardTransferOperation(
      receiverPartyId: PartyId,
      amount: BigDecimal,
      description: String,
      expiresAt: CantonTimestamp,
      dedup: Option[AmuletOperationDedupConfig],
  )(implicit tc: TraceContext): Future[transferinstructionv1.TransferInstructionResult] = {
    val p = Promise[transferinstructionv1.TransferInstructionResult]()
    enqueue(
      EnqueuedTokenStandardTransferOperation(
        receiverPartyId,
        amount,
        description,
        expiresAt,
        p,
        tc,
        dedup,
      )
    )
  }

  def enqueueAmuletAllocationOperation(
      specification: allocationv1.AllocationSpecification,
      requestedAt: Instant,
      dedup: Option[AmuletOperationDedupConfig],
  )(implicit tc: TraceContext): Future[allocationinstructionv1.AllocationInstructionResult] = {
    val p = Promise[allocationinstructionv1.AllocationInstructionResult]()
    enqueue(EnqueuedAmuletAllocationOperation(specification, requestedAt, p, tc, dedup))
  }

  private def enqueue(
      operation: EnqueuedOperation
  )(implicit tc: TraceContext): Future[operation.Result] = {
    logger.debug(
      show"Received operation (queue size before adding this: ${queue.size()}): $operation"
    )
    queue.offer(
      operation
    ) match {
      case Enqueued =>
        logger.debug(show"Operation $operation enqueued successfully")
        operation.outcomePromise.future
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

  private def closingException(operation: EnqueuedOperation) =
    Status.UNAVAILABLE
      .withDescription(
        show"Rejected operation because the amulet operation batch executor is shutting down: $operation"
      )
      .asRuntimeException

  // Try looking up the contracts relevant for identifying operation staleness. Will throw a [[io.grpc.StatusRuntimeException]] if not found.
  private def tryLookupAmuletOperation(
      op0: installCodegen.AmuletOperation
  )(implicit tc: TraceContext): Future[Unit] =
    op0 match {
      case op: amuletoperation.CO_SubscriptionAcceptAndMakeInitialPayment =>
        for {
          _ <- userStore.multiDomainAcsStore.getContractById(
            subsCodegen.SubscriptionRequest.COMPANION
          )(op.contractIdValue)
        } yield ()

      case op: amuletoperation.CO_SubscriptionMakePayment =>
        for {
          _ <- userStore.multiDomainAcsStore.getContractById(
            subsCodegen.SubscriptionIdleState.COMPANION
          )(op.contractIdValue)
        } yield ()

      case op: amuletoperation.CO_AppPayment =>
        for {
          _ <- userStore.multiDomainAcsStore.getContractById(
            walletCodegen.AppPaymentRequest.COMPANION
          )(op.contractIdValue)
        } yield ()

      case op: amuletoperation.CO_CompleteAcceptedTransfer =>
        for {
          _ <- userStore.multiDomainAcsStore.getContractById(
            transferOffersCodegen.AcceptedTransferOffer.COMPANION
          )(op.contractIdValue)
        } yield ()

      case _: amuletoperation.CO_MergeTransferInputs => Future.unit

      case _: amuletoperation.CO_Tap => Future.unit

      // TODO(tech-debt): Ideally, we should modify the BuyMemberTraffic choice to also return
      //  the ValidatorTopUpState contract Id in the COOutcome and ingest these into the store
      //  in order to do the staleness check here. BuyMemberTraffic txs are always placed into
      //  their own batch so a failure of this tx should not impact other amulet txs.
      case _: amuletoperation.CO_BuyMemberTraffic => Future.unit

      case op: amuletoperation.CO_CompleteBuyTrafficRequest =>
        for {
          _ <- userStore.multiDomainAcsStore.getContractById(
            trafficRequestCodegen.BuyTrafficRequest.COMPANION
          )(op.trafficRequestCid)
        } yield ()

      case _: amuletoperation.CO_CreateExternalPartySetupProposal => Future.unit

      case op: amuletoperation.CO_AcceptTransferPreapprovalProposal =>
        for {
          _ <- userStore.multiDomainAcsStore.getContractById(TransferPreapprovalProposal.COMPANION)(
            op.preapprovalProposalCid
          )
        } yield ()

      case op: amuletoperation.CO_RenewTransferPreapproval =>
        for {
          _ <- userStore.multiDomainAcsStore.getContractById(TransferPreapproval.COMPANION)(
            op.previousApprovalCid
          )
        } yield ()

      case _: amuletoperation.CO_TransferPreapprovalSend => Future.unit

      case op => throw new NotImplementedError(show"Unexpected amulet operation: $op")
    }

  private def isErrorOutcome(outcome: installCodegen.AmuletOperationOutcome): Boolean =
    outcome match {
      case _: installCodegen.amuletoperationoutcome.COO_Error => true
      case _ => false
    }

  // Checks an operation for staleness. If it is stale - completes it with a failure, and returns None.
  // Otherwise, returns the operation.
  private def completeIfStale(
      op: EnqueuedAmuletOperation
  )(implicit tc: TraceContext): Future[Option[EnqueuedAmuletOperation]] =
    for {
      res <- tryLookupAmuletOperation(op.operation).transform {
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
      unfilteredBatch: AmuletOperationBatch
  )(implicit tc: TraceContext): Future[AmuletOperationBatch] =
    for {
      filteredNonMergeOperations <- Future.traverse(unfilteredBatch.nonMergeOperations)(
        completeIfStale(_)
      )

    } yield AmuletOperationBatch(
      unfilteredBatch.mergeOperationOpt,
      filteredNonMergeOperations
        .filter(_.isDefined)
        .map(_.getOrElse(throw new RuntimeException("Unexpected None value"))),
      unfilteredBatch.dedup,
    )

  private def filterAndExecuteBatch(
      unfilteredBatch: AmuletOperationBatch
  ): Future[Done] = TraceContext.withNewTraceContext("executeBatch")(implicit tc => {
    for {
      filteredBatch <- filterBatch(unfilteredBatch)
      res <- executeBatch(filteredBatch)
    } yield res
  })

  private def executeBatch(
      batch: AmuletOperationBatch
  )(implicit tc: TraceContext): Future[Done] = {
    // Remove all operations from the batch whose promise has already been completed.
    // We use this approach as the retry infrastructure retries a fixed operation, and we want to avoid
    // introducing more mutable state that can go awry.
    // We accept the cost of repeatedly filtering the batch, as batches are expected to be small.
    logger.debug(
      show"Running batch of amulet operations:\n$batch"
    )

    def completeWithFailure(
        op: EnqueuedAmuletOperation,
        throwable: Throwable,
    ) = {
      op.outcomePromise.complete(
        Failure(throwable)
      )
    }

    if (batch.isEmpty) {
      logger.debug("Amulet operation batch was empty after filtering. ")
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
            // (2) there is nothing to merge (e.g. only 1 amulet + no rewards already, or rewards are too small)
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
      install: AssignedContract[WalletAppInstall.ContractId, WalletAppInstall],
      transferContext: PaymentTransferContext,
      inputs: Seq[TransferInput],
      batch: AmuletOperationBatch,
      readAs: Set[PartyId],
      disclosedContracts: DisclosedContracts.NE,
  )(implicit tc: TraceContext): Future[Done] = {
    val cmd = batch.computeExecuteBatchCmd(install, transferContext, inputs)
    logger.debug(s"executing batch $batch with inputs $inputs")
    val baseSubmission = connection
      .submit(
        Seq(walletManager.store.walletKey.validatorParty),
        userStore.key.endUserParty +: readAs.toSeq,
        cmd,
        priority = batch.priority,
        deadline = treasuryConfig.grpcDeadline,
      )
      .withDisclosedContracts(disclosedContracts.merge(batch.extraDisclosedContracts))
    for {
      (offset, result) <- batch.dedup match {
        case None => baseSubmission.noDedup.yieldResultAndOffset()
        case Some(dedup) =>
          baseSubmission.withDedup(dedup.commandId, dedup.config).yieldResultAndOffset()
      }

      // wait for store to ingest the new amulet holdings, then return all outcomes to the callers
      _ <- waitForAmuletBatchIngestion(offset, result).map(_ =>
        batch.completeBatchOperations(result)(logger, tc)
      )
    } yield Done
  }

  private def executeTokenStandardTransferOperation(
      operation: EnqueuedTokenStandardTransferOperation
  ): Future[Done] = {
    TraceContext.withNewTraceContext("executeTokenStandardTransferOperation")(implicit tc => {
      val now = clock.now.toInstant
      logger.debug(s"Executing token standard operation $operation")
      val sender = userStore.key.endUserParty
      val dso = userStore.key.dsoParty.toProtoPrimitive
      exerciseTokenStandardChoice(operation) { holdings =>
        val choiceArgs = new transferinstructionv1.TransferFactory_Transfer(
          dso,
          new transferinstructionv1.Transfer(
            sender.toProtoPrimitive,
            operation.receiverPartyId.toProtoPrimitive,
            operation.amount.bigDecimal,
            new holdingv1.InstrumentId(dso, "Amulet"),
            now,
            operation.expiresAt.toInstant,
            holdings,
            new metadatav1.Metadata(
              java.util.Map.of(TokenStandardMetadata.reasonMetaKey, operation.description)
            ),
          ),
          emptyExtraArgs,
        )
        scanConnection.getTransferFactory(choiceArgs).map { case (transferFactory, _) =>
          transferFactory.factoryId
            .exerciseTransferFactory_Transfer(
              transferFactory.args
            ) -> transferFactory.disclosedContracts
        }
      }

    })
  }

  private def executeAmuletAllocationOperation(operation: EnqueuedAmuletAllocationOperation) = {
    TraceContext.withNewTraceContext("executeAmuletAllocationOperation")(implicit tc => {
      logger.debug(s"Executing Amulet Allocation operation $operation")
      exerciseTokenStandardChoice(operation) { holdings =>
        val choiceArgs = new allocationinstructionv1.AllocationFactory_Allocate(
          userStore.key.dsoParty.toProtoPrimitive,
          operation.specification,
          operation.requestedAt,
          holdings,
          emptyExtraArgs,
        )
        scanConnection.getAllocationFactory(choiceArgs).map { allocationFactory =>
          allocationFactory.factoryId.exerciseAllocationFactory_Allocate(
            allocationFactory.args
          ) -> allocationFactory.disclosedContracts
        }
      }
    })
  }

  private def exerciseTokenStandardChoice(operation: EnqueuedOperation)(
      exerciseFromHoldings: java.util.List[holdingv1.Holding.ContractId] => Future[
        (Update[Exercised[operation.Result]], Seq[CommandsOuterClass.DisclosedContract])
      ]
  )(implicit tc: TraceContext) = {
    val now = clock.now.toInstant
    (for {
      holdings <- getHoldings(now)
      (exercise, disclosedContracts) <- exerciseFromHoldings(holdings)
      synchronizerId <- scanConnection.getAmuletRulesDomain()(tc)
      baseSubmission = connection
        .submit(
          Seq(userStore.key.endUserParty),
          Seq(userStore.key.endUserParty),
          exercise,
          CommandPriority.Low,
          treasuryConfig.grpcDeadline,
        )
        .withSynchronizerId(
          synchronizerId,
          DisclosedContracts.fromProto(disclosedContracts),
        )
      (offset, result) <- operation.dedup match {
        case None => baseSubmission.noDedup.yieldResultAndOffset()
        case Some(dedup) =>
          baseSubmission.withDedup(dedup.commandId, dedup.config).yieldResultAndOffset()
      }
      _ <- userStore.signalWhenIngestedOrShutdown(offset)
    } yield {
      operation.outcomePromise.success(result.exerciseResult)
      Done
    }).recover { case ex =>
      logger.info(s"Token standard operation failed.", ex)
      operation.outcomePromise.failure(ex)
      Done
    }
  }

  private def getHoldings(now: Instant)(implicit tc: TraceContext) = {
    for {
      amulets <- userStore.multiDomainAcsStore.listContracts(amuletCodegen.Amulet.COMPANION)
      lockedAmulets <- userStore.multiDomainAcsStore.listContracts(
        amuletCodegen.LockedAmulet.COMPANION
      )
      expiredLockedAmulets = lockedAmulets.filter(_.payload.lock.expiresAt.isBefore(now))
    } yield (amulets ++ expiredLockedAmulets)
      .map(holding => new holdingv1.Holding.ContractId(holding.contractId.contractId))
      .asJava
  }

  private def waitForAmuletBatchIngestion(
      offset: Long,
      outcomes: Exercised[WalletAppInstall_ExecuteBatchResult],
  )(implicit tc: TraceContext): Future[Unit] =
    if (outcomes.exerciseResult.outcomes.asScala.forall(isErrorOutcome)) {
      // We must not wait in this case, as the store won't see that offset until the next action comes,
      // as the transaction filter is in the way
      // TODO(tech-debt): remove this fragility of depending on the exact daml transaction to determine whether to wait or not
      Future.unit
    } else {
      logger.debug(show"Waiting for store to ingest offset ${offset}")
      userStore.signalWhenIngestedOrShutdown(offset)
    }

  private def shouldMergeOnlyTransferRun(
      totalRewardsQuantity: BigDecimal,
      amuletInputsAndQuantity: Seq[(BigDecimal, InputAmulet)],
      createFeeCc: BigDecimal,
  )(implicit tc: TraceContext): Boolean = {
    val numAmuletInputs = amuletInputsAndQuantity.length
    val totalAmuletQuantity = amuletInputsAndQuantity.map(_._1).sum
    if (numAmuletInputs <= 1) {
      val run = totalRewardsQuantity > createFeeCc
      // only log when there are actually some rewards to possibly collect.
      if (!run && totalRewardsQuantity != 0) {
        // Log at info level to avoid surprises wrt rewards not accumulating.
        logger.info(
          "Not executing a merge operation because there no amulets to merge " +
            s"and the totalRewardsQuantity $totalRewardsQuantity is smaller than the create-fee $createFeeCc"
        )
      }
      run
    } else {
      val totalQuantity = totalRewardsQuantity + totalAmuletQuantity
      val run = totalQuantity > createFeeCc
      if (!run && totalQuantity != 0)
        logger.debug(
          "Not executing a merge operation because " +
            s"the total rewards and amulet quantity ${totalQuantity} is smaller than the create-fee $createFeeCc"
        )
      run
    }
  }

  //          002| 2024-06-03T15:43:38.124Z [⋮] DEBUG - c.d.n.a.a.HttpRequestLogger:WalletManualRoundsIntegrationTest/config=5ad173a8/validator=aliceValidator (5b962e505ea5140afe2bd833f8c88bb3---21693c15d5eb9f93) - HTTP GET /api/validator/v0/wallet/balance from (127.0.0.1:35514): Responding with entity data: {"round":9,"effective_unlocked_qty":"","effective_locked_qty":"0.0000000000","total_holding_fees":""}
  // 0.0076103600 + 10.6633746670
  // 10.6633746670

  private def getAmuletRules()(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ) =
    for {
      rules <- scanConnection.getAmuletRulesWithState()
    } yield (rules, rules.contractId)

  /** Select transfer inputs and transfer context to satisfy the amulet operations.
    * Currently, this function selects all unlocked amulets and all currently redeemable app- and validator rewards.
    * It checks that if the amulet-operation batch only consists of a merge operation, it makes
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
        Seq[splice.amuletrules.TransferInput],
        Set[PartyId],
        splice.amuletrules.PaymentTransferContext,
        DisclosedContracts.NE,
    )
  ]] = {
    for {
      (disclosedAmuletRules, amuletRulesInterface) <- getAmuletRules()
      (openRounds, issuingMiningRounds) <- scanConnection.getOpenAndIssuingMiningRounds()
      openRound = SpliceUtil.selectLatestOpenMiningRound(now, openRounds)
      amuletPrice = openRound.payload.amuletPrice
      configUsd = openRound.payload.transferConfigUsd
      maxNumInputs = configUsd.maxNumInputs.intValue()
      openIssuingRounds = issuingMiningRounds.filter(c => c.payload.opensAt.isBefore(now.toInstant))
      issuingRoundsMap = openIssuingRounds.view.map { r =>
        val imr = r.payload
        (imr.round, imr)
      }.toMap
      contractsToDisclose = connection.disclosedContracts(
        disclosedAmuletRules,
        openRound,
      ) addAll openIssuingRounds
      validatorRights <- walletManager.store.multiDomainAcsStore
        .listContracts(amuletCodegen.ValidatorRight.COMPANION)
      amuletInputsAndQuantity <- userStore.listSortedAmuletsAndQuantity(
        openRound.payload.round.number,
        PageLimit.tryCreate(maxNumInputs),
      )
      (validatorRewardsAmuletQuantity, validatorRewardCouponUsers, validatorRewardInputs) <-
        getValidatorRewardsAndQuantity(
          // We select the rewards to collect them through a transfer.
          // Those rewards are then an input to a transfer.
          // So we can't use more rewards than maxNumInputs.
          // If there are more, we'll just collect maxNumInputs first and once that completes collect the remainder.
          validatorRewardCouponsLimit = maxNumInputs,
          issuingRoundsMap,
        )
      (validatorFaucetsAmuletQuantity, validatorFaucetInputs) <- getValidatorFaucetsAndQuantity(
        maxNumInputs,
        issuingRoundsMap,
      )
      (validatorLivenessActivityRecordsAmuletQuantity, validatorActivityRecordsInputs) <-
        getValidatorLivenessActivityRecordsAndQuantity(
          maxNumInputs,
          issuingRoundsMap,
        )
      (appRewardsTotalAmuletQuantity, appRewardInputs) <- getAppRewardsAndQuantity(
        maxNumInputs,
        issuingRoundsMap,
      )
      (svRewardsTotalAmuletQuantity, svRewardInputs) <- getSvRewardCouponsAndQuantity(
        maxNumInputs,
        issuingRoundsMap,
      )
      (unclaimedActivityRecordsQuantity, unclaimedActivityRecordInputs) <-
        getUnclaimedActivityRecordsAndQuantity(
          maxNumInputs
        )
    } yield {
      val createFeeCc = SpliceUtil.dollarsToCC(configUsd.createFee.fee, amuletPrice)
      if (
        isMergeOny && !shouldMergeOnlyTransferRun(
          appRewardsTotalAmuletQuantity + validatorRewardsAmuletQuantity + validatorFaucetsAmuletQuantity +
            validatorLivenessActivityRecordsAmuletQuantity + svRewardsTotalAmuletQuantity +
            unclaimedActivityRecordsQuantity,
          amuletInputsAndQuantity,
          createFeeCc,
        )
      ) {
        None
      } else {
        val inputs = constructTransferInputs(
          maxNumInputs,
          amuletInputsAndQuantity.map(_._2),
          validatorRewardInputs,
          appRewardInputs,
          validatorFaucetInputs,
          validatorActivityRecordsInputs,
          svRewardInputs,
          unclaimedActivityRecordInputs,
          numTapOperations,
        )
        val rewardInputRounds =
          appRewardInputs.map(_._1).toSet ++ validatorRewardInputs
            .map(_._1)
            .toSet ++ validatorFaucetInputs.map(_._1).toSet ++ validatorActivityRecordsInputs
            .map(_._1)
            .toSet ++ svRewardInputs.map(_._1).toSet
        val transferContext = new TransferContext(
          openRound.contractId,
          openIssuingRounds.view
            // only provide rounds that are actually used in transfer context to avoid unnecessary fetching.
            .filter(r => rewardInputRounds.contains(r.payload.round))
            .map(r =>
              (
                r.payload.round,
                r.contractId,
              )
            )
            .toMap[Round, IssuingMiningRound.ContractId]
            .asJava,
          validatorRights
            // only provide validator rights that are actually used in transfer context to avoid unnecessary fetching.
            .filter(r =>
              validatorRewardCouponUsers.contains(PartyId.tryFromProtoPrimitive(r.payload.user))
            )
            .map(r => (r.payload.user, r.contractId))
            .toMap[String, ValidatorRight.ContractId]
            .asJava,
          // The wallet app is not a featured app ==> not featured app right in the transfer contexts used for its workflow steps.
          None.toJava,
        )
        Some(
          (
            inputs,
            validatorRewardCouponUsers,
            new PaymentTransferContext(
              amuletRulesInterface,
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
      amuletInputs: Seq[InputAmulet],
      validatorRewardInputs: Seq[(Round, BigDecimal, InputValidatorRewardCoupon)],
      appRewardInputs: Seq[(Round, BigDecimal, InputAppRewardCoupon)],
      validatorFaucetInputs: Seq[(Round, BigDecimal, ExtTransferInput)],
      validatorActivityRecordsInputs: Seq[
        (Round, BigDecimal, InputValidatorLivenessActivityRecord)
      ],
      svRewardCouponInputs: Seq[(Round, BigDecimal, InputSvRewardCoupon)],
      unclaimedActivityRecordInputs: Seq[(BigDecimal, InputUnclaimedActivityRecord)],
      numTapOperations: Int,
  ): Seq[TransferInput] = {
    val sortedRewardInputs =
      (validatorRewardInputs ++ appRewardInputs ++ validatorFaucetInputs ++ validatorActivityRecordsInputs ++ svRewardCouponInputs)
        .sorted(
          // prioritize the soonest-to-expire, most-valuable rewards.
          Ordering[(Long, BigDecimal)].on((rw: (Round, BigDecimal, _)) => (rw._1.number, -rw._2))
        )
        .map(_._3)
    // Since taps in the batch increase the number of transfer inputs, we need to subtract them from the maxNumInputs limit.
    // (We could theoretically check/adjust the ordering of the batch such that if a non-tap operation is
    // before a tap-operation, we don't need to adjust the maxNumInput limit since the inputs would be merged
    // by the non-tap operation. However, the non-tap operation may fail in which case we would have again too
    // many inputs and thus we decided to always simply subtract the number of tap operations in a batch.)
    // In general, we refrain from merging more inputs in later steps in the batch to minimize code complexity.
    // We prioritize including a small amount of large amulets in transfers, so we avoid unnecessary out-of-funds errors
    // for larger transfers.

    val numAmuletsToPrioritize =
      Math
        .max(2, Math.max(amuletInputs.length + sortedRewardInputs.length, maxNumInputs) * 0.1)
        .round
        .intValue
    val inputs =
      (amuletInputs.take(numAmuletsToPrioritize) ++ sortedRewardInputs ++ amuletInputs.drop(
        numAmuletsToPrioritize
      ) ++ unclaimedActivityRecordInputs.map(_._2))
        .take(maxNumInputs - numTapOperations)
    inputs
  }

  private def getValidatorRewardsAndQuantity(
      validatorRewardCouponsLimit: Int,
      issuingRoundsMap: Map[Round, IssuingMiningRound],
  )(implicit
      tc: TraceContext
  ): Future[
    (BigDecimal, Set[PartyId], Seq[(Round, BigDecimal, InputValidatorRewardCoupon)])
  ] = {
    for {
      validatorRewardCoupons <- walletManager
        .listValidatorRewardCouponsCollectableBy(
          userStore,
          limit = PageLimit.tryCreate(validatorRewardCouponsLimit),
          Some(issuingRoundsMap.keySet.map(_.number)),
        )
      validatorRewardCouponUsers = validatorRewardCoupons
        .map(c => PartyId.tryFromProtoPrimitive(c.payload.user))
        .toSet
      validatorRewardsWithAmuletQuantity = validatorRewardCoupons.flatMap(rw => {
        val issuingO = issuingRoundsMap.get(rw.payload.round)
        issuingO
          .map(i => {
            val quantity = rw.payload.amount.multiply(i.issuancePerValidatorRewardCoupon)
            (rw.payload.round, rw, BigDecimal(quantity))
          })
      })
      validatorRewardsAmuletQuantity = validatorRewardsWithAmuletQuantity.map(_._3).sum
      validatorRewardInputs = validatorRewardsWithAmuletQuantity.map(rw =>
        (
          rw._2.payload.round,
          rw._3,
          new splice.amuletrules.transferinput.InputValidatorRewardCoupon(
            rw._2.contractId
          ),
        )
      )
    } yield (validatorRewardsAmuletQuantity, validatorRewardCouponUsers, validatorRewardInputs)
  }

  private def getValidatorFaucetsAndQuantity(
      maxNumInputs: Int,
      issuingRoundsMap: Map[Round, IssuingMiningRound],
  )(implicit
      tc: TraceContext
  ): Future[(BigDecimal, Seq[(Round, BigDecimal, ExtTransferInput)])] = {
    for {
      validatorFaucetCouponsInputs <- userStore.listSortedValidatorFaucets(
        issuingRoundsMap,
        PageLimit.tryCreate(maxNumInputs),
      )
      validatorFaucetsAmuletQuantity = validatorFaucetCouponsInputs.map(_._2).sum
      validatorFaucetsInputs = validatorFaucetCouponsInputs.map(rw =>
        (
          rw._1.payload.round,
          rw._2,
          new splice.amuletrules.transferinput.ExtTransferInput(
            com.daml.ledger.javaapi.data.Unit.getInstance(),
            Optional.of(rw._1.contractId),
          ),
        )
      )
    } yield (validatorFaucetsAmuletQuantity, validatorFaucetsInputs)
  }

  private def getValidatorLivenessActivityRecordsAndQuantity(
      maxNumInputs: Int,
      issuingRoundsMap: Map[Round, IssuingMiningRound],
  )(implicit
      tc: TraceContext
  ): Future[(BigDecimal, Seq[(Round, BigDecimal, InputValidatorLivenessActivityRecord)])] = {
    for {
      validatorLivenessActivityRecordsInputs <- userStore.listSortedLivenessActivityRecords(
        issuingRoundsMap,
        PageLimit.tryCreate(maxNumInputs),
      )
      validatorLivenessActivityRecordsAmuletQuantity = validatorLivenessActivityRecordsInputs
        .map(_._2)
        .sum
      validatorActivityRecordsInputs = validatorLivenessActivityRecordsInputs.map(rw =>
        (
          rw._1.payload.round,
          rw._2,
          new splice.amuletrules.transferinput.InputValidatorLivenessActivityRecord(
            rw._1.contractId
          ),
        )
      )
    } yield (validatorLivenessActivityRecordsAmuletQuantity, validatorActivityRecordsInputs)
  }

  private def getSvRewardCouponsAndQuantity(
      maxNumInputs: Int,
      issuingRoundsMap: Map[Round, IssuingMiningRound],
  )(implicit
      tc: TraceContext
  ): Future[(BigDecimal, Seq[(Round, BigDecimal, InputSvRewardCoupon)])] = {
    for {
      svRewardCouponsInputs <- userStore.listSortedSvRewardCoupons(
        issuingRoundsMap,
        PageLimit.tryCreate(maxNumInputs),
      )
      svRewardCouponsQuantity = svRewardCouponsInputs.map(_._2).sum
      svRewardCouponInputs = svRewardCouponsInputs.map(rw =>
        (
          rw._1.payload.round,
          rw._2,
          new splice.amuletrules.transferinput.InputSvRewardCoupon(rw._1.contractId),
        )
      )
    } yield (svRewardCouponsQuantity, svRewardCouponInputs)
  }

  private def getAppRewardsAndQuantity(
      maxNumInputs: Int,
      issuingRoundsMap: Map[Round, IssuingMiningRound],
  )(implicit
      tc: TraceContext
  ): Future[(BigDecimal, Seq[(Round, BigDecimal, InputAppRewardCoupon)])] =
    for {
      appRewardCouponInputs <- userStore.listSortedAppRewards(
        issuingRoundsMap,
        PageLimit.tryCreate(maxNumInputs),
      )
      appRewardsAmuletQuantity = appRewardCouponInputs.map(_._2).sum
      appRewardInputs = appRewardCouponInputs.map(rw =>
        (
          rw._1.payload.round,
          rw._2,
          new splice.amuletrules.transferinput.InputAppRewardCoupon(
            rw._1.contractId
          ),
        )
      )
    } yield (appRewardsAmuletQuantity, appRewardInputs)

  private def getUnclaimedActivityRecordsAndQuantity(
      maxNumInputs: Int
  )(implicit
      tc: TraceContext
  ): Future[(BigDecimal, Seq[(BigDecimal, InputUnclaimedActivityRecord)])] =
    for {
      unclaimedActivityRecordsInputs <- userStore.listUnclaimedActivityRecords(
        PageLimit.tryCreate(maxNumInputs)
      )
      unclaimedActivityRecordsQuantity = unclaimedActivityRecordsInputs
        .map(uar => scala.math.BigDecimal(uar.payload.amount))
        .sum
      unclaimedActivityRecordInputs = unclaimedActivityRecordsInputs.map(uar =>
        (
          new scala.math.BigDecimal(uar.payload.amount),
          new splice.amuletrules.transferinput.InputUnclaimedActivityRecord(
            uar.contractId
          ),
        )
      )
    } yield (unclaimedActivityRecordsQuantity, unclaimedActivityRecordInputs)

}

object TreasuryService {

  private sealed trait OperationBatch

  /** Helper class for the batches of amulet operations executed by the treasury service.
    * Mainly introduced to handle to cleanly separate the logic around managing CO_MergeTransferInputs.
    *
    * @param mergeOperationOpt tracks the CO_MergeTransferInputs operation if there is one as part of the batch.
    *                          tracked separately because it doesn't make sense for there
    *                          to be multiple merge operations in a single batch.
    */
  private case class AmuletOperationBatch(
      mergeOperationOpt: Option[EnqueuedAmuletOperation],
      nonMergeOperations: Seq[EnqueuedAmuletOperation],
      dedup: Option[AmuletOperationDedupConfig],
  ) extends OperationBatch
      with PrettyPrinting {
    require(
      !(dedup.isDefined && (mergeOperationOpt.toList.size + nonMergeOperations.size) > 1),
      "Operations requiring dedup are in their own batch",
    )

    override def pretty: Pretty[AmuletOperationBatch.this.type] = prettyOfClass(
      paramIfDefined("mergeOperationOpt", _.mergeOperationOpt),
      paramIfNonEmpty("nonMergeOperations", _.nonMergeOperations),
      param("priority", _.priority),
    )

    lazy val numTapOperations: Int = nonMergeOperations.count(_.isCO_Tap)

    /** Computes the amulet operations that should be run on the ledger given the current batch state. */
    lazy val operationsToRun: Seq[EnqueuedAmuletOperation] = {
      // if the batch is only a merge-operation - run that - else use the nonMergeOperations and don't include the
      // mergeOperation
      if (isMergeOnly) mergeOperationOpt.toList else nonMergeOperations
    }

    lazy val isEmpty: Boolean = operationsToRun.isEmpty

    def isMergeOnly: Boolean = mergeOperationOpt.isDefined && nonMergeOperations.isEmpty

    def priority: CommandPriority = {
      val allOperations = nonMergeOperations ++ mergeOperationOpt.toList
      if (allOperations.nonEmpty && allOperations.forall(_.priority == CommandPriority.High))
        CommandPriority.High
      else CommandPriority.Low
    }

    def addCOToBatch(operation: EnqueuedAmuletOperation): AmuletOperationBatch = {
      if (
        (mergeOperationOpt.isEmpty && nonMergeOperations.isEmpty) || priority == operation.priority
      ) {
        if (dedup.isDefined) {
          throw new IllegalArgumentException(
            s"Batch specifies dedup config, cannot contain more than one element"
          )
        }
        if (
          operation.dedup.isDefined && (mergeOperationOpt.isDefined || !nonMergeOperations.isEmpty)
        ) {
          throw new IllegalArgumentException(
            s"Operation specifies dedup config, must be in a batch on its own"
          )
        }
        val isMergeOp = operation.isCO_MergeTransferInputs
        mergeOperationOpt match {
          case None if isMergeOp =>
            AmuletOperationBatch(Some(operation), nonMergeOperations, operation.dedup)
          case Some(_) if isMergeOp =>
            // if we already have a merge operation in this batch; complete the new one immediately and
            // don't add it to the batch
            operation.outcomePromise.success(new COO_MergeTransferInputs(None.toJava))
            this
          case _ =>
            AmuletOperationBatch(
              mergeOperationOpt,
              nonMergeOperations :+ operation,
              operation.dedup,
            )
        }
      } else sys.error("Cannot mix operations of different priorities in batch")
    }

    def computeExecuteBatchCmd(
        install: AssignedContract[WalletAppInstall.ContractId, WalletAppInstall],
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
        outcomes: Exercised[WalletAppInstall_ExecuteBatchResult]
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

    lazy val extraDisclosedContracts: DisclosedContracts =
      operationsToRun.foldLeft[DisclosedContracts](DisclosedContracts.Empty) {
        case (acc, operation) => acc.merge(operation.extraDisclosedContracts)
      }
  }

  private object AmuletOperationBatch {
    def apply(operation: EnqueuedAmuletOperation): AmuletOperationBatch = {
      AmuletOperationBatch(None, Seq.empty, None).addCOToBatch(operation)
    }
  }

  // Only one item per batch supported
  private case class TokenStandardOperationBatch(operation: EnqueuedTokenStandardTransferOperation)
      extends OperationBatch
      with PrettyPrinting {
    override def pretty: Pretty[TokenStandardOperationBatch.this.type] = prettyOfClass(
      param("operation", _.operation)
    )
  }

  // Only one item per batch supported
  private case class AmuletAllocationOperationBatch(operation: EnqueuedAmuletAllocationOperation)
      extends OperationBatch
      with PrettyPrinting {
    override def pretty: Pretty[AmuletAllocationOperationBatch.this.type] = prettyOfClass(
      param("operation", _.operation)
    )
  }

  private sealed trait EnqueuedOperation extends PrettyPrinting {
    type Result
    val outcomePromise: Promise[Result]
    val dedup: Option[AmuletOperationDedupConfig]
  }

  private case class EnqueuedTokenStandardTransferOperation(
      receiverPartyId: PartyId,
      amount: BigDecimal,
      description: String,
      expiresAt: CantonTimestamp,
      outcomePromise: Promise[transferinstructionv1.TransferInstructionResult],
      submittedFrom: TraceContext,
      dedup: Option[AmuletOperationDedupConfig],
  ) extends EnqueuedOperation {
    override type Result = transferinstructionv1.TransferInstructionResult

    override protected def pretty: Pretty[EnqueuedTokenStandardTransferOperation.this.type] =
      prettyNode(
        "TokenStandardTransferOperation",
        param("from", _.submittedFrom.showTraceId),
        param("receiver", _.receiverPartyId),
        param("amount", _.amount),
        param("expiresAt", _.expiresAt),
        param("dedup", _.dedup),
      )
  }

  private case class EnqueuedAmuletAllocationOperation(
      specification: allocationv1.AllocationSpecification,
      requestedAt: Instant,
      outcomePromise: Promise[allocationinstructionv1.AllocationInstructionResult],
      submittedFrom: TraceContext,
      dedup: Option[AmuletOperationDedupConfig],
  ) extends EnqueuedOperation {
    override type Result = allocationinstructionv1.AllocationInstructionResult

    override def pretty: Pretty[EnqueuedAmuletAllocationOperation.this.type] =
      prettyNode(
        "AmuletAllocationOperation",
        param("specification", _.specification),
      )
  }

  private case class EnqueuedAmuletOperation(
      operation: installCodegen.AmuletOperation,
      outcomePromise: Promise[installCodegen.AmuletOperationOutcome],
      submittedFrom: TraceContext,
      priority: CommandPriority,
      dedup: Option[AmuletOperationDedupConfig],
      extraDisclosedContracts: DisclosedContracts,
  ) extends EnqueuedOperation {
    override type Result = installCodegen.AmuletOperationOutcome

    override def pretty: Pretty[EnqueuedAmuletOperation.this.type] =
      prettyNode(
        "AmuletOperation",
        param("from", _.submittedFrom.showTraceId),
        param("op", _.operation.toValue),
        param("priority", _.priority),
        paramIfDefined("dedup", _.dedup),
      )

    lazy val isCO_MergeTransferInputs: Boolean =
      operation match {
        case _: amuletoperation.CO_MergeTransferInputs => true
        case _ => false
      }

    lazy val isCO_Tap: Boolean =
      operation match {
        case _: amuletoperation.CO_Tap => true
        case _ => false
      }
  }

  final case class AmuletOperationDedupConfig(
      commandId: CommandId,
      config: DedupConfig,
  ) extends PrettyPrinting {
    override def pretty: Pretty[AmuletOperationDedupConfig.this.type] =
      prettyNode("DedupConfig", param("commandId", _.commandId), param("config", _.config))
  }

  val emptyExtraArgs = new metadatav1.ExtraArgs(
    new metadatav1.ChoiceContext(java.util.Map.of()),
    new metadatav1.Metadata(java.util.Map.of()),
  )
}
