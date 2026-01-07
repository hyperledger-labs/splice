// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.automation

import org.lfdecentralizedtrust.splice.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.mintingdelegation.MintingDelegationProposal
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection
import org.lfdecentralizedtrust.splice.store.Limit
import org.lfdecentralizedtrust.splice.util.AssignedContract
import org.lfdecentralizedtrust.splice.wallet.ExternalPartyWalletManager
import org.lfdecentralizedtrust.splice.wallet.store.UserWalletStore
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/** Trigger to reject invalid MintingDelegationProposal
  * Current support for running a minting delegation is limited to the following
  * - The beneficiary is an onboarded external-party
  * - The delegate is the validator party
  * Proposals not satisfying both these conditions will be auto rejected
  *
  * Additionally if there are multiple proposals from the same beneficiary, this
  * trigger will reject all the existing proposals when a new proposal is received
  */
class RejectInvalidMintingDelegationProposalTrigger(
    override protected val context: TriggerContext,
    store: UserWalletStore,
    externalPartyWalletManager: ExternalPartyWalletManager,
    connection: SpliceLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      MintingDelegationProposal.ContractId,
      MintingDelegationProposal,
    ](
      store,
      MintingDelegationProposal.COMPANION,
    ) {

  private val endUserParty = store.key.endUserParty
  private val validatorParty = store.key.validatorParty

  override def completeTask(
      proposal: AssignedContract[
        MintingDelegationProposal.ContractId,
        MintingDelegationProposal,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val beneficiary = PartyId.tryFromProtoPrimitive(proposal.payload.delegation.beneficiary)

    if (endUserParty != validatorParty) {
      // always reject if user is not the validator
      rejectProposal(
        proposal,
        show"delegate is not a validator",
      )
    } else {
      val beneficiaryIsOnboarded =
        externalPartyWalletManager.lookupExternalPartyWallet(beneficiary).isDefined

      if (!beneficiaryIsOnboarded) {
        rejectProposal(
          proposal,
          show"beneficiary is not an onboarded external party",
        )
      } else {
        for {
          allProposals <- store.multiDomainAcsStore.listContracts(
            MintingDelegationProposal.COMPANION,
            Limit.DefaultLimit,
          )
          sameBeneficiaryProposals = allProposals.flatMap(_.toAssignedContract).filter { ac =>
            ac.payload.delegation.beneficiary == beneficiary.toProtoPrimitive &&
            ac.contractId != proposal.contractId
          }
          // Reject all existing proposals from the same beneficiary
          _ <- MonadUtil.sequentialTraverse_(sameBeneficiaryProposals) { existingProposal =>
            rejectProposal(
              existingProposal,
              show"duplicate proposal from same beneficiary",
            ).recover { case NonFatal(_) =>
              // Ignore errors - proposal may have already been rejected
              TaskSuccess(show"Proposal could not be rejected, ignoring")
            }
          }
        } yield TaskSuccess(
          show"MintingDelegationProposal is valid"
        )
      }
    }
  }

  private def rejectProposal(
      proposal: AssignedContract[
        MintingDelegationProposal.ContractId,
        MintingDelegationProposal,
      ],
      reason: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val beneficiary = proposal.payload.delegation.beneficiary

    connection
      .submit(
        actAs = Seq(endUserParty),
        readAs = Seq.empty,
        update = proposal.exercise(_.exerciseMintingDelegationProposal_Reject()),
      )
      .noDedup
      .yieldUnit()
      .map(_ =>
        TaskSuccess(
          show"Rejected MintingDelegationProposal from beneficiary $beneficiary: $reason"
        )
      )
  }
}
