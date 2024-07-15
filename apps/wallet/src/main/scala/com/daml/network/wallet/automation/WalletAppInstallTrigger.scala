// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.wallet.automation

import com.daml.network.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.splice.wallet.install as installCodegen
import com.daml.network.environment.{SpliceLedgerConnection, RetryFor}
import com.daml.network.util.AssignedContract
import com.daml.network.wallet.UserWalletManager
import com.digitalasset.canton.lifecycle.UnlessShutdown
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}

class WalletAppInstallTrigger(
    override protected val context: TriggerContext,
    walletManager: UserWalletManager,
    connection: SpliceLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      installCodegen.WalletAppInstall.ContractId,
      installCodegen.WalletAppInstall,
    ](
      walletManager.store,
      installCodegen.WalletAppInstall.COMPANION,
    ) {

  override def completeTask(
      install: AssignedContract[
        installCodegen.WalletAppInstall.ContractId,
        installCodegen.WalletAppInstall,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val endUserParty = PartyId.tryFromProtoPrimitive(install.payload.endUserParty)
    for {
      // actAs rights for the validator user might have gone lost during a hard migration or a disaster recovery
      _ <- ensureActAsRight(endUserParty)
    } yield walletManager.getOrCreateUserWallet(install.contract) match {
      case UnlessShutdown.AbortedDueToShutdown =>
        TaskSuccess(
          s"skipped or aborted starting wallet automation for end-user party '$endUserParty', as we are shutting down."
        )
      case UnlessShutdown.Outcome(true) =>
        TaskSuccess(show"started wallet automation for end-user party $endUserParty")
      case UnlessShutdown.Outcome(false) =>
        TaskSuccess(
          show"skipped starting wallet automation for end-user party '$endUserParty', as it is already running."
        )
    }
  }

  private def ensureActAsRight(endUserParty: PartyId)(implicit tc: TraceContext): Future[Unit] = {
    val validatorUser = walletManager.validatorUser
    context.retryProvider.ensureThatB(
      RetryFor.Automation,
      "ensure_act_as_right",
      s"$validatorUser can actAs $endUserParty",
      connection.getUserActAs(validatorUser).map(_.contains(endUserParty)),
      connection.grantUserRights(validatorUser, Seq(endUserParty), Seq()),
      logger,
    )
  }
}
