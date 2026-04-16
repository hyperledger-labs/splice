// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.daml.lf.data.Ref.{PackageName, PackageVersion}
import org.lfdecentralizedtrust.splice.environment.{PackageIdResolver, ParticipantAdminConnection}
import org.lfdecentralizedtrust.splice.util.{AmuletConfigSchedule, PackageVetting}

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future

abstract class PackageVettingTrigger(
    packages: Set[PackageIdResolver.Package],
    maxVettingDelay: NonNegativeFiniteDuration,
    latestPackagesOnly: Boolean,
    enableUnvetting: Boolean,
    enableUnsupportedDarsUnvetting: Boolean,
    additionalPackagesToUnvet: Map[PackageName, Set[PackageVersion]],
) extends PollingTrigger
    with PackageIdResolver.HasAmuletRules
    with PackageVetting.HasVoteRequests {

  private val previouslyRunInputRefForVetting = new AtomicReference[Set[String]](Set.empty)
  private val previouslyRunInputRefForUnvetting = new AtomicReference[Set[String]](Set.empty)

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
      newAmuletConfigVoteRequests = AmuletConfigSchedule.getAcceptedEffectiveVoteRequests(
        dsoRules,
        voteRequests,
      )
      _ <- runIfInputChanged(
        Seq(
          domainId.toString,
          amuletRules.contractId.toString,
          dsoRules.contractId.toString,
        ) ++ voteRequests.map(_.toString) ++ additionalPackagesToUnvet.map(_.toString()),
        previouslyRunInputRefForVetting,
        "vetting",
      )(
        vetting.vetPackages(
          domainId,
          amuletRules,
          newAmuletConfigVoteRequests,
          Some((context.pollingClock, maxVettingDelay)),
          additionalPackagesToUnvet,
        )
      )
      // ensure that unsupported versions are not vetted
      _ <- runIfInputChanged(
        Seq(
          amuletRules.payload.configSchedule.initialValue.packageConfig.toString
        ) ++ additionalPackagesToUnvet.map(_.toString()),
        previouslyRunInputRefForUnvetting,
        "unvetting",
        enabled = enableUnvetting && enableUnsupportedDarsUnvetting,
      )(
        vetting.unvetPackages(
          domainId,
          additionalPackagesToUnvet,
          amuletRules.payload.configSchedule.initialValue.packageConfig,
          Some((context.pollingClock, maxVettingDelay)),
        )
      )
    } yield false
  }

  private def runIfInputChanged(
      input: Seq[String],
      reference: AtomicReference[Set[String]],
      keyword: String,
      enabled: Boolean = true,
  )(run: => Future[Unit])(implicit tc: TraceContext) = {
    val previouslyRunInput = reference.get()
    if (previouslyRunInput != input.toSet && enabled) {
      logger.info(
        s"Running package $keyword as the input has changed from $previouslyRunInput to $input"
      )
      run.map(_ => reference.set(input.toSet))
    } else {
      logger.debug(
        s"Not running package $keyword as the input has not changed from $previouslyRunInput or the mechanism is disabled by configuration."
      )
      Future.unit
    }
  }

}
