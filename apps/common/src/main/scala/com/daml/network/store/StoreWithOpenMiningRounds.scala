package com.daml.network.store

import com.daml.network.codegen.java.cc.round.OpenMiningRound
import com.daml.network.util.JavaContract
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.FlagCloseable
import io.grpc.{Status, StatusRuntimeException}

import scala.concurrent.{ExecutionContext, Future}

/** Mix-in for an ACS-based store that tracks the OpenMiningRound contracts. */
trait StoreWithOpenMiningRounds { this: FlagCloseable =>

  protected def acs: AcsStore

  /** Returns the active open mining rounds who are open according to 'opensAt'. */
  def lookupSubmittableOpenMiningRounds(
      now: CantonTimestamp
  )(implicit
      ec: ExecutionContext
  ): Future[Seq[JavaContract[OpenMiningRound.ContractId, OpenMiningRound]]] = {
    acs
      .listContracts(OpenMiningRound.COMPANION)
      // only return open open rounds.
      .map(
        _.filter(r => r.payload.opensAt.compareTo(now.toInstant) <= 0)
          .sortBy(r => r.payload.opensAt)
      )
  }

  /** Get latest mining round which can be submitted against.
    */
  def getLatestOpenMiningRound(now: CantonTimestamp)(implicit
      ec: ExecutionContext
  ): Future[JavaContract[OpenMiningRound.ContractId, OpenMiningRound]] =
    lookupSubmittableOpenMiningRounds(now).map(
      _.maxByOption { c =>
        c.payload.round.number: Long
      }.getOrElse(
        throw new StatusRuntimeException(
          Status.NOT_FOUND.withDescription("No active OpenMiningRound contract")
        )
      )
    )
}
