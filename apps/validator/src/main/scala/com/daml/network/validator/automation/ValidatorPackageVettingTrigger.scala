// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

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
  override def getAmuletRules()(implicit tc: TraceContext) =
    scanConnection.getAmuletRules()
}

object ValidatorPackageVettingTrigger {
  val packages = Set(
    PackageIdResolver.Package.SpliceAmulet,
    PackageIdResolver.Package.SpliceAmuletNameService,
    PackageIdResolver.Package.SpliceValidatorLifecycle,
    PackageIdResolver.Package.SpliceWallet,
    PackageIdResolver.Package.SpliceWalletPayments,
  )
}
