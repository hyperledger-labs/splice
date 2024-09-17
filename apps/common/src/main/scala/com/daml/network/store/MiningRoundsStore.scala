// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.store

import com.daml.network.codegen.java.splice
import com.daml.network.util.{AssignedContract, SpliceUtil, Contract}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

trait MiningRoundsStore extends AppStore {

  /** Lookup the triple of open mining rounds that should always be present
    * after bootstrapping.
    */
  final def lookupOpenMiningRoundTriple()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[MiningRoundsStore.OpenMiningRoundTriple]] =
    for {
      openMiningRounds <- multiDomainAcsStore.listAssignedContracts(
        splice.round.OpenMiningRound.COMPANION
      )
    } yield for {
      newestOverallRound <- openMiningRounds.maxByOption(_.payload.round.number)
      // all rounds are signed by dso; pick the domain with the highest round#
      domain = newestOverallRound.domain
      Seq(oldest, middle, newest) <- Some(
        openMiningRounds
          .filter(_.domain == domain)
          .sortBy(_.payload.round.number)
      )
      if oldest.payload.round.number + 1 == middle.payload.round.number &&
        newest.payload.round.number - 1 == middle.payload.round.number
    } yield MiningRoundsStore.OpenMiningRoundTriple(
      oldest = oldest.contract,
      middle = middle.contract,
      newest = newest.contract,
      domain = domain,
    )

  /** Get the triple of open mining rounds that should always be present after boostrapping. */
  final def getOpenMiningRoundTriple()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[MiningRoundsStore.OpenMiningRoundTriple] =
    lookupOpenMiningRoundTriple().map(
      _.getOrElse(
        throw Status.NOT_FOUND
          .withDescription("No triple of OpenMiningRound contracts")
          .asRuntimeException()
      )
    )

  final def lookupLatestActiveOpenMiningRound()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[MiningRoundsStore.OpenMiningRound[AssignedContract]]] =
    lookupOpenMiningRoundTriple().map(_.map { triple =>
      AssignedContract(triple.newest, triple.domain)
    })

  /** get the latest active open mining round contract, which should always be present after bootstrapping. */
  def getLatestActiveOpenMiningRound()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[MiningRoundsStore.OpenMiningRound[AssignedContract]] =
    lookupLatestActiveOpenMiningRound().map(
      _.getOrElse(
        throw Status.NOT_FOUND
          .withDescription("No active OpenMiningRound contract")
          .asRuntimeException()
      )
    )

}

object MiningRoundsStore {
  type OpenMiningRound[Ct[_, _]] =
    Ct[splice.round.OpenMiningRound.ContractId, splice.round.OpenMiningRound]
  type OpenMiningRoundContract = OpenMiningRound[Contract]

  case class OpenMiningRoundTriple(
      oldest: OpenMiningRoundContract,
      middle: OpenMiningRoundContract,
      newest: OpenMiningRoundContract,
      domain: DomainId,
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("oldest", _.oldest),
        param("middle", _.middle),
        param("newest", _.newest),
        param("domain", _.domain),
      )

    /** The time after which these can be advanced at assuming the given tick duration. */
    def readyToAdvanceAt: Instant = {
      val middleTickDuration = SpliceUtil.relTimeToDuration(
        middle.payload.tickDuration
      )
      Ordering[Instant].max(
        oldest.payload.targetClosesAt,
        Ordering[Instant].max(
          // TODO(M3-07): when changing AmuletConfigs it will make sense to store tickDuration on the rounds and express targetClosesAt as 2 * tickDuration
          middle.payload.opensAt.plus(middleTickDuration),
          newest.payload.opensAt,
        ),
      )
    }

    def toSeq: Seq[OpenMiningRoundContract] = Seq(oldest, middle, newest)
  }

}
