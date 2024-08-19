// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.platform.apiserver.services.command

import cats.syntax.either.*
import cats.syntax.traverse.*
import com.daml.error.ContextualizedErrorLogger
import com.daml.ledger.api.v2.interactive_submission_service.*
import com.daml.scalautil.future.FutureConversion.*
import com.daml.timer.Delayed
import com.digitalasset.canton.crypto.LedgerApiCryptoConversions.*
import com.digitalasset.canton.crypto.{Hash, HashAlgorithm, HashPurpose}
import com.digitalasset.canton.ledger.api.domain.{Commands as ApiCommands, SubmissionId}
import com.digitalasset.canton.ledger.api.services.InteractiveSubmissionService
import com.digitalasset.canton.ledger.api.services.InteractiveSubmissionService.PrepareRequest as PrepareRequestInternal
import com.digitalasset.canton.ledger.api.util.TimeProvider
import com.digitalasset.canton.ledger.configuration.LedgerTimeModel
import com.digitalasset.canton.ledger.participant.state
import com.digitalasset.canton.logging.LoggingContextWithTrace.*
import com.digitalasset.canton.logging.{
  ErrorLoggingContext,
  LoggingContextWithTrace,
  NamedLoggerFactory,
  NamedLogging,
}
import com.digitalasset.canton.metrics.LedgerApiServerMetrics
import com.digitalasset.canton.platform.apiserver.SeedService
import com.digitalasset.canton.platform.apiserver.execution.{
  CommandExecutionResult,
  CommandExecutor,
}
import com.digitalasset.canton.platform.apiserver.services.command.InteractiveSubmissionServiceImpl.PendingRequest
import com.digitalasset.canton.platform.apiserver.services.{
  ErrorCause,
  RejectionGenerators,
  TimeProviderType,
  logging,
}
import com.digitalasset.canton.platform.config.InteractiveSubmissionServiceConfig
import com.digitalasset.canton.protocol.v30
import com.digitalasset.canton.sequencing.protocol.PartySignatures as CantonPartySignatures
import com.digitalasset.canton.tracing.Spanning
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.daml.lf.command.ApiCommand
import com.digitalasset.daml.lf.crypto
import com.github.benmanes.caffeine.cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.google.protobuf.ByteString
import io.opentelemetry.api.trace.Tracer
import io.scalaland.chimney.dsl.*
import monocle.macros.syntax.lens.*

import java.time.Duration
import scala.concurrent.{ExecutionContext, Future}

private[apiserver] object InteractiveSubmissionServiceImpl {

  private final case class PendingRequest(
      executionResult: CommandExecutionResult,
      commands: ApiCommands,
  )

  def createApiService(
      writeService: state.WriteService,
      timeProvider: TimeProvider,
      timeProviderType: TimeProviderType,
      seedService: SeedService,
      commandExecutor: CommandExecutor,
      metrics: LedgerApiServerMetrics,
      interactiveSubmissionServiceConfig: InteractiveSubmissionServiceConfig,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      executionContext: ExecutionContext,
      tracer: Tracer,
  ): InteractiveSubmissionService & AutoCloseable = new InteractiveSubmissionServiceImpl(
    writeService,
    timeProvider,
    timeProviderType,
    seedService,
    commandExecutor,
    metrics,
    interactiveSubmissionServiceConfig,
    loggerFactory,
  )

}

