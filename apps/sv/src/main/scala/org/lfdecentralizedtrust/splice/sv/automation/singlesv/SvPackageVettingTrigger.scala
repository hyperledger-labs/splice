// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.topology.SynchronizerId
import org.lfdecentralizedtrust.splice.automation.{PackageVettingTrigger, TriggerContext}
import org.lfdecentralizedtrust.splice.environment.{PackageIdResolver, ParticipantAdminConnection}
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{DsoRules, VoteRequest}
import org.lfdecentralizedtrust.splice.util.Contract

import scala.concurrent.{ExecutionContext, Future}

class SvPackageVettingTrigger(
    override protected val participantAdminConnection: ParticipantAdminConnection,
    store: SvDsoStore,
    override protected val context: TriggerContext,
    maxVettingDelay: NonNegativeFiniteDuration,
    latestPackagesOnly: Boolean,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PackageVettingTrigger(
      SvPackageVettingTrigger.packages,
      maxVettingDelay,
      latestPackagesOnly,
    ) {

  override def getSynchronizerId()(implicit tc: TraceContext): Future[SynchronizerId] =
    store.getDsoRules().map(_.domain)

  override def getAmuletRules()(implicit tc: TraceContext) =
    store.getAmuletRules()

  override def getVoteRequests()(implicit
      tc: TraceContext
  ): Future[Seq[Contract[VoteRequest.ContractId, VoteRequest]]] =
    store.listVoteRequests()

  override def getDsoRules()(implicit
      tc: TraceContext
  ): Future[Contract[DsoRules.ContractId, DsoRules]] =
    store.getDsoRules().map(_.contract)
}

object SvPackageVettingTrigger {
  val packages: Set[PackageIdResolver.Package] = Set(
    PackageIdResolver.Package.SpliceDsoGovernance,
    PackageIdResolver.Package.SpliceValidatorLifecycle,
  )
}
