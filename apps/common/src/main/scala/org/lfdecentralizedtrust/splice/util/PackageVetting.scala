// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import cats.syntax.foldable.*
import cats.syntax.traverse.*
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.{AmuletConfig, USD}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.VoteRequest
import org.lfdecentralizedtrust.splice.environment.*

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

class PackageVetting(
    packages: Set[PackageIdResolver.Package],
    prevetDuration: NonNegativeFiniteDuration,
    clock: Clock,
    participantAdminConnection: ParticipantAdminConnection,
    override val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends NamedLogging {

  def vetPackages(
      amuletRules: Contract[AmuletRules.ContractId, AmuletRules],
      futureAmuletConfigFromVoteRequests: Seq[(Option[Instant], AmuletConfig[USD])],
  )(implicit tc: TraceContext): Future[Unit] = {
    val schedule = AmuletConfigSchedule(amuletRules)
    val now = clock.now.plus(prevetDuration.asJava)
    val currentConfig = schedule.getConfigAsOf(now)
    for {
      _ <- vetUpToCurrentConfig(currentConfig)
      // TODO(#16139): eventually retire this part as futureConfigs from schedule will always be empty
      _ <- schedule.futureConfigs.traverse_ { case (time, config) =>
        val timestamp = CantonTimestamp.assertFromInstant(time)
        if (timestamp > now) {
          warnIfFutureConfigUnknown(Some(timestamp), config)
        } else {
          Future.unit
        }
      }
      _ <- futureAmuletConfigFromVoteRequests.traverse_ { case (time, config) =>
        warnIfFutureConfigUnknown(time.map(CantonTimestamp.assertFromInstant), config)
      }
    } yield ()
  }

  // Future configs must not be vetted yet but we warn if the app does not know about a package version since this indicates
  // you must upgrade soon.
  private def warnIfFutureConfigUnknown(
      time: Option[CantonTimestamp],
      config: splice.amuletconfig.AmuletConfig[splice.amuletconfig.USD],
  )(implicit tc: TraceContext): Future[Unit] =
    packages.toSeq.traverse_ { pkg =>
      val version = PackageIdResolver.readPackageVersion(config.packageConfig, pkg)
      val darResource = DarResources.lookupPackageMetadata(pkg.packageName, version)
      if (darResource.isEmpty) {
        logger.warn(
          show"Package ${pkg.packageName} is required in version ${version.toString} after $time according to AmuletConfig but this version is not part of the deployed release, upgrade before $time to avoid any issues"
        )
      }
      Future.unit
    }

  // The current config must be vetted.
  private def vetUpToCurrentConfig(
      config: splice.amuletconfig.AmuletConfig[splice.amuletconfig.USD]
  )(implicit tc: TraceContext): Future[Unit] = {
    packages.toSeq.traverse_ { pkg =>
      val version = PackageIdResolver.readPackageVersion(config.packageConfig, pkg)
      for {
        // Upload the version required by current config, and log an error if it is not part of the deployed release
        _ <- DarResources.lookupPackageMetadata(pkg.packageName, version) match {
          case None =>
            logger.error(
              show"Package ${pkg.packageName} is required in version ${version.toString} according to AmuletConfig but this version is not part of the deployed release, upgrade immediately to avoid any issues"
            )
            Future.unit
          case Some(resource) => uploadDar(resource)
        }
        // upload all earlier versions of the same package
        _ <- DarResources
          .lookupAllPackageVersions(pkg.packageName)
          .filter(_.metadata.version < version)
          .map(uploadDar(_))
          .sequence
      } yield ()
    }
  }

  private def uploadDar(resource: DarResource)(implicit tc: TraceContext): Future[Unit] =
    for {
      // While uploadDarFile is idempotent the logs are fairly noisy (which is useful in other places)
      // so we do an explicit check here to only upload if it's not already there.
      darO <- participantAdminConnection.lookupDar(resource.darHash)
      _ <- darO match {
        case None =>
          participantAdminConnection.uploadDarFile(
            UploadablePackage.fromResource(resource),
            RetryFor.Automation,
          )
        case Some(_) => Future.unit
      }
    } yield ()
}

object PackageVetting {
  trait HasVoteRequests {

    def getVoteRequests()(implicit
        tc: TraceContext
    ): Future[Seq[Contract[VoteRequest.ContractId, VoteRequest]]]
  }
}
