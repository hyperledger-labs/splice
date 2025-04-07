// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.daml.lf.data.Ref.PackageVersion
import io.grpc.Status
import org.lfdecentralizedtrust.splice.automation.MultiDomainExpiredContractTrigger.ListExpiredContracts
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  DsoRules,
  DsoRules_CloseVoteRequestResult,
  VoteRequest,
}
import org.lfdecentralizedtrust.splice.config.Thresholds
import org.lfdecentralizedtrust.splice.environment.DarResources
import org.lfdecentralizedtrust.splice.environment.PackageIdResolver.HasAmuletRules
import org.lfdecentralizedtrust.splice.util.Contract

import scala.concurrent.Future
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.RichOptional

trait VotesStore extends AppStore with DsoRulesStore with HasAmuletRules {

  def listAmuletPriceVotes(
      limit: Limit = Limit.DefaultLimit
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[
    splice.dso.amuletprice.AmuletPriceVote.ContractId,
    splice.dso.amuletprice.AmuletPriceVote,
  ]]] =
    multiDomainAcsStore
      .listContracts(splice.dso.amuletprice.AmuletPriceVote.COMPANION, limit)
      .map(_ map (_.contract))

  def listVoteRequests(limit: Limit = Limit.DefaultLimit)(implicit tc: TraceContext): Future[
    Seq[Contract[splice.dsorules.VoteRequest.ContractId, splice.dsorules.VoteRequest]]
  ] =
    multiDomainAcsStore
      .listContracts(splice.dsorules.VoteRequest.COMPANION, limit)
      .map(_ map (_.contract))

  def listVoteRequestsReadyToBeClosed: ListExpiredContracts[
    VoteRequest.ContractId,
    VoteRequest,
  ] = (now: CantonTimestamp, _: PageLimit) =>
    implicit traceContext => {

      def fulfillConditions(
          request: VoteRequest,
          dsoRules: Contract[DsoRules.ContractId, DsoRules],
      ): Boolean = {
        request.targetEffectiveAt.toScala match {
          case Some(effectiveAt) =>
            effectiveAt.isBefore(now.toInstant) || (request.voteBefore.isBefore(
              now.toInstant
            ) && request.votes.size < Thresholds.requiredNumVotes(dsoRules))
          case None => request.voteBefore.isBefore(now.toInstant)
        }
      }

      def fulfillConditionsForEarlyClosing(
          request: VoteRequest,
          dsoRules: Contract[DsoRules.ContractId, DsoRules],
          amuletRules: Contract[AmuletRules.ContractId, AmuletRules],
      ): Boolean = {
        val votes = request.votes.values().asScala
        val majorityRejected = votes.count(_.accept == false) >= Thresholds.requiredNumVotes(
          dsoRules
        )
        val majorityAccepted = votes.count(_.accept) >= Thresholds.requiredNumVotes(
          dsoRules
        )
        val dsoGovernanceVersion = PackageVersion.assertFromString(
          amuletRules.payload.configSchedule.initialValue.packageConfig.dsoGovernance
        )
        request.targetEffectiveAt.toScala match {
          case Some(_) => majorityRejected
          case None =>
            if (dsoGovernanceVersion >= DarResources.dsoGovernance_0_1_11.metadata.version) {
              majorityRejected || majorityAccepted
            } else {
              // TODO(#16139): get rid of this case
              votes.size >= dsoRules.payload.svs.size()
            }
        }
      }

      for {
        dsoRules <- getDsoRules()
        amuletRules <- getAmuletRules()
        // Assumption: there are less than 1000 active vote requests
        // listing contracts in memory has a default limit of 1000
        requests <- multiDomainAcsStore.listAssignedContracts(
          VoteRequest.COMPANION
        )
      } yield requests
        .filter(request =>
          fulfillConditions(request.payload, dsoRules.contract) || fulfillConditionsForEarlyClosing(
            request.payload,
            dsoRules.contract,
            amuletRules,
          )
        )

    }

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
    lookupVoteRequest(contractId).map(
      _.getOrElse(
        throw Status.NOT_FOUND
          .withDescription(s"Vote request not found for tracking-id $contractId")
          .asRuntimeException()
      )
    )
  }

}
