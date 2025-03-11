// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import com.digitalasset.canton.topology.SynchronizerId
import org.lfdecentralizedtrust.splice.environment.{PackageIdResolver, ParticipantAdminConnection}
import org.lfdecentralizedtrust.splice.util.PackageVetting
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.Future

abstract class PackageVettingTrigger(packages: Set[PackageIdResolver.Package])
    extends PollingTrigger
    with PackageIdResolver.HasAmuletRules {

  def getSynchronizerId()(implicit tc: TraceContext): Future[SynchronizerId]

  protected def participantAdminConnection: ParticipantAdminConnection

  val vetting = new PackageVetting(
    packages,
    context.clock,
    participantAdminConnection,
    loggerFactory,
  )

  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] = {
    for {
      domainId <- getSynchronizerId()
      amuletRules <- getAmuletRules()
      _ <- vetting.vetPackages(domainId, amuletRules)
    } yield false
  }
}
