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

  // TODO(#7884): update function after proper sv off-boarding
  def getMediatorDomainStateThreshold(svcSize: Int, mediatorSize: Int): PositiveInt = {
    // take the minimum to avoid setting a threshold which is too high in concurrent onboarding setup.
    // add 1 to include the new sv.
    val minSize = List(svcSize, mediatorSize).min + 1
    FPlus1Threshold(minSize)
  }

  // TODO(#7884): update function after proper sv off-boarding
  def getPartyToParticipantThreshold(svcSize: Int, participantSize: Int): PositiveInt = {
    // take the minimum to avoid setting a threshold which is too high in concurrent onboarding setup.
    // add 1 to include the new sv.
    val minSize = List(svcSize, participantSize).min + 1
    FPlus1Threshold(minSize)
  }

}
