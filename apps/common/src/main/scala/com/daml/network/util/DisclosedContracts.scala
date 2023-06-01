package com.daml.network.util

import com.daml.ledger.api.v1.CommandsOuterClass.DisclosedContract as Lav1DisclosedContract
import com.daml.network.store.MultiDomainAcsStore.{ContractState, ContractWithState}
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.util.ShowUtil.*
import PrettyInstances.*

/** This extends the simple `Seq[DisclosedContract]` with invariants to be determined.
  * It doesn't make sense to allow contracts with different states to be disclosure
  * arguments to a single submission, because to be disclosed, they must be in the same state.
  * Moreover, it doesn't make sense to disclose contracts in a state that doesn't
  * match the domain ID for the whole command.  So we can make `apply` stricter
  * as we tighten up these invariants, and always only convert
  * `#toLedgerApiDisclosedContracts` when ready for actual submission.
  */
final class DisclosedContracts private (
    private val statefulContracts: Seq[ContractWithState[?, ?]],
    private val statelessContracts: Seq[Contract[?, ?]],
) {
  def toLedgerApiDisclosedContracts: Seq[Lav1DisclosedContract] =
    (statefulContracts.map(_.contract.toDisclosedContract)
      ++ statelessContracts.map(_.toDisclosedContract))

  /** Semigroup append. */
  def ++(other: DisclosedContracts): DisclosedContracts =
    new DisclosedContracts(
      statefulContracts ++ other.statefulContracts,
      statelessContracts ++ other.statelessContracts,
    )

  /** Pick a consistent `domainId` argument for ledger API submission that will take
    * these disclosures.  This is entirely based on disclosed contracts' states if
    * `ifExpected` is [[None]]; otherwise equivalent to [[#assertOnDomain]].
    */
  @throws[IllegalStateException]
  private[network] def inferDomain(ifExpected: Option[DomainId]): Option[DomainId] =
    ifExpected.fold {
      val differingStates = statefulContracts.view.collect {
        case ContractWithState(_, ContractState.Assigned(domainId)) => Some(domainId)
        case _ => None
      }.toSet
      if (differingStates.sizeIs > 1)
        throw new IllegalStateException(
          show"contracts are assigned to different domains: $statefulContracts"
        )
      else differingStates.headOption.flatten
    } { exDomain =>
      assertOnDomain(exDomain)
      ifExpected
    }

  /** Throw if any contracts with known state are not assigned to `domainId`.
    */
  @throws[IllegalStateException]
  private[network] def assertOnDomain(domainId: DomainId): this.type = {
    val inOtherStates = statefulContracts.filter {
      case ContractWithState(_, ContractState.Assigned(`domainId`)) => false
      case _ => true
    }
    if (inOtherStates.isEmpty) this
    else
      throw new IllegalStateException(
        show"not all disclosed contracts are on expected domain $domainId: $inOtherStates"
      )
  }
}

object DisclosedContracts {
  def apply(arg: Arg*): DisclosedContracts = {
    val (stful, stless) = arg.partitionMap {
      case Arg.Stateful(c) => Left(c)
      case Arg.Stateless(c) => Right(c)
    }
    new DisclosedContracts(stful, stless)
  }

  import language.implicitConversions

  /** Allow each argument to `apply` to pick a separate overload. */
  sealed abstract class Arg extends Product with Serializable

  object Arg {
    implicit def stateful(c: ContractWithState[?, ?]): Arg = Stateful(c)
    implicit def stateless(c: Contract[?, ?]): Arg = Stateless(c)
    private[DisclosedContracts] final case class Stateful(c: ContractWithState[?, ?]) extends Arg
    private[DisclosedContracts] final case class Stateless(c: Contract[?, ?]) extends Arg
  }
}
