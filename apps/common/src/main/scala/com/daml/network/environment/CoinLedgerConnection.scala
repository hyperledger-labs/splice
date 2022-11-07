package com.daml.network.environment

import akka.actor.ActorSystem
import com.daml.ledger.api.domain.UserRight
import com.daml.ledger.api.refinements.ApiTypes.{ContractId, WorkflowId}
import com.daml.ledger.api.v1.transaction.{TransactionTree, TreeEvent}
import com.daml.ledger.client.binding.{Value => CodegenValue, ValueDecoder}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{TraceContext, TracerProvider}

import scala.concurrent.{ExecutionContextExecutor, Future}

// TODO(#1498) Remove in favor of JavaCoinLedgerConnection.
trait CoinLedgerConnection {
  def getUserReadAs(username: String): Future[Set[PartyId]]

  def listPackages()(implicit traceContext: TraceContext): Future[Set[String]]
}

// Note: this is copied from the Canton LedgerConnection class
// Differences:
// - the management of the ledger client is factored out to [[CoinLedgerClient]], such that multiple workflows
//   can share the same physical connection
// - it uses the command submission client, and not the command client
// - it does not retry commands on timeout (that was implemented as an akka flow around the command client)
// - actAs/readAs parties are specified for each submission, instead of being static for the duration of the connection
// - there are new methods for interacting with the ledger API (e.g., party/package management)
object CoinLedgerConnection {

  def apply(
      coinLedgerClient: CoinLedgerClient,
      maxRetries: Int,
      workflowId: WorkflowId,
      loggerFactoryForCoinLedgerConnectionOverride: NamedLoggerFactory,
      tracerProvider: TracerProvider,
  ): CoinLedgerConnection with NamedLogging =
    new CoinLedgerConnection with NamedLogging {
      protected val loggerFactory: NamedLoggerFactory = loggerFactoryForCoinLedgerConnectionOverride

      private def client = coinLedgerClient.client
      private def ledgerId = coinLedgerClient.ledgerId
      private def transactionClient = coinLedgerClient.transactionClient
      implicit private def ec: ExecutionContextExecutor = coinLedgerClient.executionContextExecutor
      implicit private def as: ActorSystem = coinLedgerClient.actorSystem

      override def getUserReadAs(
          username: String
      ): Future[Set[PartyId]] = {
        val userId = com.daml.lf.data.Ref.UserId.assertFromString(username)
        for {
          userRights <- client.userManagementClient.listUserRights(userId)
        } yield userRights.collect { case UserRight.CanReadAs(p) =>
          PartyId.tryFromLfParty(p)
        }.toSet
      }

      override def listPackages()(implicit traceContext: TraceContext): Future[Set[String]] =
        client.packageClient.listPackages().map(_.packageIds.toSet)
    }

  def toFuture[A](o: Option[A]): Future[A] =
    o.fold(Future.failed[A](new IllegalStateException(s"Empty option: $o")))(a =>
      Future.successful(a)
    )

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def decodeExerciseResult[T](
      cmdDescription: String,
      transaction: TransactionTree,
  )(implicit decoder: ValueDecoder[T]): T = {
    if (transaction.rootEventIds.size == 1) {
      val event = transaction.eventsById(transaction.rootEventIds(0))
      event.kind match {
        case TreeEvent.Kind.Created(created) =>
          // We don’t have enough information here to check that T is a contract id.
          // We could try to commit some crimes using Scala reflection & TypeTag
          // but in the end this cast seems much simpler and the Scala codegen
          // makes Update internal so we can rely on people not making up garbage
          // Update values.
          ContractId(created.contractId).asInstanceOf[T]
        case TreeEvent.Kind.Exercised(exercised) =>
          CodegenValue
            .decode[T](exercised.getExerciseResult)
            .getOrElse(
              throw new IllegalArgumentException(
                s"Executing [$cmdDescription] produced result [$exercised] of unexpected type."
              )
            )
        case TreeEvent.Kind.Empty =>
          throw new IllegalArgumentException(s"Unknown tree event kind")
      }
    } else {
      throw new IllegalArgumentException(
        s"Expected exactly one root event id but got ${transaction.rootEventIds.size}"
      )
    }
  }
}