private[apiserver] final class InteractiveSubmissionServiceImpl private[services] (
    writeService: state.WriteService,
    timeProvider: TimeProvider,
    timeProviderType: TimeProviderType,
    seedService: SeedService,
    commandExecutor: CommandExecutor,
    metrics: LedgerApiServerMetrics,
    interactiveSubmissionServiceConfig: InteractiveSubmissionServiceConfig,
    val loggerFactory: NamedLoggerFactory,
)(implicit executionContext: ExecutionContext, tracer: Tracer)
    extends InteractiveSubmissionService
    with AutoCloseable
    with Spanning
    with NamedLogging {

  // TODO(i20660): This is temporary while we settle on a proper serialization format for the prepared transaction
  // For now for simplicity we keep the required data in-memory to be able to submit the transaction when
  // it is submitted with external signatures. Obviously this restricts usage of the prepare / submit
  // flow to using the same node, and does not survive restarts of the node.
  private val pendingPrepareRequests: cache.Cache[ByteString, PendingRequest] = Caffeine
    .newBuilder()
    // Max duration to keep the prepared transaction in memory after it's been prepared
    .expireAfterWrite(interactiveSubmissionServiceConfig.prepareCacheTTL.asJava)
    // We only read from it when the transaction is submitted, after which we don't need to keep it around
    // so expire it almost immediately after access
    .expireAfterAccess(Duration.ofSeconds(1))
    .build[ByteString, PendingRequest]()

  override def prepare(
      request: PrepareRequestInternal
  )(implicit
      loggingContext: LoggingContextWithTrace
  ): Future[PrepareSubmissionResponse] =
    withEnrichedLoggingContext(logging.commands(request.commands)) { implicit loggingContext =>
      logger.info(
        s"Requesting preparation of daml transaction with command ID ${request.commands.commandId}"
      )
      val cmds = request.commands.commands.commands
      // TODO(i20726): make sure this does not leak information
      logger.debug(
        show"Submitted commands for prepare are: ${if (cmds.length > 1) "\n  " else ""}${cmds
            .map {
              case ApiCommand.Create(templateRef, _) =>
                s"create ${templateRef.qName}"
              case ApiCommand.Exercise(templateRef, _, choiceId, _) =>
                s"exercise @${templateRef.qName} $choiceId"
              case ApiCommand.ExerciseByKey(templateRef, _, choiceId, _) =>
                s"exerciseByKey @${templateRef.qName} $choiceId"
              case ApiCommand.CreateAndExercise(templateRef, _, choiceId, _) =>
                s"createAndExercise ${templateRef.qName} ... $choiceId ..."
            }
            .map(_.singleQuoted)
            .toSeq
            .mkString("\n  ")}"
      )

      implicit val errorLoggingContext: ContextualizedErrorLogger =
        ErrorLoggingContext.fromOption(
          logger,
          loggingContext,
          request.commands.submissionId.map(SubmissionId.unwrap),
        )

      evaluateAndHash(seedService.nextSeed(), request.commands)
    }

  private def handleCommandExecutionResult(
      result: Either[ErrorCause, CommandExecutionResult]
  )(implicit contextualizedErrorLogger: ContextualizedErrorLogger): Future[CommandExecutionResult] =
    result.fold(
      error => {
        metrics.commands.failedCommandInterpretations.mark()
        failedOnCommandExecution(error)
      },
      Future.successful,
    )

  // TODO(i20660): Until serialization and hashing are figured out, use the command Id as transaction and hash
  private def computeTransactionHash(transaction: ByteString) =
    Hash
      .digest(
        HashPurpose.SignedLedgerApiCommand,
        transaction,
        HashAlgorithm.Sha256,
      )
      .getCryptographicEvidence

  private def evaluateAndHash(
      submissionSeed: crypto.Hash,
      commands: ApiCommands,
  )(implicit
      loggingContext: LoggingContextWithTrace,
      errorLoggingContext: ContextualizedErrorLogger,
  ): Future[PrepareSubmissionResponse] =
    for {
      result <- withSpan("ApiSubmissionService.evaluate") { _ => _ =>
        commandExecutor.execute(commands, submissionSeed)
      }
      transactionInfo <- handleCommandExecutionResult(result)
      // TODO(i20660): Until serialization and hashing are figured out, use the command Id as transaction and hash
      transactionByteString = ByteString.copyFromUtf8(commands.commandId.toString)
      transactionHash = computeTransactionHash(transactionByteString)
      // Caffeine doesn't have putIfAbsent. Use `get` with a function to insert the value in the cache to obtain the same result
      _ = pendingPrepareRequests.get(
        transactionHash,
        _ => PendingRequest(transactionInfo, commands),
      )
    } yield PrepareSubmissionResponse(
      preparedTransaction = transactionByteString,
      preparedTransactionHash = transactionHash,
    )

  private def failedOnCommandExecution(
      error: ErrorCause
  )(implicit contextualizedErrorLogger: ContextualizedErrorLogger): Future[CommandExecutionResult] =
    Future.failed(
      RejectionGenerators
        .commandExecutorError(error)
        .asGrpcError
    )

  override def close(): Unit = ()

  private def submitTransactionWithDelay(
      transactionInfo: CommandExecutionResult
  )(implicit
      loggingContext: LoggingContextWithTrace
  ): Future[state.SubmissionResult] =
    timeProviderType match {
      case TimeProviderType.WallClock =>
        // Submit transactions such that they arrive at the ledger sequencer exactly when record time equals ledger time.
        // If the ledger time of the transaction is far in the future (farther than the expected latency),
        // the submission to the WriteService is delayed.
        val submitAt = transactionInfo.transactionMeta.ledgerEffectiveTime.toInstant
          .minus(LedgerTimeModel.maximumToleranceTimeModel.avgTransactionLatency)
        val submissionDelay = Duration.between(timeProvider.getCurrentTime, submitAt)
        if (submissionDelay.isNegative)
          submitTransaction(transactionInfo)
        else {
          logger.info(s"Delaying submission by $submissionDelay")
          metrics.commands.delayedSubmissions.mark()
          val scalaDelay = scala.concurrent.duration.Duration.fromNanos(submissionDelay.toNanos)
          Delayed.Future.by(scalaDelay)(submitTransaction(transactionInfo))
        }
      case TimeProviderType.Static =>
        // In static time mode, record time is always equal to ledger time
        submitTransaction(transactionInfo)
    }

  private def submitTransaction(
      result: CommandExecutionResult
  )(implicit
      loggingContext: LoggingContextWithTrace
  ): Future[state.SubmissionResult] = {
    metrics.commands.validSubmissions.mark()
    logger.trace("Submitting transaction to ledger.")
    writeService
      .submitTransaction(
        result.submitterInfo,
        result.optDomainId,
        result.transactionMeta,
        result.transaction,
        result.interpretationTimeNanos,
        result.globalKeyMapping,
        result.processedDisclosedContracts,
      )
      .toScalaUnwrapped
  }

  override def execute(
      request: ExecuteSubmissionRequest
  )(implicit loggingContext: LoggingContextWithTrace): Future[ExecuteSubmissionResponse] =
    for {
      pending <- Option(
        pendingPrepareRequests.getIfPresent(computeTransactionHash(request.preparedTransaction))
      )
        .map(Future.successful)
        .getOrElse(
          Future.failed(
            io.grpc.Status.NOT_FOUND.withDescription("Unknown command").asRuntimeException()
          )
        )
      partySignatures <- Future.fromTry(
        request.transactionSignatures.traverse { issSignature =>
          CantonPartySignatures
            .fromProtoV30(
              // Convert the LAPI proto to the Canton protocol proto
              // before parsing it to the internal data type CantonPartySignatures
              issSignature.transformInto[v30.PartySignatures]
            )
            .leftMap(err =>
              io.grpc.Status.INVALID_ARGUMENT
                .withDescription(s"Invalid signature argument: $err")
                .asRuntimeException()
            )
            .toTry
        }
      )
      transactionWithPartySignatures = pending
        .focus(_.executionResult.submitterInfo.partySignatures)
        .replace(partySignatures)
      _ <- submitTransactionWithDelay(transactionWithPartySignatures.executionResult)
      submissionId <- pending.commands.submissionId
        .map(_.toString)
        .map(Future.successful)
        .getOrElse(
          Future.failed(
            io.grpc.Status.INTERNAL.withDescription("No submission id").asRuntimeException()
          )
        )
    } yield ExecuteSubmissionResponse(
      Seq(pending.commands.commandId.toString),
      submissionId,
    )
}
