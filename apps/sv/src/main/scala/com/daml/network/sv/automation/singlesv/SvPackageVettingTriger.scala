package com.daml.network.sv.automation.singlesv

import com.daml.network.automation.{PackageVettingTrigger, TriggerContext}
import com.daml.network.environment.{PackageIdResolver, ParticipantAdminConnection}
import com.daml.network.sv.store.SvSvcStore
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContext

class SvPackageVettingTrigger(
    override protected val participantAdminConnection: ParticipantAdminConnection,
    store: SvSvcStore,
    override protected val prevetDuration: NonNegativeFiniteDuration,
    override protected val context: TriggerContext,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PackageVettingTrigger {
  override def getCoinRulesPayload()(implicit tc: TraceContext) =
    store.getCoinRulesPayload()

  override protected val packages = Set(
    PackageIdResolver.Package.SvcGovernance,
    PackageIdResolver.Package.DirectoryService,
    PackageIdResolver.Package.ValidatorLifecycle,
  )
}
