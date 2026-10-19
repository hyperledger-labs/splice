// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import com.digitalasset.daml.lf.data.Ref.PackageVersion
import org.lfdecentralizedtrust.splice.store.UnavailablePartiesStore
import org.lfdecentralizedtrust.splice.util.UnresponsiveParties

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/** Executes a batch of contracts, isolating failures via depth-first batch splitting.
  *
  * The batch is pre-sorted ascending by the lowest package version supported by the informees of
  * each contract, so that contracts that are likely to fail are submitted first. If a submission
  * is rejected because some parties were unresponsive, those parties are named by the mediator and
  * are ignored directly. Any other failure is isolated by splitting the batch in half and retrying
  * the halves in DFS order. Once a batch of size 1 fails, the parties with the
  * lowest supported version on that contract are marked as unavailable in the
  * [[org.lfdecentralizedtrust.splice.store.UnavailablePartiesStore]], unless they are on the safety
  * list of protected parties.
  * TODO(#5019): On success, all parties involved in the submitted batch are removed from the store, resetting
  * their backoff.
  */
class BatchSplitting(
    lookupSupportedVersion: PartyId => TraceContext => Future[Option[PackageVersion]],
    unavailablePartiesStore: UnavailablePartiesStore,
    protectedParties: Set[PartyId],
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends NamedLogging {

  import BatchSplitting.*

  def processBatch[C](
      batch: Seq[C],
      parties: C => Set[PartyId],
      submit: Seq[C] => Future[Unit],
  )(implicit tc: TraceContext): Future[Result] =
    if (batch.isEmpty) Future.successful(Result.empty)
    else
      for {
        // the lowest supported versions are looked up once per party for the whole batch
        // and reused by the split batches for both the pre-sorting and the failure attribution
        lowestSupportedVersions <- lookupLowestSupportedVersions(batch.flatMap(parties).toSet)
        sortedBatch = sortBySupportedVersionAscending(batch, parties, lowestSupportedVersions)
        ignored <- unavailablePartiesStore.listParties().map(_.toSet)
        result <- processSplitBatches(
          List(sortedBatch),
          parties,
          submit,
          lowestSupportedVersions,
          ignored,
          Result.empty,
        )
      } yield result

  private def lookupLowestSupportedVersions(
      parties: Set[PartyId]
  )(implicit tc: TraceContext): Future[LowestSupportedVersions] =
    MonadUtil
      .sequentialTraverse(parties.toSeq.sortBy(_.toProtoPrimitive))(p =>
        lookupSupportedVersion(p)(tc).map(p -> _)
      )
      .map(_.toMap)

  /** Sorts the contracts ascending by the minimal package version supported by all informees. Contracts
    * for which no version could be determined (None) are placed at the front.
    */
  private def sortBySupportedVersionAscending[C](
      batch: Seq[C],
      parties: C => Set[PartyId],
      lowestSupportedVersions: LowestSupportedVersions,
  ): Seq[C] =
    batch
      .map(c => minVersion(parties(c).toSeq.map(versionOf(lowestSupportedVersions, _))) -> c)
      .sortBy(_._1)(versionOptionOrdering)
      .map(_._2)

  private def versionOf(
      lowestSupportedVersions: LowestSupportedVersions,
      party: PartyId,
  ): Option[PackageVersion] =
    lowestSupportedVersions.getOrElse(party, None)

  private def minVersion(
      lowestSupportedVersions: Seq[Option[PackageVersion]]
  ): Option[PackageVersion] =
    lowestSupportedVersions
      .foldLeft(Option.empty[Option[PackageVersion]]) {
        case (None, v) => Some(v)
        case (Some(acc), v) => Some(if (versionOptionOrdering.lt(v, acc)) v else acc)
      }
      .flatten

  /** Submits the batches on top of the stack, one at a time, splitting a batch in two on a
    * failure and pushing the halves back onto the stack. Parties that get ignored along the way
    * are carried in `ignoredParties`, so that later batches skip the contracts involving them.
    */
  private def processSplitBatches[C](
      stack: List[Seq[C]],
      partiesOf: C => Set[PartyId],
      submit: Seq[C] => Future[Unit],
      lowestSupportedVersions: LowestSupportedVersions,
      ignoredParties: Set[PartyId],
      result: Result,
  )(implicit tc: TraceContext): Future[Result] = stack match {
    // If the stack is empty, we are done and return the accumulated result.
    case Nil => Future.successful(result)
    // If the stack is not empty, we pop the top batch and submit it.
    case head :: rest =>
      // contracts involving parties that got ignored while processing earlier sub-batches must not be re-submitted
      val current = head.filterNot(c => partiesOf(c).exists(ignoredParties.contains))
      if (current.isEmpty) {
        processSplitBatches(
          rest,
          partiesOf,
          submit,
          lowestSupportedVersions,
          ignoredParties,
          result,
        )
      } else {
        submit(current).transformWith {
          // On success, remove all involved parties from the unavailable parties store to reset their backoff, go on with rest.
          case Success(_) =>
            val involvedParties = current.flatMap(partiesOf).toSet -- protectedParties
            unavailablePartiesStore
              .removeParties(involvedParties.toSeq)
              .flatMap(_ =>
                processSplitBatches(
                  rest,
                  partiesOf,
                  submit,
                  lowestSupportedVersions,
                  ignoredParties,
                  result.addSubmitted(current.size),
                )
              )
          // On Failure:
          //  - if the mediator named the unresponsive parties, ignore exactly those: the whole
          //    batch is rejected up-front, so splitting would neither help nor identify anyone.
          //  - if the batch has more than one contract, split it in half and retry both halves.
          //  - if the batch has only one contract, mark the parties with the lowest supported version as unavailable.
          case Failure(UnresponsivePartiesFailure(unresponsiveParties))
              if shrinks(current, partiesOf, unresponsiveParties -- protectedParties) =>
            val toIgnore = unresponsiveParties -- protectedParties
            val remaining = current.filterNot(c => partiesOf(c).exists(toIgnore.contains))
            logger.info(
              s"Batch of ${current.size} contracts was rejected due to unresponsive parties, ignoring $toIgnore"
            )
            unavailablePartiesStore
              .addParties(toIgnore.toSeq)
              .flatMap(_ =>
                processSplitBatches(
                  // the remaining contracts were rejected along with the batch, so they go back on the stack
                  remaining :: rest,
                  partiesOf,
                  submit,
                  lowestSupportedVersions,
                  ignoredParties ++ toIgnore,
                  result.addIgnored(toIgnore, failedContracts = current.size - remaining.size),
                )
              )
          case Failure(reason) if current.size > 1 =>
            val (left, right) = current.splitAt(current.size / 2)
            logger.info(
              s"Splitting batch of ${current.size} contracts into ${left.size} and ${right.size} after failure: $reason"
            )
            processSplitBatches(
              // push right first, then left, so that left is popped next (DFS)
              left :: right :: rest,
              partiesOf,
              submit,
              lowestSupportedVersions,
              ignoredParties,
              result.addSplit(),
            )
          case Failure(reason) =>
            val involvedParties = current.headOption.fold(Set.empty[PartyId])(partiesOf)
            logger.info(
              s"Isolated failure to a single contract with parties $involvedParties: $reason"
            )
            val candidates =
              partiesWithMinimalSupportedVersion(involvedParties, lowestSupportedVersions)
            val toIgnore = candidates -- protectedParties
            for {
              _ <-
                if (toIgnore.isEmpty) {
                  logger.info(
                    s"Not ignoring any party for the failing contract, all candidates ($candidates) are protected"
                  )
                  Future.unit
                } else {
                  unavailablePartiesStore.addParties(toIgnore.toSeq)
                }
              result <- processSplitBatches(
                rest,
                partiesOf,
                submit,
                lowestSupportedVersions,
                ignoredParties ++ toIgnore,
                result.addIgnored(toIgnore, failedContracts = 1),
              )
            } yield result
        }
      }
  }

  /** Conservatively ignores the parties supporting the lowest package version.
    * Falls back to all involved parties if they all support the same version or if no version could be determined.
    */
  private def partiesWithMinimalSupportedVersion(
      parties: Set[PartyId],
      lowestSupportedVersions: LowestSupportedVersions,
  ): Set[PartyId] = {
    val partyLowestSupportedVersions =
      parties.toSeq.map(p => p -> versionOf(lowestSupportedVersions, p))
    val min = minVersion(partyLowestSupportedVersions.map(_._2))
    partyLowestSupportedVersions.collect {
      case (p, v) if versionOptionOrdering.equiv(v, min) => p
    }.toSet
  }
}

object BatchSplitting {

  private type LowestSupportedVersions = Map[PartyId, Option[PackageVersion]]

  /** Matches failures for which the mediator reported the parties that did not respond. */
  private object UnresponsivePartiesFailure {
    def unapply(t: Throwable): Option[Set[PartyId]] = UnresponsiveParties.fromThrowable(t)
  }

  /** Ignoring the given parties only pays off if it actually removes contracts from the batch,
    * otherwise the very same batch would be resubmitted and we would loop forever.
    */
  private def shrinks[C](
      batch: Seq[C],
      partiesOf: C => Set[PartyId],
      toIgnore: Set[PartyId],
  ): Boolean =
    toIgnore.nonEmpty && batch.exists(c => partiesOf(c).exists(toIgnore.contains))

  // parties without any version sort first
  private[automation] val versionOptionOrdering: Ordering[Option[PackageVersion]] =
    Ordering.Option(Ordering.fromLessThan[PackageVersion](_ < _))

  final case class Result(
      submittedContracts: Int,
      failedContracts: Int,
      splits: Int,
      ignoredParties: Set[PartyId],
  ) {
    def addSubmitted(n: Int): Result = copy(submittedContracts = submittedContracts + n)
    def addSplit(): Result = copy(splits = splits + 1)
    def addIgnored(parties: Set[PartyId], failedContracts: Int): Result =
      copy(
        ignoredParties = ignoredParties ++ parties,
        failedContracts = this.failedContracts + failedContracts,
      )

    def summary: String =
      s"submitted $submittedContracts contracts, failed $failedContracts, " +
        s"$splits batch splits, newly ignored parties: ${ignoredParties.size}" +
        (if (ignoredParties.isEmpty) "" else s" ($ignoredParties)")
  }

  object Result {
    val empty: Result = Result(0, 0, 0, Set.empty)
  }
}
