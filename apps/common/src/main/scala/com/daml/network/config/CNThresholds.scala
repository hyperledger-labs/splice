package com.daml.network.config

import com.daml.network.codegen.java.cn.svcrules.SvcRules
import com.daml.network.util.Contract
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.topology.transaction.{HostingParticipant, ParticipantPermissionX}

import scala.math.{ceil, floor}

@SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
object CNThresholds {

  private def FPlus1Threshold(n: Int): PositiveInt = {
    if (n < 1) {
      PositiveInt.one
    } else {
      PositiveInt.tryCreate(Math.floorDiv(n - 1, 3) + 1)
    }
  }

  def mediatorDomainStateThreshold(
      currentMediatorSize: Int
  ): PositiveInt = {
    FPlus1Threshold(currentMediatorSize)
  }

  def partyToParticipantThreshold(
      hostingParticipants: Seq[HostingParticipant]
  ): PositiveInt = {
    governanceThreshold(
      hostingParticipants.count(_.permission == ParticipantPermissionX.Submission)
    )
  }

  def sequencerConnectionsSizeThreshold(sequencersSize: Int): PositiveInt =
    FPlus1Threshold(
      sequencersSize
    )

  def decentralizedNamespaceThreshold(decentralizedNamespaceSize: Int): PositiveInt = {
    governanceThreshold(decentralizedNamespaceSize)
  }

  def requiredNumVotes(
      svcRules: Contract.Has[SvcRules.ContractId, SvcRules]
  ): Int = {
    val memberNum = svcRules.payload.members.size
    governanceThreshold(memberNum).value
  }

  private def governanceThreshold(memberNum: Int) = {
    // as per `SvcRules` / `summarizeCollective`
    val f = floor((memberNum - 1) / 3.0).toInt
    PositiveInt.tryCreate(ceil((memberNum + f + 1) / 2.0).toInt)
  }
}
