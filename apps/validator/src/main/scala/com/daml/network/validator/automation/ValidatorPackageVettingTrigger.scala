package com.daml.network.validator.automation

import com.daml.network.automation.{PackageVettingTrigger, TriggerContext}
import com.daml.network.environment.{PackageIdResolver, ParticipantAdminConnection}
import com.daml.network.scan.admin.api.client.BftScanConnection
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContext

class ValidatorPackageVettingTrigger(
    override protected val participantAdminConnection: ParticipantAdminConnection,
    scanConnection: BftScanConnection,
    override protected val prevetDuration: NonNegativeFiniteDuration,
    override protected val context: TriggerContext,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PackageVettingTrigger(ValidatorPackageVettingTrigger.packages) {
  override def getCoinRules()(implicit tc: TraceContext) =
    scanConnection.getCoinRules()
}

object ValidatorPackageVettingTrigger {
  val packages = Set(
    PackageIdResolver.Package.CantonCoin,
    PackageIdResolver.Package.CantonNameService,
    PackageIdResolver.Package.ValidatorLifecycle,
    PackageIdResolver.Package.Wallet,
    PackageIdResolver.Package.WalletPayments,
  )
}
