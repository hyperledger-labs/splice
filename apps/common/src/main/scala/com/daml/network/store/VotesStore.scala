// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.store

import com.daml.network.codegen.java.splice
import com.daml.network.codegen.java.splice.dsorules.{DsoRules_CloseVoteRequestResult, VoteRequest}
import com.daml.network.util.Contract
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import com.digitalasset.canton.util.ShowUtil.*

import scala.concurrent.Future

trait VotesStore extends AppStore {

  def listVoteRequests(limit: Limit = Limit.DefaultLimit)(implicit tc: TraceContext): Future[
    Seq[Contract[splice.dsorules.VoteRequest.ContractId, splice.dsorules.VoteRequest]]
  ] =
    multiDomainAcsStore
      .listContracts(splice.dsorules.VoteRequest.COMPANION, limit)
      .map(_ map (_.contract))

  def listVoteRequestResults(
      actionName: Option[String],
      accepted: Option[Boolean],
      requester: Option[String],
      effectiveFrom: Option[String],
      effectiveTo: Option[String],
      limit: Limit = Limit.DefaultLimit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[DsoRules_CloseVoteRequestResult]]

  def listVoteRequestsByTrackingCid(
      voteRequestCids: Seq[splice.dsorules.VoteRequest.ContractId],
      limit: Limit = Limit.DefaultLimit,
  )(implicit
      tc: TraceContext
  ): Future[
    Seq[Contract[VoteRequest.ContractId, VoteRequest]]
  ]

  def lookupVoteRequest(contractId: splice.dsorules.VoteRequest.ContractId)(implicit
      tc: TraceContext
  ): Future[Option[Contract[splice.dsorules.VoteRequest.ContractId, splice.dsorules.VoteRequest]]]

  def getVoteRequest(contractId: splice.dsorules.VoteRequest.ContractId)(implicit
      tc: TraceContext
  ): Future[Contract[splice.dsorules.VoteRequest.ContractId, splice.dsorules.VoteRequest]] = {
    import com.digitalasset.canton.participant.pretty.Implicits.prettyContractId
    lookupVoteRequest(contractId).map(
      _.getOrElse(
        throw Status.NOT_FOUND
          .withDescription(show"Vote request not found for tracking-id $contractId")
          .asRuntimeException()
      )
    )
  }

}
