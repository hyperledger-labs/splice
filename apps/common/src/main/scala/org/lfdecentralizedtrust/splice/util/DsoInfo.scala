// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import cats.syntax.either.*
import cats.syntax.traverse.*
import com.digitalasset.canton.logging.ErrorLoggingContext
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.svstate.SvNodeState
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.OpenMiningRound
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.util.{ContractWithState, TemplateJsonDecoder}
import com.digitalasset.canton.topology.PartyId

/** Decoded version of [[definitions.GetDsoInfoResponse]], as served by scan's `/v0/dso`
  * and the SV app's `/v1/dso` endpoints.
  */
final case class DsoInfo(
    svUser: String,
    svParty: PartyId,
    dsoParty: PartyId,
    votingThreshold: BigInt,
    latestMiningRound: ContractWithState[OpenMiningRound.ContractId, OpenMiningRound],
    amuletRules: ContractWithState[AmuletRules.ContractId, AmuletRules],
    dsoRules: ContractWithState[DsoRules.ContractId, DsoRules],
    svNodeStates: Map[PartyId, ContractWithState[SvNodeState.ContractId, SvNodeState]],
    initialRound: Option[String],
) {

  def toHttp(implicit elc: ErrorLoggingContext): definitions.GetDsoInfoResponse =
    definitions.GetDsoInfoResponse(
      svUser,
      svParty.toProtoPrimitive,
      dsoParty.toProtoPrimitive,
      votingThreshold,
      latestMiningRound.toHttp,
      amuletRules.toHttp,
      dsoRules.toHttp,
      svNodeStates.values.map(_.toHttp).toVector,
      initialRound,
    )

  def toV1: DsoInfoV1 =
    DsoInfoV1(
      dsoParty,
      votingThreshold,
      latestMiningRound,
      amuletRules,
      dsoRules,
      svNodeStates,
      initialRound,
    )
}

object DsoInfo {

  def fromHttp(
      dsoInfo: definitions.GetDsoInfoResponse
  )(implicit decoder: TemplateJsonDecoder): Either[String, DsoInfo] =
    for {
      svPartyId <- Codec.decode(Codec.Party)(dsoInfo.svPartyId)
      dsoPartyId <- Codec.decode(Codec.Party)(dsoInfo.dsoPartyId)
      latestMiningRound <- ContractWithState
        .fromHttp(OpenMiningRound.COMPANION)(dsoInfo.latestMiningRound)
        .leftMap(_.toString)
      amuletRules <- ContractWithState
        .fromHttp(AmuletRules.COMPANION)(dsoInfo.amuletRules)
        .left
        .map(_.toString)
      dsoRules <- ContractWithState
        .fromHttp(DsoRules.COMPANION)(dsoInfo.dsoRules)
        .left
        .map(_.toString)
      svNodeStates <- dsoInfo.svNodeStates.traverse { co =>
        for {
          nodeState <- ContractWithState
            .fromHttp(SvNodeState.COMPANION)(co)
            .left
            .map(_.toString)
          partyId <- Codec.decode(Codec.Party)(nodeState.payload.sv)
        } yield partyId -> nodeState
      }
    } yield DsoInfo(
      dsoInfo.svUser,
      svPartyId,
      dsoPartyId,
      dsoInfo.votingThreshold,
      latestMiningRound,
      amuletRules,
      dsoRules,
      svNodeStates.toMap,
      dsoInfo.initialRound,
    )
}

final case class DsoInfoV1(
    dsoParty: PartyId,
    votingThreshold: BigInt,
    latestMiningRound: ContractWithState[OpenMiningRound.ContractId, OpenMiningRound],
    amuletRules: ContractWithState[AmuletRules.ContractId, AmuletRules],
    dsoRules: ContractWithState[DsoRules.ContractId, DsoRules],
    svNodeStates: Map[PartyId, ContractWithState[SvNodeState.ContractId, SvNodeState]],
    initialRound: Option[String],
) {

  def toHttp(implicit elc: ErrorLoggingContext): definitions.GetDsoInfoResponseV1 =
    definitions.GetDsoInfoResponseV1(
      dsoParty.toProtoPrimitive,
      votingThreshold,
      latestMiningRound.toHttp,
      amuletRules.toHttp,
      dsoRules.toHttp,
      svNodeStates.values.map(_.toHttp).toVector,
      initialRound,
    )
}

object DsoInfoV1 {

  def fromHttp(
      dsoInfo: definitions.GetDsoInfoResponseV1
  )(implicit decoder: TemplateJsonDecoder): Either[String, DsoInfoV1] =
    for {
      dsoPartyId <- Codec.decode(Codec.Party)(dsoInfo.dsoPartyId)
      latestMiningRound <- ContractWithState
        .fromHttp(OpenMiningRound.COMPANION)(dsoInfo.latestMiningRound)
        .leftMap(_.toString)
      amuletRules <- ContractWithState
        .fromHttp(AmuletRules.COMPANION)(dsoInfo.amuletRules)
        .leftMap(_.toString)
      dsoRules <- ContractWithState
        .fromHttp(DsoRules.COMPANION)(dsoInfo.dsoRules)
        .leftMap(_.toString)
      svNodeStates <- dsoInfo.svNodeStates.traverse { co =>
        for {
          nodeState <- ContractWithState
            .fromHttp(SvNodeState.COMPANION)(co)
            .leftMap(_.toString)
          partyId <- Codec.decode(Codec.Party)(nodeState.payload.sv)
        } yield partyId -> nodeState
      }
    } yield DsoInfoV1(
      dsoPartyId,
      dsoInfo.votingThreshold,
      latestMiningRound,
      amuletRules,
      dsoRules,
      svNodeStates.toMap,
      dsoInfo.initialRound,
    )
}
