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

  def supportsValidatorLicenseActivity(parties: Seq[PartyId], now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[FeatureSupport] = {
    isDarSupported(parties, PackageIdResolver.Package.SpliceAmulet, now, DarResources.amulet_0_1_3)
  }

  def supportsMergeDuplicatedValidatorLicense(
      dsoGovernanceParties: Seq[PartyId],
      amuletParties: Seq[PartyId],
      now: CantonTimestamp,
  )(implicit
      tc: TraceContext
  ): Future[FeatureSupport] = {
    isDarSupported(
      Seq(
        (PackageIdResolver.Package.SpliceDsoGovernance -> dsoGovernanceParties),
        (PackageIdResolver.Package.SpliceAmulet -> amuletParties),
      ),
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

  def supportsExpectedDsoParty(parties: Seq[PartyId], now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[FeatureSupport] = {
    isDarSupported(
      parties,
      PackageIdResolver.Package.SpliceAmulet,
      now,
      // this is when the expectedDsoParty was added to all choices granted to users on AmuletRules and ExternalAmuletRules
      DarResources.amulet_0_1_11,
    )
  }

  private def isDarSupported(
      parties: Seq[PartyId],
      packageId: PackageIdResolver.Package,
      at: CantonTimestamp,
      dar: DarResource,
  )(implicit tc: TraceContext): Future[FeatureSupport] =
    isDarSupported(Seq(packageId -> parties), at, dar)

  private def isDarSupported(
      packageRequirements: Seq[(PackageIdResolver.Package, Seq[PartyId])],
      at: CantonTimestamp,
      dar: DarResource,
  )(implicit tc: TraceContext): Future[FeatureSupport] = {
    require(packageRequirements.exists(_._1.packageName == dar.metadata.name))
    require(packageRequirements.forall(_._2.nonEmpty))
    isPackageSupported(packageRequirements, at, dar.metadata)
  }

  def isPackageSupported(
      packageRequirements: Seq[(PackageIdResolver.Package, Seq[PartyId])],
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
      packageRequirements: Seq[(PackageIdResolver.Package, Seq[PartyId])],
      at: CantonTimestamp,
      metadata: Ast.PackageMetadata,
  )(implicit tc: TraceContext): Future[FeatureSupport] = {
    connection
      .getSupportedPackageVersion(
        synchronizerId,
        packageRequirements.map({ case (k, v) => k.packageName -> v }),
        at,
      )
      .map { packageReferences =>
        val isFeatureSupported = packageReferences.exists { packageReference =>
          PackageVersion.assertFromString(
            packageReference.packageVersion
          ) >= metadata.version && packageReference.packageName == metadata.name
        }
        FeatureSupport(
          supported = isFeatureSupported,
          packageReferences.map(_.packageId).toList,
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
