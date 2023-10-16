package com.daml.network.config

import com.digitalasset.canton.config.RequireTypes.PositiveInt

@SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
object CNThresholds {

  private def FPlus1Threshold(n: Int) = {
    if (n < 1) {
      PositiveInt.one
    } else {
      PositiveInt.tryCreate(Math.floorDiv(n - 1, 3) + 1)
    }
  }

  def getMediatorDomainStateThreshold(svcSize: Int): PositiveInt = FPlus1Threshold(svcSize)

  def getPartyToParticipantThreshold(svcSize: Int, participantSize: Int): PositiveInt = {
    val minSize = List(svcSize + 1, participantSize + 1).min
    FPlus1Threshold(minSize)
  }

}
