package com.daml.network.config

import com.digitalasset.canton.config.RequireTypes.PositiveInt

object CNThresholds {

  private def FPlus1Threshold(n: Int) = {
    if (n < 1) {
      PositiveInt.one
    } else {
      PositiveInt.tryCreate(Math.floorDiv(n - 1, 3) + 1)
    }
  }

  def getMediatorDomainStateThreshold(svcSize: Int): PositiveInt = FPlus1Threshold(svcSize)

  def getPartyToParticipantThreshold(svcSize: Int): PositiveInt = FPlus1Threshold(svcSize)

}
