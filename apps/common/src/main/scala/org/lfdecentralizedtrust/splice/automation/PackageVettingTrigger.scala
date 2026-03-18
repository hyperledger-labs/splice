// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyTransactionType.AuthorizedState
import org.lfdecentralizedtrust.splice.environment.{PackageIdResolver, ParticipantAdminConnection}
import org.lfdecentralizedtrust.splice.util.{AmuletConfigSchedule, DarResourcesUtil, PackageVetting}

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future

abstract class PackageVettingTrigger(
    packages: Set[PackageIdResolver.Package],
    maxVettingDelay: NonNegativeFiniteDuration,
    latestPackagesOnly: Boolean,
    enableUnvetting: Boolean,
    enableUnsupportedDarsUnvetting: Boolean,
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
    enableUnsupportedDarsUnvetting,
  )

  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] = {
    for {
      // ensure to vet new package versions
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
      // ensure that unsupported versions are not vetted
      participantId <- participantAdminConnection.getParticipantId()
      vettedPackages <- participantAdminConnection.listVettedPackages(
        participantId,
        domainId,
        AuthorizedState,
      )
      unsupportedPackages = DarResourcesUtil.filterUnsupportedPackageVersions(
        vettedPackages.flatMap(_.mapping.packages).map(_.packageId)
      )
      isUnvettingEnable =
        unsupportedPackages.nonEmpty && enableUnvetting && enableUnsupportedDarsUnvetting
      // See https://github.com/DACH-NY/canton/issues/29834: make it work for non-sv validators as well
      _ = if (isUnvettingEnable) {
        vetting.unvetPackages(
          domainId,
          unsupportedPackages,
          Some((context.pollingClock, maxVettingDelay)),
        )
      }
    } yield false
  }

  private def runIfInputChanged(
      input: Seq[String]
  )(run: => Future[Unit])(implicit tc: TraceContext) = {
    val previouslyRunInput = previouslyRunInputRef.get()
    if (previouslyRunInput != input.toSet) {
      logger.info(
        s"Running package vetting as the input has changed from $previouslyRunInput to $input"
      )
      run.map(_ => previouslyRunInputRef.set(input.toSet))
    } else {
      Future.unit
    }
  }
}
