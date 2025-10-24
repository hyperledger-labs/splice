// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.config

import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules
import org.lfdecentralizedtrust.splice.util.Contract
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.topology.transaction.{HostingParticipant, ParticipantPermission}

import scala.math.{ceil, floor}

@SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
object Thresholds {

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
      hostingParticipants.count(_.permission == ParticipantPermission.Submission)
    )
  }

  def sequencerConnectionsSizeThreshold(sequencersSize: Int): PositiveInt =
    FPlus1Threshold(
      sequencersSize
    )

  def sequencerSubmissionRequestAmplification(sequencersSize: Int): PositiveInt =
    sequencerConnectionsSizeThreshold(sequencersSize)

  def decentralizedNamespaceThreshold(decentralizedNamespaceSize: Int): PositiveInt = {
    governanceThreshold(decentralizedNamespaceSize)
  }

  def requiredNumVotes(
      dsoRules: Contract.Has[DsoRules.ContractId, DsoRules]
  ): Int = {
    val svNum = dsoRules.payload.svs.size
    governanceThreshold(svNum).value
  }

  private def governanceThreshold(svNum: Int) = {
    // as per `DsoRules` / `summarizeDso`
    val f = floor((svNum - 1) / 3.0).toInt
    PositiveInt.tryCreate(ceil((svNum + f + 1) / 2.0).toInt)
  }

  def requiredNumScanThreshold(svNum: Int) = {
    (svNum / 3) + 1
  }
}
