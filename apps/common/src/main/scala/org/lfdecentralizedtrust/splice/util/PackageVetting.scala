// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.digitalasset.daml.lf.data.Ref.{IdString, PackageVersion}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.util.MonadUtil
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.{AmuletConfig, USD}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{DsoRules, VoteRequest}
import org.lfdecentralizedtrust.splice.environment.*

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

class PackageVetting(
    packages: Set[PackageIdResolver.Package],
    clock: Clock,
    participantAdminConnection: ParticipantAdminConnection,
    override val loggerFactory: NamedLoggerFactory,
    latestPackagesOnly: Boolean,
)(implicit ec: ExecutionContext, tracer: Tracer)
    extends NamedLogging
    with Spanning {

  def vetCurrentPackages(
      domainId: SynchronizerId,
      amuletRules: Contract[AmuletRules.ContractId, AmuletRules],
  )(implicit tc: TraceContext): Future[Unit] = {
    val schedule = AmuletConfigSchedule(amuletRules)
    val currentPackageConfig = schedule.getConfigAsOf(clock.now).packageConfig
    val currentRequiredPackages =
      packages.map(pkg => pkg -> PackageIdResolver.readPackageVersion(currentPackageConfig, pkg))
    val packagesToVet = currentRequiredPackages.toSeq.flatMap { case (pkg, packageVersion) =>
      DarResources
        .lookupAllPackageVersions(pkg.packageName)
        .filter(_.metadata.version <= packageVersion)
        .map(versionToVet => pkg -> versionToVet.metadata.version)
    // Stores filter by interfaces contained in this package, including the interface id in the GetUpdates request.
    // Said request will fail if the package is not present. Thus, we upload and vet all token standard packages.
    // Since interfaces are not upgradeable, there's no gain in coordinating it via package config.
    // An interface itself also does nothing, only the implementations do, so it's OK from a vetting perspective.
    } ++ Seq(
      PackageIdResolver.Package.TokenStandard.SpliceApiTokenMetadataV1,
      PackageIdResolver.Package.TokenStandard.SpliceApiTokenHoldingV1,
      PackageIdResolver.Package.TokenStandard.SpliceApiTokenTransferInstructionV1,
      PackageIdResolver.Package.TokenStandard.SpliceApiTokenAllocationV1,
      PackageIdResolver.Package.TokenStandard.SpliceApiTokenAllocationRequestV1,
      PackageIdResolver.Package.TokenStandard.SpliceApiTokenAllocationInstructionV1,
    ).map(pkg => pkg -> PackageIdResolver.readPackageVersion(currentPackageConfig, pkg)) ++
      DarResources.batchedMarkers.all.map(pkg =>
        PackageIdResolver.Package.SpliceUtilBatchedMarkers -> pkg.metadata.version
      )

    vetPackages(
      domainId,
      packagesToVet,
      None,
      maxVettingDelay = None,
    )
  }

  // Adjust max vetting delay to not be longer than the validFrom date.
  private def adjustMaxVettingDelay(
      validFrom: Instant,
      maxVettingDelay: (Clock, NonNegativeFiniteDuration),
  ): (Clock, NonNegativeFiniteDuration) = {
    val (clock, maxDelay) = maxVettingDelay
    val validFromDelayNanos =
      Math.max(0, java.time.Duration.between(clock.now.toInstant, validFrom).toNanos)
    (
      clock,
      NonNegativeFiniteDuration.tryFromJavaDuration(
        java.time.Duration.ofNanos(Math.min(validFromDelayNanos, maxDelay.asJava.toNanos))
      ),
    )
  }

  def vetPackages(
      domainId: SynchronizerId,
      amuletRules: Contract[AmuletRules.ContractId, AmuletRules],
      futureAmuletConfigFromVoteRequests: Seq[(Option[Instant], AmuletConfig[USD])],
      maxVettingDelay: Option[(Clock, NonNegativeFiniteDuration)],
  )(implicit tc: TraceContext): Future[Unit] = {
    val schedule = AmuletConfigSchedule(amuletRules)
    val vettingSchedule =
      associatePackageVersionsByEarliestVettingDate(
        amuletRules.createdAt,
        schedule,
        futureAmuletConfigFromVoteRequests,
      )
    // sort them and vet in the order of earliest first to ensure that dependencies are vetted at the earliest time as well
    // also it doesn't really make sense to run multiple vettings in parallel as they will just race to update the topology state
    val vettingTimeSortedDars = vettingSchedule.toSeq.sortBy(_._1)
    logger.info(s"Vetting for schedule $vettingTimeSortedDars from amulet rules $schedule")

    MonadUtil
      .sequentialTraverse(vettingTimeSortedDars) { case (validFrom, packages) =>
        vetPackages(
          domainId,
          packages.toSeq,
          Some(validFrom),
          maxVettingDelay.map(adjustMaxVettingDelay(validFrom, _)),
        )
      }
      .map(_ => ())
  }

  private def vetPackages(
      domainId: SynchronizerId,
      packages: Seq[(PackageIdResolver.Package, PackageVersion)],
      validFrom: Option[Instant],
      maxVettingDelay: Option[(Clock, NonNegativeFiniteDuration)],
  )(implicit tc: TraceContext): Future[Unit] = {
    val packagesToProcess = if (latestPackagesOnly) {
      packages
        .foldLeft(Map.empty[IdString.PackageName, (PackageIdResolver.Package, PackageVersion)]) {
          case (acc, (pkg, version)) =>
            acc.get(pkg.packageName) match {
              case Some((_, existingVersion)) =>
                if (version > existingVersion) acc.updated(pkg.packageName, (pkg, version))
                else acc
              case None => acc.updated(pkg.packageName, (pkg, version))
            }

        }
        .values
        .toSeq
    } else packages
    if (latestPackagesOnly) {
      logger.debug(
        s"Vetting only the latest packages: ${packagesToProcess.mkString(", ")} on $domainId valid from $validFrom"
      )
    } else {
      logger.debug(
        s"Vetting packages: ${packages.mkString(", ")} on $domainId valid from $validFrom"
      )
    }
    val resources = packagesToProcess.flatMap { case (pkg, packageVersion) =>
      // Upload the version required by current config, and log an error if it is not part of the deployed release
      DarResources.lookupPackageMetadata(pkg.packageName, packageVersion) match {
        case None =>
          validFrom match {
            case Some(time) =>
              logger.warn(
                show"Package ${pkg.packageName} is required in version ${packageVersion.toString()} after $time according to AmuletConfig but this version is not part of the deployed release, upgrade before $time to avoid any issues"
              )
            case None =>
              logger.error(
                show"Package ${pkg.packageName} is required in version ${packageVersion.toString()} according to AmuletConfig but this version is not part of the deployed release, upgrade immediately to avoid any issues"
              )
          }
          None
        case valid =>
          valid
      }
    }
    uploadDarsAndVet(
      domainId = domainId,
      validFrom = validFrom,
      resources,
      maxVettingDelay,
    )
  }

  private def uploadDarsAndVet(
      domainId: SynchronizerId,
      validFrom: Option[Instant],
      resources: Seq[DarResource],
      maxVettingDelay: Option[(Clock, NonNegativeFiniteDuration)],
  )(implicit tc: TraceContext) = {
    for {
      _ <- withSpan("upload_dars") { implicit tc => _ =>
        participantAdminConnection.uploadDarFiles(
          resources.map(
            UploadablePackage.fromResource
          ),
          RetryFor.Automation,
        )
      }
      _ <- withSpan("vet_dars") { implicit tc => _ =>
        participantAdminConnection.vetDars(
          domainId,
          resources,
          fromDate = validFrom,
          maxVettingDelay = maxVettingDelay,
        )
      }
    } yield {}
  }

  private def associatePackageVersionsByEarliestVettingDate(
      createdAt: Instant,
      amuletConfigSchedule: AmuletConfigSchedule,
      futureAmuletConfigFromVoteRequests: Seq[(Option[Instant], AmuletConfig[USD])],
  ) = {
    (futureAmuletConfigFromVoteRequests.collect { case (Some(effectiveAt), config) =>
      (effectiveAt, config)
    } ++ amuletConfigSchedule.futureConfigs :+ (createdAt -> amuletConfigSchedule.initialConfig))
      .flatMap { case (time, config) =>
        packages.flatMap { pkg =>
          val allPackageVersions =
            DarResources.lookupAllPackageVersions(pkg.packageName).map(_.metadata.version)
          val configPackageVersion = PackageIdResolver.readPackageVersion(config.packageConfig, pkg)
          allPackageVersions
            .filter(_ <= configPackageVersion)
            .map(version => time -> (pkg -> version))
        }
      }
      .groupMapReduce(_._2)(_._1) { case (time1, time2) =>
        if (time1.isBefore(time2)) time1 else time2
      }
      .groupMap(_._2)(_._1)
  }

}

object PackageVetting {
  trait HasVoteRequests {

    def getDsoRules()(implicit
        tc: TraceContext
    ): Future[Contract[DsoRules.ContractId, DsoRules]]

    def getVoteRequests()(implicit
        tc: TraceContext
    ): Future[Seq[Contract[VoteRequest.ContractId, VoteRequest]]]
  }
}
