package com.daml.network.automation

import com.daml.network.environment.{PackageIdResolver, ParticipantAdminConnection}
import com.daml.network.util.PackageVetting
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.Future

abstract class PackageVettingTrigger(packages: Set[PackageIdResolver.Package])
    extends PollingTrigger
    with PackageIdResolver.HasCoinRules {

  protected def participantAdminConnection: ParticipantAdminConnection

  // Duration that packages will be pre-vetted by. E.g.,
  // if this is set to 5 minutes packages will be vetted
  // 5 minutes before the switch in CoinConfig.
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
      coinRules <- getCoinRules()
      _ <- vetting.vetPackages(coinRules)
    } yield false
  }
}
