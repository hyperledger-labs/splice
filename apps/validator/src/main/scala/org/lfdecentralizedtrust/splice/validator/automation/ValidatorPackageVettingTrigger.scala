// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.automation

import com.digitalasset.canton.topology.DomainId
import org.lfdecentralizedtrust.splice.automation.{PackageVettingTrigger, TriggerContext}
import org.lfdecentralizedtrust.splice.environment.{PackageIdResolver, ParticipantAdminConnection}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class ValidatorPackageVettingTrigger(
    override protected val participantAdminConnection: ParticipantAdminConnection,
    scanConnection: BftScanConnection,
    override protected val context: TriggerContext,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PackageVettingTrigger(ValidatorPackageVettingTrigger.packages) {

  override def getDomainId()(implicit tc: TraceContext): Future[DomainId] =
    scanConnection.getAmuletRulesDomain()(tc)

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
