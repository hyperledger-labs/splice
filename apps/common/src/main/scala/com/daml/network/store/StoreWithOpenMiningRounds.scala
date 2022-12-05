package com.daml.network.store

import com.daml.network.codegen.java.cc.round.OpenMiningRound
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.util.JavaContract
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.FlagCloseable
import io.grpc.{Status, StatusRuntimeException}

import scala.concurrent.{ExecutionContext, Future}

/** Mix-in for an ACS-based store that tracks the OpenMiningRound contracts. */
trait StoreWithOpenMiningRounds { this: FlagCloseable =>

  protected def acsStore: AcsStore

  /** Returns the active open mining rounds who are open according to 'opensAt'. */
  def lookupSubmittableOpenMiningRounds(
      now: CantonTimestamp
  )(implicit ec: ExecutionContext): Future[QueryResult[
    Seq[JavaContract[OpenMiningRound.ContractId, OpenMiningRound]]
  ]] = {
    acsStore
      .listContracts(OpenMiningRound.COMPANION)
      .map(
        _.map(contracts =>
          contracts
            // only return open open rounds.
            .filter(r => r.payload.opensAt.compareTo(now.toInstant) <= 0)
            .sortBy(r => r.payload.opensAt)
        )
      )
  }

  /** Get latest mining round which can be submitted against.
    */
  def getLatestOpenMiningRound(now: CantonTimestamp)(implicit
      ec: ExecutionContext
  ): Future[
    QueryResult[JavaContract[OpenMiningRound.ContractId, OpenMiningRound]]
  ] =
    lookupSubmittableOpenMiningRounds(now).map { queryResult =>
      queryResult.map(
        _.lastOption.getOrElse(
          throw new StatusRuntimeException(
            Status.NOT_FOUND.withDescription("No active OpenMiningRound contract")
          )
        )
      )
    }
}
