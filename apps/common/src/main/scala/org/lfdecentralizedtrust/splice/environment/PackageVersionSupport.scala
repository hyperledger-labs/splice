// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.daml.lf.data.Ref.PackageVersion
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules
import org.lfdecentralizedtrust.splice.environment.PackageIdResolver.HasAmuletRules
import org.lfdecentralizedtrust.splice.util.AmuletConfigSchedule

import scala.concurrent.{ExecutionContext, Future}

trait PackageVersionSupport {

  def supportsValidatorLicenseMetadata(now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[Boolean]

  def supportsValidatorLicenseActivity(now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[Boolean]

  def supportsPruneAmuletConfigSchedule(now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[Boolean]

  def supportsMergeDuplicatedValidatorLicense(now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[Boolean]

  def supportsLegacySequencerConfig(now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[Boolean]

  def supportsValidatorLivenessActivityRecord(now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[Boolean]

  def supportsExternalPartyAmuletRules(now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[Boolean]

}

class AmuletRulesPackageVersionSupport(amuletRules: HasAmuletRules)(implicit
    ec: ExecutionContext
) extends PackageVersionSupport {

  override def supportsValidatorLicenseMetadata(
      now: CantonTimestamp
  )(implicit tc: TraceContext): Future[Boolean] = {
    basedOnCurrentAmuletRules(supportsValidatorLicenseMetadata(now, _))
  }

  override def supportsValidatorLicenseActivity(now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[Boolean] =
    basedOnCurrentAmuletRules(
      supportsValidatorLicenseActivity(now, _)
    )

  override def supportsPruneAmuletConfigSchedule(now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[Boolean] =
    basedOnCurrentAmuletRules(
      supportsPruneAmuletConfigSchedule(now, _)
    )

  override def supportsMergeDuplicatedValidatorLicense(now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[Boolean] =
    basedOnCurrentAmuletRules(
      supportsMergeDuplicatedValidatorLicense(now, _)
    )

  override def supportsLegacySequencerConfig(now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[Boolean] =
    basedOnCurrentAmuletRules(
      supportsLegacySequencerConfig(now, _)
    )

  override def supportsValidatorLivenessActivityRecord(now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[Boolean] =
    basedOnCurrentAmuletRules(
      supportsValidatorLivenessActivityRecord(now, _)
    )

  override def supportsExternalPartyAmuletRules(now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[Boolean] =
    basedOnCurrentAmuletRules(
      supportsExternalPartyAmuletRules(now, _)
    )
  private def basedOnCurrentAmuletRules(
      condition: AmuletRules => Boolean
  )(implicit tc: TraceContext): Future[Boolean] = {
    amuletRules.getAmuletRules().map(amuletRules => condition(amuletRules.payload))
  }

  private def supportsValidatorLicenseMetadata(
      now: CantonTimestamp,
      amuletRules: AmuletRules,
  ): Boolean = {
    val currentConfig = AmuletConfigSchedule(amuletRules).getConfigAsOf(now)
    val spliceAmuletVersion = PackageVersion.assertFromString(currentConfig.packageConfig.amulet)
    spliceAmuletVersion >= DarResources.amulet_0_1_3.metadata.version
  }

  private def supportsValidatorLicenseActivity(
      now: CantonTimestamp,
      amuletRules: AmuletRules,
  ): Boolean =
    supportsValidatorLicenseMetadata(now, amuletRules)

  private def supportsPruneAmuletConfigSchedule(
      now: CantonTimestamp,
      amuletRules: AmuletRules,
  ): Boolean = {
    val currentConfig = AmuletConfigSchedule(amuletRules).getConfigAsOf(now)
    val dsoGovernanceVersion =
      PackageVersion.assertFromString(currentConfig.packageConfig.dsoGovernance)
    dsoGovernanceVersion >= DarResources.dsoGovernance_0_1_5.metadata.version
  }

  private def supportsMergeDuplicatedValidatorLicense(
      now: CantonTimestamp,
      amuletRules: AmuletRules,
  ): Boolean = {
    val currentConfig = AmuletConfigSchedule(amuletRules).getConfigAsOf(now)
    val dsoGovernanceVersion =
      PackageVersion.assertFromString(currentConfig.packageConfig.dsoGovernance)
    dsoGovernanceVersion >= DarResources.dsoGovernance_0_1_8.metadata.version
  }

  private def supportsLegacySequencerConfig(
      now: CantonTimestamp,
      amuletRules: AmuletRules,
  ): Boolean = {
    val currentConfig = AmuletConfigSchedule(amuletRules).getConfigAsOf(now)
    val dsoGovernanceVersion =
      PackageVersion.assertFromString(currentConfig.packageConfig.dsoGovernance)
    dsoGovernanceVersion >= DarResources.dsoGovernance_0_1_7.metadata.version
  }

  private def supportsValidatorLivenessActivityRecord(
      now: CantonTimestamp,
      amuletRules: AmuletRules,
  ): Boolean = {
    val currentConfig = AmuletConfigSchedule(amuletRules).getConfigAsOf(now)
    val amuletVersion =
      PackageVersion.assertFromString(currentConfig.packageConfig.amulet)
    amuletVersion >= DarResources.amulet_0_1_5.metadata.version
  }

  private def supportsExternalPartyAmuletRules(
      now: CantonTimestamp,
      amuletRules: AmuletRules,
  ): Boolean = {
    val currentConfig = AmuletConfigSchedule(amuletRules).getConfigAsOf(now)
    val amuletVersion =
      PackageVersion.assertFromString(currentConfig.packageConfig.amulet)
    amuletVersion >= DarResources.amulet_0_1_6.metadata.version
  }
}
