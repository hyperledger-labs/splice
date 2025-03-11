// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv

import com.digitalasset.canton.topology.SynchronizerId
import org.lfdecentralizedtrust.splice.automation.{PackageVettingTrigger, TriggerContext}
import org.lfdecentralizedtrust.splice.environment.{PackageIdResolver, ParticipantAdminConnection}
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class SvPackageVettingTrigger(
    override protected val participantAdminConnection: ParticipantAdminConnection,
    store: SvDsoStore,
    override protected val context: TriggerContext,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PackageVettingTrigger(SvPackageVettingTrigger.packages) {

  override def getSynchronizerId()(implicit tc: TraceContext): Future[SynchronizerId] =
    store.getDsoRules().map(_.domain)

  override def getAmuletRules()(implicit tc: TraceContext) =
    store.getAmuletRules()
}

object SvPackageVettingTrigger {
  val packages: Set[PackageIdResolver.Package] = Set(
    PackageIdResolver.Package.SpliceDsoGovernance,
    PackageIdResolver.Package.SpliceValidatorLifecycle,
  )
}
