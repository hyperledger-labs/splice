// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.automation

import org.lfdecentralizedtrust.splice.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.ValidatorRight
import org.lfdecentralizedtrust.splice.environment.{
  ParticipantAdminConnection,
  RetryFor,
  SpliceLedgerConnection,
}
import org.lfdecentralizedtrust.splice.util.AssignedContract
import org.lfdecentralizedtrust.splice.wallet.ExternalPartyWalletManager
import com.digitalasset.canton.lifecycle.UnlessShutdown
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}

class ValidatorRightTrigger(
    override protected val context: TriggerContext,
    walletManager: ExternalPartyWalletManager,
    connection: SpliceLedgerConnection,
    participantAdminConnection: ParticipantAdminConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      ValidatorRight.ContractId,
      ValidatorRight,
    ](
      walletManager.store,
      ValidatorRight.COMPANION,
    ) {

  override def completeTask(
      validatorRight: AssignedContract[
        ValidatorRight.ContractId,
        ValidatorRight,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val party = PartyId.tryFromProtoPrimitive(validatorRight.payload.user)
    for {
      partyToKeyMappings <- participantAdminConnection.listPartyToKey(
        filterStore = TopologyStoreId.Synchronizer(validatorRight.domain),
        filterParty = Some(party),
      )
      r <-
        if (partyToKeyMappings.isEmpty) {
          Future.successful(
            TaskSuccess(
              s"No PartyToKeyMapping for party $party, handled through WalletAppInstallTrigger"
            )
          )
        } else {
          for {
            // actAs rights for the validator user might have gotten lost during a hard migration or a disaster recovery
            _ <- ensureActAsRight(party)
          } yield walletManager.getOrCreateWallet(party) match {
            case UnlessShutdown.AbortedDueToShutdown =>
              TaskSuccess(
                s"skipped or aborted starting wallet automation for end-user party '$party', as we are shutting down."
              )
            case UnlessShutdown.Outcome(true) =>
              TaskSuccess(show"started wallet automation for end-user party $party")
            case UnlessShutdown.Outcome(false) =>
              TaskSuccess(
                show"skipped starting wallet automation for end-user party '$party', as it is already running."
              )
          }
        }
    } yield r
  }

  private def ensureActAsRight(party: PartyId)(implicit tc: TraceContext): Future[Unit] = {
    val validatorUser = walletManager.validatorUser
    context.retryProvider.ensureThatB(
      RetryFor.Automation,
      "ensure_act_as_right",
      s"$validatorUser can actAs $party",
      connection.getUserActAs(validatorUser).map(_.contains(party)),
      connection.grantUserRights(validatorUser, Seq(party), Seq()),
      logger,
    )
  }
}
