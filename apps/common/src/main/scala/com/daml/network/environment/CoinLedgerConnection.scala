package com.daml.network.environment

import akka.actor.ActorSystem
import akka.stream.KillSwitches
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.{Done, NotUsed}
import com.daml.grpc.{GrpcException, GrpcStatus}
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.ledger.api.refinements.ApiTypes.{
  ApplicationId,
  TemplateId,
  TransactionId,
  WorkflowId,
}
import com.daml.ledger.api.v1.commands.Commands.DeduplicationPeriod
import com.daml.ledger.api.v1.commands.{Command, Commands}
import com.daml.ledger.api.v1.event.CreatedEvent
import com.daml.ledger.api.v1.ledger_offset.LedgerOffset
import com.daml.ledger.api.v1.transaction.{Transaction, TransactionTree}
import com.daml.ledger.api.v1.transaction_filter.{Filters, InclusiveFilters, TransactionFilter}
import com.daml.ledger.api.v1.value.Identifier
import com.daml.ledger.client.LedgerClient
import com.daml.ledger.client.binding.{Primitive => P}
import com.daml.ledger.client.configuration.{
  CommandClientConfiguration,
  LedgerClientChannelConfiguration,
  LedgerClientConfiguration,
  LedgerIdRequirement,
}
import com.digitalasset.canton.config.{ClientConfig, ProcessingTimeout}
import com.digitalasset.canton.lifecycle.{
  AsyncCloseable,
  AsyncOrSyncCloseable,
  FlagCloseableAsync,
  SyncCloseable,
}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging, TracedLogger}
import com.digitalasset.canton.networking.grpc.ClientChannelBuilder
import com.digitalasset.canton.time.NonNegativeFiniteDuration
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{TraceContext, TracerProvider}
import com.digitalasset.canton.util.{AkkaUtil, retry}
import com.google.rpc.status.Status
import io.grpc.StatusRuntimeException
import io.opentelemetry.instrumentation.grpc.v1_6.GrpcTracing
import org.slf4j.event.Level
import scalaz.syntax.tag._

import java.util.UUID
import com.daml.ledger.api.domain.UserRight.CanActAs
import com.daml.ledger.api.v1.command_service.{
  SubmitAndWaitForTransactionResponse,
  SubmitAndWaitRequest,
}
import com.daml.network.util.UploadablePackage
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.error.ErrorCodeUtils
import com.digitalasset.canton.util.retry.RetryUtil.NoExnRetryable.logThrowable
import com.digitalasset.canton.util.retry.RetryUtil.{
  ErrorKind,
  ExceptionRetryable,
  FatalErrorKind,
  NoErrorKind,
  NoExnRetryable,
  TransientErrorKind,
}
import com.google.protobuf.ByteString
import com.google.protobuf.empty.Empty

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success, Try}

/** Extract from connection for only submitting functionality */
trait CoinLedgerSubmit extends FlagCloseableAsync {
  def submitCommand(
      actAs: Seq[PartyId],
      readAs: Seq[PartyId],
      command: Seq[Command],
      commandId: Option[String] = None,
      workflowId: Option[WorkflowId] = None,
      deduplicationTime: Option[NonNegativeFiniteDuration] = None,
  )(implicit traceContext: TraceContext): Future[SubmitAndWaitForTransactionResponse]
}

/** Subscription for reading the ledger */
trait CoinLedgerSubscription extends FlagCloseableAsync with NamedLogging {
  val completed: Future[Done]
}

trait CoinLedgerConnection extends CoinLedgerSubmit {
  def ledgerEnd: Future[LedgerOffset]
  def activeContracts(
      filter: TransactionFilter
  ): Future[(Seq[CreatedEvent], LedgerOffset)]
  def subscribe(
      subscriptionName: String,
      offset: LedgerOffset,
      filter: TransactionFilter = TransactionFilter(),
  )(f: Transaction => Unit): CoinLedgerSubscription
  def subscribeAsync(
      subscriptionName: String,
      offset: LedgerOffset,
      filter: TransactionFilter,
  )(f: Transaction => Future[Unit]): CoinLedgerSubscription
  def subscription[T](
      subscriptionName: String,
      offset: LedgerOffset,
      filter: TransactionFilter,
  )(mapOperator: Flow[Transaction, Any, _]): CoinLedgerSubscription

