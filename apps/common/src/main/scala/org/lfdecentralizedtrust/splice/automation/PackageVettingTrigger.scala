// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.environment.{PackageIdResolver, ParticipantAdminConnection}
import org.lfdecentralizedtrust.splice.util.{AmuletConfigSchedule, PackageVetting}

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future

abstract class PackageVettingTrigger(
    packages: Set[PackageIdResolver.Package],
    maxVettingDelay: NonNegativeFiniteDuration,
    latestPackagesOnly: Boolean,
) extends PollingTrigger
    with PackageIdResolver.HasAmuletRules
    with PackageVetting.HasVoteRequests {

  private val previouslyRunInputRef = new AtomicReference[Set[String]](Set.empty)

  def getSynchronizerId()(implicit tc: TraceContext): Future[SynchronizerId]

  protected def participantAdminConnection: ParticipantAdminConnection

  val vetting = new PackageVetting(
    packages,
    context.clock,
    participantAdminConnection,
    loggerFactory,
    latestPackagesOnly,
  )

  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] = {
    for {
      domainId <- getSynchronizerId()
      amuletRules <- getAmuletRules()
      voteRequests <- getVoteRequests()
      dsoRules <- getDsoRules()
      _ <- runIfInputChanged(
        Seq(
          domainId.toString,
          amuletRules.contractId.toString,
          dsoRules.contractId.toString,
        ) ++ voteRequests.map(_.toString)
      )(
        vetting.vetPackages(
          domainId,
          amuletRules,
          AmuletConfigSchedule.getAcceptedEffectiveVoteRequests(dsoRules, voteRequests),
          Some((context.pollingClock, maxVettingDelay)),
        )
      )
    } yield false
  }

  private def runIfInputChanged(
      input: Seq[String]
  )(run: => Future[Unit])(implicit tc: TraceContext) = {
    val previoslyRunInput = previouslyRunInputRef.get()
    if (previoslyRunInput != input.toSet) {
      logger.info(
        s"Running package vetting as the input has changed from $previoslyRunInput to $input"
      )
      run.map(_ => previouslyRunInputRef.set(input.toSet))
    } else {
      Future.unit
    }
  }
}
