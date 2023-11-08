package com.daml.network.automation

import cats.syntax.foldable.*
import com.daml.network.codegen.java.cc
import com.daml.network.environment.{
  DarResource,
  PackageIdResolver,
  ParticipantAdminConnection,
  RetryFor,
}
import com.daml.network.util.{CoinConfigSchedule, UploadablePackage}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*

import scala.concurrent.Future

abstract class PackageVettingTrigger
    extends PollingTrigger
    with PackageIdResolver.HasCoinRulesPayload {

  protected def participantAdminConnection: ParticipantAdminConnection

  protected def packages: Set[PackageIdResolver.Package]

  // Duration that packages will be pre-vetted by. E.g.,
  // if this is set to 5 minutes packages will be vetted
  // 5 minutes before the switch in CoinConfig.
  protected def prevetDuration: NonNegativeFiniteDuration

  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] =
    for {
      coinRules <- getCoinRulesPayload()
      schedule = CoinConfigSchedule(coinRules)
      now = context.clock.now.plus(prevetDuration.asJava)
      currentConfig = schedule.getConfigAsOf(now)
      _ <- vetCurrentConfig(currentConfig)
      _ <- schedule.futureConfigs.traverse_ { case (time, config) =>
        val timestamp = CantonTimestamp.assertFromInstant(time)
        if (timestamp > now) {
          warnIfFutureConfigUnknown(timestamp, config)
        } else {
          Future.unit
        }
      }
    } yield false

  // The current config must be vetted.
  private def vetCurrentConfig(
      config: cc.coinconfig.CoinConfig[cc.coinconfig.USD]
  )(implicit tc: TraceContext): Future[Unit] =
    packages.toSeq.traverse_ { pkg =>
      val version = PackageIdResolver.readPackageVersion(config.packageConfig, pkg)
      val darResource = PackageIdResolver.lookupPackage(pkg, version)
      darResource match {
        case None =>
          logger.error(
            s"Package ${pkg.packageName} is required in version ${version} according to CoinConfig but this version is not part of the deployed release, upgrade immediately to avoid any issues"
          )
          Future.unit
        case Some(resource) => uploadDar(resource)
      }
    }

  // Future configs must not be vetted yet but we warn if the app does not know about a package version since this indicates
  // you must upgrade soon.
  private def warnIfFutureConfigUnknown(
      time: CantonTimestamp,
      config: cc.coinconfig.CoinConfig[cc.coinconfig.USD],
  )(implicit tc: TraceContext): Future[Unit] =
    packages.toSeq.traverse_ { pkg =>
      val version = PackageIdResolver.readPackageVersion(config.packageConfig, pkg)
      val darResource = PackageIdResolver.lookupPackage(pkg, version)
      if (darResource.isEmpty) {
        logger.warn(
          show"Package ${pkg.packageName} is required in version ${version} after $time according to CoinConfig but this version is not part of the deployed release, upgrade before $time to avoid any issues"
        )
      }
      Future.unit
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