  def subscribeTree(
      subscriptionName: String,
      offset: LedgerOffset,
      filter: Seq[PartyId],
  )(f: TransactionTree => Unit): CoinLedgerSubscription
  def subscribeAsyncTree(
      subscriptionName: String,
      offset: LedgerOffset,
      filter: Seq[PartyId],
  )(f: TransactionTree => Future[Unit]): CoinLedgerSubscription
  def subscriptionTree[T](
      subscriptionName: String,
      offset: LedgerOffset,
      filter: Seq[PartyId],
  )(mapOperator: Flow[TransactionTree, Any, _]): CoinLedgerSubscription

  def transactionById(parties: Seq[PartyId], id: String): Future[Option[Transaction]]

  def getUser(user: String): Future[Option[PartyId]]

  def createPartyAndUser(user: String): Future[PartyId]

  def getOrAllocateParty(
      username: String
  )(implicit traceContext: TraceContext): Future[PartyId]

  def uploadDarFile(pkg: UploadablePackage)(implicit traceContext: TraceContext): Future[Unit]

  def allocatePartyViaLedgerApi(hint: Option[String], displayName: Option[String]): Future[PartyId]

}

// Note: this is copied from the Canton LedgerConnection class
// Differences:
// - it uses the command submission client, and not the command client
// - it does not retry commands on timeout (that was implemented as an akka flow around the command client)
// - actAs/readAs parties are specified for each submission, instead of being static for the duration of the connection
// - there are new methods for interacting with the ledger API (e.g., party/package management)
object CoinLedgerConnection {
  def createLedgerClient(
      applicationId: ApplicationId,
      config: ClientConfig,
      commandClientConfiguration: CommandClientConfiguration,
      tracerProvider: TracerProvider,
      token: Option[String] = None,
  )(implicit
      ec: ExecutionContextExecutor,
      executionSequencerFactory: ExecutionSequencerFactory,
  ): Future[LedgerClient] = {
    val clientConfig = LedgerClientConfiguration(
      applicationId = ApplicationId.unwrap(applicationId),
      ledgerIdRequirement = LedgerIdRequirement(None),
      commandClient = commandClientConfiguration,
      token = token,
    )
    val clientChannelConfig = LedgerClientChannelConfiguration(
      sslContext = config.tls.map(x => ClientChannelBuilder.sslContext(x)),
      // Hard-coding the maximum value (= 2GB).
      // If a limit is needed, because an application can't handle transactions at that size,
      // the participants should agree on a lower limit and enforce that through domain parameters.
      maxInboundMessageSize = Int.MaxValue,
    )

    val builder = clientChannelConfig
      .builderFor(config.address, config.port.unwrap)
      .executor(ec)
      .intercept(
        GrpcTracing.builder(tracerProvider.openTelemetry).build().newClientInterceptor()
      )
    LedgerClient.fromBuilder(builder, clientConfig)
  }

