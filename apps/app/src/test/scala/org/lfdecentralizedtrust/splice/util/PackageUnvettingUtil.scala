package org.lfdecentralizedtrust.splice.util

import com.digitalasset.canton.LfPackageId
import com.digitalasset.canton.topology.SynchronizerId
import org.lfdecentralizedtrust.splice.environment.ParticipantAdminConnection
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyTransactionType.AuthorizedState
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.TestCommon

trait PackageUnvettingUtil extends TestCommon {

  protected def getVettedPackageIds(
      participantAdminConnection: ParticipantAdminConnection,
      synchronizerId: SynchronizerId,
  ): Seq[LfPackageId] = {
    val participantId = participantAdminConnection.getParticipantId().futureValue
    participantAdminConnection
      .listVettedPackages(participantId, synchronizerId, AuthorizedState)
      .futureValue
      .flatMap(_.mapping.packages)
      .map(_.packageId)
  }

}
