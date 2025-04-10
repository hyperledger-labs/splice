// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.daml.lf.data.Ref.PackageVersion
import com.digitalasset.daml.lf.language.Ast
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules
import org.lfdecentralizedtrust.splice.environment.PackageIdResolver.HasAmuletRules
import org.lfdecentralizedtrust.splice.util.AmuletConfigSchedule

import scala.concurrent.{ExecutionContext, Future}

trait PackageVersionSupport {

  def supportsValidatorLicenseMetadata(parties: Seq[PartyId], now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[Boolean] = {
    isDarSupported(parties, PackageIdResolver.Package.SpliceAmulet, now, DarResources.amulet_0_1_3)
  }

  def supportsValidatorLicenseActivity(parties: Seq[PartyId], now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[Boolean] = {
    isDarSupported(parties, PackageIdResolver.Package.SpliceAmulet, now, DarResources.amulet_0_1_3)
  }

  def supportsPruneAmuletConfigSchedule(parties: Seq[PartyId], now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[Boolean] = {
    isDarSupported(
      parties,
      PackageIdResolver.Package.SpliceDsoGovernance,
      now,
      DarResources.dsoGovernance_0_1_5,
    )
  }

  def supportsMergeDuplicatedValidatorLicense(parties: Seq[PartyId], now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[Boolean] = {
    isDarSupported(
      parties,
      PackageIdResolver.Package.SpliceDsoGovernance,
      now,
      DarResources.dsoGovernance_0_1_8,
    )
  }

  def supportsLegacySequencerConfig(parties: Seq[PartyId], now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[Boolean] = {
    isDarSupported(
      parties,
      PackageIdResolver.Package.SpliceDsoGovernance,
      now,
      DarResources.dsoGovernance_0_1_7,
    )
  }

  def supportsValidatorLivenessActivityRecord(parties: Seq[PartyId], now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[Boolean] = {
    isDarSupported(parties, PackageIdResolver.Package.SpliceAmulet, now, DarResources.amulet_0_1_5)
  }

  def supportsExternalPartyAmuletRules(parties: Seq[PartyId], now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[Boolean] = {
    isDarSupported(parties, PackageIdResolver.Package.SpliceAmulet, now, DarResources.amulet_0_1_6)
  }

  private def isDarSupported(
      parties: Seq[PartyId],
      packageId: PackageIdResolver.Package,
      at: CantonTimestamp,
      dar: DarResource,
  )(implicit tc: TraceContext) = {
    require(packageId.packageName == dar.metadata.name)
    require(parties.nonEmpty)
    isPackageSupported(parties, packageId, at, dar.metadata)
  }

  def isPackageSupported(
      parties: Seq[PartyId],
      packageId: PackageIdResolver.Package,
      at: CantonTimestamp,
      metadata: Ast.PackageMetadata,
  )(implicit
      tc: TraceContext
  ): Future[Boolean]

}

class AmuletRulesPackageVersionSupport private[environment] (amuletRules: HasAmuletRules)(implicit
    ec: ExecutionContext
) extends PackageVersionSupport {

  override def isPackageSupported(
      parties: Seq[PartyId],
      packageId: PackageIdResolver.Package,
      at: CantonTimestamp,
      metadata: Ast.PackageMetadata,
  )(implicit tc: TraceContext): Future[Boolean] = {
    basedOnCurrentAmuletRules { amuletRules =>
      val packageVersion = metadata.version
      val packageConfig = AmuletConfigSchedule(amuletRules).getConfigAsOf(at).packageConfig
      val activeVersion = PackageIdResolver.readPackageVersion(packageConfig, packageId)
      packageVersion >= activeVersion
    }
  }

  private def basedOnCurrentAmuletRules(
      condition: AmuletRules => Boolean
  )(implicit tc: TraceContext): Future[Boolean] = {
    amuletRules.getAmuletRules().map(amuletRules => condition(amuletRules.payload))
  }

}

class TopologyAwarePackageVersionSupport private[environment] (
    synchronizerId: SynchronizerId,
    connection: BaseLedgerConnection,
)(implicit ec: ExecutionContext)
    extends PackageVersionSupport {

  override def isPackageSupported(
      parties: Seq[PartyId],
      packageId: PackageIdResolver.Package,
      at: CantonTimestamp,
      metadata: Ast.PackageMetadata,
  )(implicit tc: TraceContext): Future[Boolean] = {
    connection
      .getSupportedPackageVersion(
        synchronizerId,
        parties,
        packageId,
        at,
      )
      .map(
        _.exists { packageReference =>
          PackageVersion.assertFromString(packageReference.packageVersion) >= metadata.version
        }
      )
  }
}

object PackageVersionSupport {

  def createPackageVersionSupport(
      enableCantonPackageSelection: Boolean,
      amuletRules: HasAmuletRules,
      synchronizerId: SynchronizerId,
      connection: BaseLedgerConnection,
  )(implicit ec: ExecutionContext): PackageVersionSupport = {
    if (enableCantonPackageSelection) {
      new TopologyAwarePackageVersionSupport(synchronizerId, connection)
    } else {
      new AmuletRulesPackageVersionSupport(amuletRules)
    }
  }

}
