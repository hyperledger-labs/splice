// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import org.lfdecentralizedtrust.splice.environment.{PackageIdResolver, ParticipantAdminConnection}
import org.lfdecentralizedtrust.splice.util.{AmuletConfigSchedule, PackageVetting}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.Future

abstract class PackageVettingTrigger(packages: Set[PackageIdResolver.Package])
    extends PollingTrigger
    with PackageIdResolver.HasAmuletRules
    with PackageVetting.HasVoteRequests {

  protected def participantAdminConnection: ParticipantAdminConnection

  // Duration that packages will be pre-vetted by. E.g.,
  // if this is set to 5 minutes packages will be vetted
  // 5 minutes before the switch in AmuletConfig.
  protected def prevetDuration: NonNegativeFiniteDuration

  val vetting = new PackageVetting(
    packages,
    prevetDuration,
    context.clock,
    participantAdminConnection,
    loggerFactory,
  )

  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] = {
    for {
      amuletRules <- getAmuletRules()
      voteRequests <- getVoteRequests()
      _ <- vetting.vetPackages(
        amuletRules,
        AmuletConfigSchedule.filterAmuletBasedSetConfigVoteRequests(voteRequests),
      )
    } yield false
  }
}
