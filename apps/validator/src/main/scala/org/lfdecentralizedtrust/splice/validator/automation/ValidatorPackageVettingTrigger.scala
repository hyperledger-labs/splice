// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.automation

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.automation.{PackageVettingTrigger, TriggerContext}
import org.lfdecentralizedtrust.splice.environment.{PackageIdResolver, ParticipantAdminConnection}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{DsoRules, VoteRequest}
import org.lfdecentralizedtrust.splice.util.Contract

import scala.concurrent.{ExecutionContext, Future}

class ValidatorPackageVettingTrigger(
    override protected val participantAdminConnection: ParticipantAdminConnection,
    scanConnection: BftScanConnection,
    override protected val context: TriggerContext,
    maxVettingDelay: NonNegativeFiniteDuration,
    latestPackagesOnly: Boolean,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PackageVettingTrigger(
      ValidatorPackageVettingTrigger.packages,
      maxVettingDelay,
      latestPackagesOnly,
    ) {

  override def getSynchronizerId()(implicit tc: TraceContext): Future[SynchronizerId] =
    scanConnection.getAmuletRulesDomain()(tc)

  override def getAmuletRules()(implicit tc: TraceContext) =
    scanConnection.getAmuletRules()

  override def getVoteRequests()(implicit
      tc: TraceContext
  ): Future[Seq[Contract[VoteRequest.ContractId, VoteRequest]]] =
    scanConnection.getVoteRequests()

  override def getDsoRules()(implicit
      tc: TraceContext
  ): Future[Contract[DsoRules.ContractId, DsoRules]] =
    scanConnection.getDsoRules()
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
