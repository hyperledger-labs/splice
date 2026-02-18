// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.logging.pretty.Pretty
import com.digitalasset.canton.logging.pretty.Pretty.{param, prettyOfClass}
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.ShowStringSyntax
import com.digitalasset.daml.lf.data.Ref.PackageVersion
import com.digitalasset.daml.lf.language.Ast
import org.lfdecentralizedtrust.splice.environment.PackageVersionSupport.FeatureSupport

import scala.concurrent.{ExecutionContext, Future}

trait PackageVersionSupport extends NamedLogging {

  def supportBootstrapWithNonZeroRound(parties: Seq[PartyId], now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[FeatureSupport] = {
    isDarSupported(
      parties,
      PackageIdResolver.Package.SpliceDsoGovernance,
      now,
      DarResources.dsoGovernance,
      DarResources.dsoGovernance_0_1_17,
    )
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
      DarResources.dsoGovernance,
      DarResources.dsoGovernance_0_1_8,
      ignoreRedundantCheck = true,
      // ValidatorLicense contracts can stick around for parties that are no longer on the network and don't upgrade beyond the minimum version.
      // Without the version check our trigger currently gets confused.
    )
  }

  // TODO(#1825): remove unused flag
  def supportsTokenStandard(parties: Seq[PartyId], now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[FeatureSupport] = {
    isDarSupported(
      parties,
      PackageIdResolver.Package.SpliceWallet,
      now,
      DarResources.wallet,
      // this is the first version implementing the token standard
      DarResources.wallet_0_1_9,
      ignoreRedundantCheck = true,
    )
  }

  // TODO(#1825): remove unused flag
  def supportsDescriptionInTransferPreapprovals(parties: Seq[PartyId], now: CantonTimestamp)(
      implicit tc: TraceContext
  ): Future[FeatureSupport] = {
    isDarSupported(
      parties,
      PackageIdResolver.Package.SpliceWallet,
      now,
      DarResources.wallet,
      // this is when the description field was added to transfer preapprovals
      DarResources.wallet_0_1_9,
      ignoreRedundantCheck = true,
    )
  }

  // TODO(#2257): remove this flag once the holding fees change has been rolled out to MainNet
  def noHoldingFeesOnTransfers(dsoParty: PartyId, now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[FeatureSupport] = {
    isDarSupported(
      Seq(dsoParty),
      PackageIdResolver.Package.SpliceAmulet,
      now,
      DarResources.amulet,
      // This is when the AmuletRules transfer choice was changed to not charge holding fees
      DarResources.amulet_0_1_14,
    )
  }

  def supportsExpectedDsoParty(parties: Seq[PartyId], now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[FeatureSupport] = {
    isDarSupported(
      parties,
      PackageIdResolver.Package.SpliceAmulet,
      now,
      DarResources.amulet,
      // this is when the expectedDsoParty was added to all choices granted to users on AmuletRules and ExternalAmuletRules
      DarResources.amulet_0_1_11,
    )
  }

  def supportDevelopmentFund(parties: Seq[PartyId], now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[FeatureSupport] = {
    isDarSupported(
      parties,
      PackageIdResolver.Package.SpliceDsoGovernance,
      now,
      DarResources.dsoGovernance,
      DarResources.dsoGovernance_0_1_21,
    )
  }

  def supportsConvertFeaturedAppActivityMarkerObservers(
      parties: Seq[PartyId],
      now: CantonTimestamp,
  )(implicit tc: TraceContext): Future[FeatureSupport] =
    isDarSupported(
      parties,
      PackageIdResolver.Package.SpliceAmulet,
      now,
      DarResources.amulet,
      DarResources.amulet_0_1_16,
    )

  private def isDarSupported(
      parties: Seq[PartyId],
      packageId: PackageIdResolver.Package,
      at: CantonTimestamp,
      packageResource: PackageResource,
      dar: DarResource,
      ignoreRedundantCheck: Boolean = false,
  )(implicit tc: TraceContext): Future[FeatureSupport] =
    isDarSupported(Seq(packageId -> parties), at, packageResource, dar, ignoreRedundantCheck)

  private def isDarSupported(
      packageRequirements: Seq[(PackageIdResolver.Package, Seq[PartyId])],
      at: CantonTimestamp,
      packageResource: PackageResource,
      dar: DarResource,
      ignoreRedundantCheck: Boolean,
  )(implicit tc: TraceContext): Future[FeatureSupport] = {
    require(packageRequirements.exists(_._1.packageName == dar.metadata.name))
    require(packageRequirements.forall(_._2.nonEmpty))
    require(packageResource.minimumInitialization.metadata.name == dar.metadata.name)
    if (
      !ignoreRedundantCheck && packageResource.minimumInitialization.metadata.version >= dar.metadata.version
    ) {
      logger.warn(
        s"Package version check for ${dar.metadata.name} at version ${dar.metadata.version} is redundant, minimum version: ${packageResource.minimumInitialization.metadata.version}"
      )
    }
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
    protected val loggerFactory: NamedLoggerFactory,
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
      loggerFactory: NamedLoggerFactory,
  )(implicit ec: ExecutionContext): PackageVersionSupport = {
    new TopologyAwarePackageVersionSupport(synchronizerId, connection, loggerFactory)

  }
}