  def apply(
      clientConfig: ClientConfig,
      applicationId: ApplicationId,
      maxRetries: Int,
      defaultWorkflowId: WorkflowId,
      commandClientConfiguration: CommandClientConfiguration,
      token: Option[String],
      processingTimeouts: ProcessingTimeout,
      loggerFactoryForCoinLedgerConnectionOverride: NamedLoggerFactory,
      tracerProvider: TracerProvider,
  )(implicit
      ec: ExecutionContextExecutor,
      as: ActorSystem,
      sequencerPool: ExecutionSequencerFactory,
  ): CoinLedgerConnection with NamedLogging =
    new CoinLedgerConnection with NamedLogging {
      protected val loggerFactory: NamedLoggerFactory = loggerFactoryForCoinLedgerConnectionOverride

      override protected def timeouts: ProcessingTimeout = processingTimeouts

      private val client = {
        import TraceContext.Implicits.Empty._
        processingTimeouts.unbounded.await(
          s"Creation of the ledger client",
          logFailing = Some(Level.WARN),
        )(
          createLedgerClient(
            applicationId,
            clientConfig,
            commandClientConfiguration,
            tracerProvider,
            token,
          )
        )
      }
      private val ledgerId = client.ledgerId

      private val transactionClient = client.transactionClient

      override def ledgerEnd: Future[LedgerOffset] =
        transactionClient.getLedgerEnd().flatMap(response => toFuture(response.offset))

      override def activeContracts(
          filter: TransactionFilter
      ): Future[(Seq[CreatedEvent], LedgerOffset)] = {
        val activeContractsRequest = client.activeContractSetClient.getActiveContracts(filter)
        activeContractsRequest.toMat(Sink.seq)(Keep.right).run().map { responseSequence =>
          val offset = responseSequence
            .map(_.offset)
            .lastOption
            // according to spec, should always be defined in last message of stream
            .getOrElse(
              throw new RuntimeException(
                "Expected to have offset in the last message of the acs stream but didn't have one!"
              )
            )
          val active = responseSequence.flatMap(_.activeContracts)
          (active, LedgerOffset(value = LedgerOffset.Value.Absolute(offset)))
        }
      }

      case object RetryOnRetryableLedgerApiError extends ExceptionRetryable {

        override def retryOK(outcome: Try[_], logger: TracedLogger)(implicit
            tc: TraceContext
        ): ErrorKind = outcome match {
          case Failure(ex @ GrpcException(GrpcStatus(_, Some(description)), _)) =>
            if (ErrorCodeUtils.errorCategoryFromString(description).exists(_.retryable.nonEmpty)) {
              logger.info(s"Ledger API call failed with a retryable error", ex)
              TransientErrorKind
            } else {
              logger.info(s"Ledger API call failed with a non-retryable error", ex)
              FatalErrorKind
            }
          case Failure(ex) =>
            logger.info(s"Ledger API call failed with an unknown exception, not retrying", ex)
            FatalErrorKind
          case util.Success(_) =>
            NoErrorKind
        }
      }

      override def submitCommand(
          actAs: Seq[PartyId],
          readAs: Seq[PartyId],
          command: Seq[Command],
          commandId: Option[String] = None,
          workflowId: Option[WorkflowId] = None,
          deduplicationTime: Option[NonNegativeFiniteDuration] = None,
      )(implicit traceContext: TraceContext): Future[SubmitAndWaitForTransactionResponse] = {
        // Note: reusing the same command id for all retries
        val fullCommand =
          commandsOf(
            actAs,
            readAs,
            commandId,
            workflowId.getOrElse(defaultWorkflowId),
            deduplicationTime,
            command,
          )

        implicit val success = com.digitalasset.canton.util.retry.Success.always
        retry
          .Backoff(logger, this, maxRetries, 10.millis, 5.second, "submitCommand")
          .apply(submitCommandOnce(fullCommand), RetryOnRetryableLedgerApiError)
      }

      private def submitCommandOnce(
          fullCommand: Commands
      )(implicit traceContext: TraceContext): Future[SubmitAndWaitForTransactionResponse] = {
        val commandIdA = fullCommand.commandId
        logger.debug(s"Submitting command [$commandIdA]")
        val result = client.commandServiceClient.submitAndWaitForTransaction(
          new SubmitAndWaitRequest(Some(fullCommand))
        )

        result onComplete { outcome =>
          outcome.fold(
            e => logger.error(s"Command [$commandIdA] failed due to an exception", e),
            response =>
              logger.debug(
                s"Command [$commandIdA] succeeded"
              ),
          )
        }
        result
      }

      def commandsOf(
          actAs: Seq[PartyId],
          readAs: Seq[PartyId],
          commandId: Option[String],
          workflowId: WorkflowId,
          deduplicationTime: Option[NonNegativeFiniteDuration],
          seq: Seq[Command],
      ): Commands =
        Commands(
          ledgerId = ledgerId.unwrap,
          workflowId = WorkflowId.unwrap(workflowId),
          applicationId = ApplicationId.unwrap(applicationId),
          commandId = commandId.getOrElse(uniqueId),
          actAs = actAs.map(_.toProtoPrimitive),
          readAs = readAs.map(_.toProtoPrimitive),
          deduplicationPeriod = deduplicationTime
            .map(dt => DeduplicationPeriod.DeduplicationDuration(dt.toProtoPrimitive))
            .getOrElse(DeduplicationPeriod.Empty),
          commands = seq,
        )

      override def subscribe(
          subscriptionName: String,
          offset: LedgerOffset,
          filter: TransactionFilter,
      )(f: Transaction => Unit): CoinLedgerSubscription =
        subscription(subscriptionName, offset, filter)({
          Flow[Transaction].map(f)
        })

      override def subscribeAsync(
          subscriptionName: String,
          offset: LedgerOffset,
          filter: TransactionFilter,
      )(f: Transaction => Future[Unit]): CoinLedgerSubscription =
        subscription(subscriptionName, offset, filter)({
          Flow[Transaction].mapAsync(1)(f)
        })

      override def transactionById(parties: Seq[PartyId], id: String): Future[Option[Transaction]] =
        client.transactionClient
          .getFlatTransactionById(id, parties.map(_.toProtoPrimitive), token)
          .map { resp =>
            resp.transaction
          }

      override def getUser(user: String): Future[Option[PartyId]] = {
        val userId = com.daml.lf.data.Ref.UserId.assertFromString(user)
        for {
          user <- client.userManagementClient
            .getUser(userId, token)
            .map(Some(_))
            .recover {
              case e: StatusRuntimeException
                  if e.getStatus.getCode == io.grpc.Status.Code.NOT_FOUND =>
                None
            }
          partyId = user.map(u =>
            PartyId.tryFromLfParty(
              u.primaryParty
                .getOrElse(sys.error(s"user $user was allocated without primary party"))
            )
          )
        } yield partyId
      }

      override def createPartyAndUser(user: String): Future[PartyId] = {
        for {
          party <- allocatePartyViaLedgerApi(Some(user), Some(user))
          userId = com.daml.lf.data.Ref.UserId.assertFromString(user)
          userLf = com.daml.ledger.api.domain.User(userId, Some(party.toLf))

          user <- client.userManagementClient
            .createUser(userLf, List(CanActAs(party.toLf)), token)
          partyId =
            PartyId.tryFromLfParty(
              user.primaryParty
                .getOrElse(sys.error(s"user $user was allocated without primary party"))
            )
        } yield partyId
      }

      // TODO(Robert): Factor out user/party allocation and make it robust (current implementation is racy)
      override def getOrAllocateParty(
          username: String
      )(implicit traceContext: TraceContext): Future[PartyId] = {
        for {
          existingPartyId <- getUser(username)
          partyId <- existingPartyId.fold[Future[PartyId]](createPartyAndUser(username))(
            Future.successful
          )
          // TODO: Is there some automatic logging of submitted commands already? If not, can we introduce it?
          _ = logger.info(s"User $username and party $partyId are allocated")
        } yield partyId
      }

      private def uploadDarFileInternal(packageId: String, darFile: => ByteString)(implicit
          traceContext: TraceContext
      ): Future[Unit] = {
        for {
          known <- client.packageManagementClient.listKnownPackages()
          _ <- {
            if (known.map(_.packageId).contains(packageId)) {
              logger.debug(s"Package of dar $packageId already exists")
              Future.successful(())
            } else {
              logger.debug(s"Uploading dar file ${packageId}")
              client.packageManagementClient.uploadDarFile(darFile, token)
            }
          }
        } yield ()
      }

      override def uploadDarFile(
          pkg: UploadablePackage
      )(implicit traceContext: TraceContext): Future[Unit] = {
        for {
          _ <- uploadDarFileInternal(
            pkg.packageId,
            ByteString.readFrom(pkg.inputStream()),
          )
          // TODO(M1-90): The ledger API does not block until the package is vetted.
          //  Need to wait a bit, or use the Canton admin API to upload the package (that one does block).
          _ = Threading.sleep(500)
          _ = logger.info(s"Package ${pkg.packageId} is uploaded")
        } yield ()
      }

      override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = List[AsyncOrSyncCloseable](
        SyncCloseable("ledgerClient", client.close())
      )

      override def subscription[T](
          subscriptionName: String,
          offset: LedgerOffset,
          filter: TransactionFilter,
      )(mapOperator: Flow[Transaction, Any, _]): CoinLedgerSubscription =
        makeSubscription(
          transactionClient.getTransactions(offset, None, filter),
          mapOperator,
          subscriptionName,
        )

      override def subscribeTree(
          subscriptionName: String,
          offset: LedgerOffset,
          filterParty: Seq[PartyId],
      )(f: TransactionTree => Unit): CoinLedgerSubscription =
        subscriptionTree(subscriptionName, offset, filterParty)({
          Flow[TransactionTree].map(f)
        })

      override def subscribeAsyncTree(
          subscriptionName: String,
          offset: LedgerOffset,
          filterParty: Seq[PartyId],
      )(f: TransactionTree => Future[Unit]): CoinLedgerSubscription =
        subscriptionTree(subscriptionName, offset, filterParty)({
          Flow[TransactionTree].mapAsync(1)(f)
        })

      override def subscriptionTree[T](
          subscriptionName: String,
          offset: LedgerOffset,
          filterParty: Seq[PartyId],
      )(mapOperator: Flow[TransactionTree, Any, _]): CoinLedgerSubscription =
        makeSubscription(
          transactionClient.getTransactionTrees(offset, None, transactionFilter(filterParty: _*)),
          mapOperator,
          subscriptionName,
        )

      private def makeSubscription[S, T](
          source: Source[S, NotUsed],
          mapOperator: Flow[S, T, _],
          subscriptionName: String,
      ): CoinLedgerSubscription =
        new CoinLedgerSubscription {
          override protected def timeouts: ProcessingTimeout = processingTimeouts
          import TraceContext.Implicits.Empty._
          val (killSwitch, completed) = AkkaUtil.runSupervised(
            logger.error("Fatally failed to handle transaction", _),
            source
              // we place the kill switch before the map operator, such that
              // we can shut down the operator quickly and signal upstream to cancel further sending
              .viaMat(KillSwitches.single)(Keep.right)
              .viaMat(mapOperator)(Keep.left)
              // and we get the Future[Done] as completed from the sink so we know when the last message
              // was processed
              .toMat(Sink.ignore)(Keep.both),
          )
          override val loggerFactory =
            if (subscriptionName.isEmpty)
              loggerFactoryForCoinLedgerConnectionOverride
            else
              loggerFactoryForCoinLedgerConnectionOverride.appendUnnamedKey(
                "subscription",
                subscriptionName,
              )

          override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = {
            import TraceContext.Implicits.Empty._
            List[AsyncOrSyncCloseable](
              SyncCloseable(s"killSwitch.shutdown $subscriptionName", killSwitch.shutdown()),
              AsyncCloseable(
                s"graph.completed $subscriptionName",
                completed.transform {
                  case Success(v) => Success(v)
                  case Failure(ex: StatusRuntimeException) =>
                    // don't fail to close if there was a grpc status runtime exception
                    // this can happen (i.e. server not available etc.)
                    Success(Done)
                  case Failure(ex) => Failure(ex)
                },
                processingTimeouts.shutdownShort.unwrap,
              ),
            )
          }
        }

      override def allocatePartyViaLedgerApi(
          hint: Option[String],
          displayName: Option[String],
      ): Future[PartyId] =
        client.partyManagementClient.allocateParty(hint, displayName, token).map { details =>
          PartyId.tryFromLfParty(details.party)
        }
    }

