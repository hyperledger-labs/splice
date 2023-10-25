package com.daml.network.validator.automation

import com.daml.network.automation.{PackageVettingTrigger, TriggerContext}
import com.daml.network.environment.{PackageIdResolver, ParticipantAdminConnection}
import com.daml.network.scan.admin.api.client.ScanConnection
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContext

class ValidatorPackageVettingTrigger(
    override protected val participantAdminConnection: ParticipantAdminConnection,
    scanConnection: ScanConnection,
    override protected val prevetDuration: NonNegativeFiniteDuration,
    override protected val context: TriggerContext,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PackageVettingTrigger {
  override def getCoinRulesPayload()(implicit tc: TraceContext) =
    scanConnection.getCoinRulesPayload()

  override protected val packages = Set(
    PackageIdResolver.Package.CantonCoin,
    PackageIdResolver.Package.CantonNameService,
    PackageIdResolver.Package.DirectoryService,
    PackageIdResolver.Package.ValidatorLifecycle,
    PackageIdResolver.Package.Wallet,
    PackageIdResolver.Package.WalletPayments,
  )
}
