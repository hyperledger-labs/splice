// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.pretty.Pretty
import com.digitalasset.canton.logging.pretty.Pretty.{param, prettyOfClass}
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.ShowStringSyntax
import com.digitalasset.daml.lf.data.Ref.PackageVersion
import com.digitalasset.daml.lf.language.Ast
import org.lfdecentralizedtrust.splice.environment.PackageVersionSupport.FeatureSupport

import scala.concurrent.{ExecutionContext, Future}

trait PackageVersionSupport {

  def supportsDelegatelessAutomation(parties: Seq[PartyId], now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[FeatureSupport] = {
    isDarSupported(
      parties,
      PackageIdResolver.Package.SpliceDsoGovernance,
      now,
      DarResources.dsoGovernance_0_1_13,
    )
  }

  def supportsBootstrapWithNonZeroRound(parties: Seq[PartyId], now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[FeatureSupport] = {
    isDarSupported(
      parties,
      PackageIdResolver.Package.SpliceDsoGovernance,
      now,
      DarResources.dsoGovernance_0_1_14,
    )
  }

  def supportsValidatorLicenseMetadata(parties: Seq[PartyId], now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[FeatureSupport] = {
    isDarSupported(parties, PackageIdResolver.Package.SpliceAmulet, now, DarResources.amulet_0_1_3)
  }

  def supportsValidatorLicenseActivity(parties: Seq[PartyId], now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[FeatureSupport] = {
    isDarSupported(parties, PackageIdResolver.Package.SpliceAmulet, now, DarResources.amulet_0_1_3)
  }

  def supportsPruneAmuletConfigSchedule(parties: Seq[PartyId], now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[FeatureSupport] = {
    isDarSupported(
      parties,
      PackageIdResolver.Package.SpliceDsoGovernance,
      now,
      DarResources.dsoGovernance_0_1_5,
    )
  }

  def supportsMergeDuplicatedValidatorLicense(parties: Seq[PartyId], now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[FeatureSupport] = {
    isDarSupported(
      parties,
      PackageIdResolver.Package.SpliceDsoGovernance,
      now,
      DarResources.dsoGovernance_0_1_8,
    )
  }

  def supportsLegacySequencerConfig(parties: Seq[PartyId], now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[FeatureSupport] = {
    isDarSupported(
      parties,
      PackageIdResolver.Package.SpliceDsoGovernance,
      now,
      DarResources.dsoGovernance_0_1_7,
    )
  }

  def supportsValidatorLivenessActivityRecord(parties: Seq[PartyId], now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[FeatureSupport] = {
    isDarSupported(parties, PackageIdResolver.Package.SpliceAmulet, now, DarResources.amulet_0_1_5)
  }

  def supportsDsoRulesCreateExternalPartyAmuletRules(parties: Seq[PartyId], now: CantonTimestamp)(
      implicit tc: TraceContext
  ): Future[FeatureSupport] = {
    isDarSupported(
      parties,
      PackageIdResolver.Package.SpliceDsoGovernance,
      now,
      DarResources.dsoGovernance_0_1_9,
    )
  }

  def supportsNewGovernanceFlow(parties: Seq[PartyId], now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[FeatureSupport] = {
    isDarSupported(
      parties,
      PackageIdResolver.Package.SpliceDsoGovernance,
      now,
      DarResources.dsoGovernance_0_1_11,
    )
  }

  def supportsTokenStandard(parties: Seq[PartyId], now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[FeatureSupport] = {
    isDarSupported(
      parties,
      PackageIdResolver.Package.SpliceWallet,
      now,
      // this is the first version implementing the token standard
      DarResources.wallet_0_1_9,
    )
  }

  def supportsDescriptionInTransferPreapprovals(parties: Seq[PartyId], now: CantonTimestamp)(
      implicit tc: TraceContext
  ): Future[FeatureSupport] = {
    isDarSupported(
      parties,
      PackageIdResolver.Package.SpliceWallet,
      now,
      // this is when the description field was added to transfer preapprovals
      DarResources.wallet_0_1_9,
    )
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
  ): Future[FeatureSupport]

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
  )(implicit tc: TraceContext): Future[FeatureSupport] = {
    connection
      .getSupportedPackageVersion(
        synchronizerId,
        parties,
        packageId.packageName,
        at,
      )
      .map { optionalPackageReference =>
        val isFeatureSupported = optionalPackageReference.exists { packageReference =>
          PackageVersion.assertFromString(packageReference.packageVersion) >= metadata.version
        }
        FeatureSupport(
          supported = isFeatureSupported,
          optionalPackageReference.map(_.packageId).toList,
        )
      }
  }
}

object PackageVersionSupport {

  case class FeatureSupport(
      supported: Boolean,
      packageIds: Seq[String],
  )

  object FeatureSupport {

    implicit val featureSupportPretty: Pretty[FeatureSupport] = prettyOfClass(
      param("supported", t => t.supported),
      param("prefferredPackageIds", _.packageIds.map(_.singleQuoted)),
    )
  }

  def createPackageVersionSupport(
      synchronizerId: SynchronizerId,
      connection: BaseLedgerConnection,
  )(implicit ec: ExecutionContext): PackageVersionSupport = {
    new TopologyAwarePackageVersionSupport(synchronizerId, connection)

  }
}