  def transactionFilter(ps: PartyId*): TransactionFilter =
    TransactionFilter(ps.map(p => p.toProtoPrimitive -> Filters.defaultInstance).toMap)

  def transactionFilter(partyId: PartyId, template: P.TemplateId[_]): TransactionFilter =
    TransactionFilter(
      Map(
        partyId.toProtoPrimitive -> Filters(
          Some(InclusiveFilters(templateIds = Seq(TemplateId.unwrap(template))))
        )
      )
    )

  def transactionFilterByParty(filter: Map[PartyId, Seq[TemplateId]]): TransactionFilter =
    TransactionFilter(filter.map {
      case (p, Nil) => p.toPrim.unwrap -> Filters.defaultInstance
      case (p, ts) => p.toPrim.unwrap -> Filters(Some(InclusiveFilters(ts.map(_.unwrap))))
    })

  def mapTemplateIds(id: P.TemplateId[_]): TemplateId = {
    import scalaz.syntax.tag._
    id.unwrap match {
      case Identifier(packageId, moduleName, entityName) =>
        TemplateId(
          Identifier(packageId = packageId, moduleName = moduleName, entityName = entityName)
        )
    }
  }

  val ledgerBegin = LedgerOffset(
    LedgerOffset.Value.Boundary(LedgerOffset.LedgerBoundary.LEDGER_BEGIN)
  )

  def uniqueId: String = UUID.randomUUID.toString

  def toFuture[A](o: Option[A]): Future[A] =
    o.fold(Future.failed[A](new IllegalStateException(s"Empty option: $o")))(a =>
      Future.successful(a)
    )
}
