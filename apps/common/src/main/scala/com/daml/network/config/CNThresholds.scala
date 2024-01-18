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

  // TODO(#7884): update function after proper sv off-boarding
  def mediatorDomainStateThresholdWithNewMember(
      currentSvcSize: Int,
      currentMediatorSize: Int,
  ): PositiveInt = {
    // take the minimum to avoid setting a threshold which is too high in concurrent onboarding setup.
    // add 1 to include the new sv.
    val minSize = List(currentSvcSize, currentMediatorSize).min + 1
    FPlus1Threshold(minSize)
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
