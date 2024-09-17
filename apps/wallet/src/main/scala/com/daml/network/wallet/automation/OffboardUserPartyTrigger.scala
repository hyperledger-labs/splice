// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.wallet.automation

import com.daml.network.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.environment.SpliceLedgerConnection
import com.daml.network.wallet.UserWalletManager
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import cats.syntax.traverseFilter.*

import scala.concurrent.{ExecutionContext, Future}

/** A trigger that shuts down the wallet automation for a party once no more WalletAppInstalls remain for it. */
class OffboardUserPartyTrigger(
    override protected val context: TriggerContext,
    walletManager: UserWalletManager,
    connection: SpliceLedgerConnection,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[PartyId] {

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[PartyId]] = {
    walletManager.listEndUserParties
      .filterA(userParty => walletManager.store.lookupInstallByParty(userParty).map(_.isEmpty))
  }

  override protected def completeTask(userParty: PartyId)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    walletManager.offboardUserParty(userParty)
    for {
      _ <- connection.revokeUserRights(
        walletManager.validatorUser,
        Seq(userParty),
        Seq(userParty),
      )
    } yield TaskSuccess(s"offboarded wallet for user party ${userParty}")
  }

  override protected def isStaleTask(userParty: PartyId)(implicit
      tc: TraceContext
  ): Future[Boolean] = {
    Future.successful(walletManager.lookupEndUserPartyWallet(userParty).isEmpty)
  }
}
