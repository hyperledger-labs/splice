package com.daml.network.environment

import akka.actor.ActorSystem
import com.daml.ledger.api.domain.UserRight
import com.daml.ledger.api.refinements.ApiTypes.WorkflowId
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
}
