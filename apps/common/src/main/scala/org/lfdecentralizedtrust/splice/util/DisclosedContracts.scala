// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.daml.ledger.api.v2.CommandsOuterClass.DisclosedContract as Lav1DisclosedContract
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.ContractState
import com.daml.nonempty.{NonEmpty, Singleton}
import com.daml.nonempty.NonEmptyReturningOps.*
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.util.ShowUtil.*
import PrettyInstances.*
import io.grpc.StatusRuntimeException
import io.grpc.Status.FAILED_PRECONDITION

/** This extends the simple `Seq[DisclosedContract]` with invariants to be determined.
  * It doesn't make sense to allow contracts with different states to be disclosure
  * arguments to a single submission, because to be disclosed, they must be in the same state.
  * Moreover, it doesn't make sense to disclose contracts in a state that doesn't
  * match the domain ID for the whole command.  So we can make `apply` stricter
  * as we tighten up these invariants, and always only convert
  * `#toLedgerApiDisclosedContracts` when ready for actual submission.
  */
sealed abstract class DisclosedContracts {
  import DisclosedContracts.{Empty, NE, Ex, retryableError}

  def toLedgerApiDisclosedContracts: Seq[Lav1DisclosedContract] = this match {
    case Empty => Seq.empty
    case NE(contracts, _) => contracts.map(_.toDisclosedContract)
  }

  /** Pick a consistent `domainId` argument for ledger API submission that will take
    * these disclosures.  This is entirely based on disclosed contracts' states if
    * `ifExpected` is [[None]]; otherwise equivalent to [[#assertOnDomain]].
    */
  @throws[Ex]
  private[splice] def inferDomain(ifExpected: Option[DomainId]): Option[DomainId]

  /** Overwrite the domain id with the domain id of the disclosed contracts as those cannot be reassigned.
    */
  // TODO(#13713) Remove this once our domain selection logic works properly with soft domain migrations
  private[splice] def overwriteDomain(target: DomainId): DomainId

  /** Throw if any contracts with known state are not assigned to `domainId`.
    */
  @throws[Ex]
  private[splice] def assertOnDomain(domainId: DomainId): this.type =
    this match {
      case Empty | NE(_, `domainId`) => this
      case NE(contracts, otherDomainId) =>
        // TODO (#8135) invalidate contracts
        retryableError(
          show"disclosed contracts are not on expected domain $domainId, but on $otherDomainId: $contracts"
        )
    }
}

object DisclosedContracts {
  def apply(): Empty.type = Empty

  @throws[Ex]
  def apply(
      callbacks: Seq[String => Unit],
      arg: ContractWithState[?, ?],
      args: ContractWithState[?, ?]*
  ): NE = {
    val contracts = arg +-: args
    contracts.map(_.state).toSet match {
      case Singleton(ContractState.Assigned(onlyDomain)) =>
        NE(contracts.map(_.contract), onlyDomain)
      case variousStates =>
        // We expect there to be background automation that ensures that
        // all disclosed contracts are eventually on the same domain.
        // We thus invalidate all caches so that the disclosed contracts get re-fetched.
        contracts.foreach { c =>
          callbacks.foreach(f => f(c.contractId.contractId))
        }
        retryableError(
          show"contracts must be assigned to a single domain to be disclosed, not $variousStates: $contracts"
        )
    }
  }

  // This should only be used for testing, otherwise use SpliceLedgerConnection.disclosedContracts
  // which does the right cache invalidation.
  @throws[Ex]
  def forTesting(arg: ContractWithState[?, ?], args: ContractWithState[?, ?]*): NE =
    DisclosedContracts(Seq.empty, arg, args*)

  private type Ex = StatusRuntimeException

  @throws[Ex]
  private def retryableError(description: String): Nothing =
    throw (FAILED_PRECONDITION.augmentDescription(description).asRuntimeException(): Ex)

  case object Empty extends DisclosedContracts {
    private[splice] override def inferDomain(ifExpected: Option[DomainId]): ifExpected.type =
      ifExpected

    private[splice] override def overwriteDomain(target: DomainId) = target
  }

  final case class NE(
      private val contracts: NonEmpty[Seq[Contract[?, ?]]],
      assignedDomain: DomainId,
  ) extends DisclosedContracts {
    private[splice] override def inferDomain(ifExpected: Option[DomainId]): Some[DomainId] =
      ifExpected match {
        case it @ Some(exDomain) =>
          assertOnDomain(exDomain)
          it
        case None => Some(assignedDomain)
      }

    private[splice] override def overwriteDomain(target: DomainId) = assignedDomain

    @throws[Ex]
    def addAll(other: Seq[ContractWithState[?, ?]]): NE = {
      val inOtherStates = other.filter {
        case ContractWithState(_, ContractState.Assigned(`assignedDomain`)) => false
        case _ => true
      }
      if (inOtherStates.isEmpty) NE(contracts ++ other.map(_.contract), assignedDomain)
      else // TODO (#8135) invalidate contracts and other
        retryableError(
          show"contracts must match the domain of other disclosed contracts, $assignedDomain, to be disclosed: $other"
        )
    }
  }
}
