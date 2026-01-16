// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import com.digitalasset.daml.lf.data.Ref.{PackageId, PackageName, PackageVersion}
import com.digitalasset.daml.lf.language.Ast.PackageMetadata
import org.lfdecentralizedtrust.splice.util.DarUtil
import com.digitalasset.daml.lf.archive.Dar
import com.digitalasset.daml.lf.language.Ast

import java.nio.file.Path
import scala.util.Using

object DarResources {
  object TokenStandard {
    val tokenMetadata = PackageResource(
      DarResource(s"splice-api-token-metadata-v1-current.dar"),
      DarResource(s"splice-api-token-metadata-v1-1.0.0.dar"),
      Seq(DarResource(s"splice-api-token-metadata-v1-1.0.0.dar")),
    )
    val tokenHolding = PackageResource(
      DarResource(s"splice-api-token-holding-v1-current.dar"),
      DarResource(s"splice-api-token-holding-v1-1.0.0.dar"),
      Seq(DarResource(s"splice-api-token-holding-v1-1.0.0.dar")),
    )
    val tokenTransferInstruction = PackageResource(
      DarResource(s"splice-api-token-transfer-instruction-v1-current.dar"),
      DarResource(s"splice-api-token-transfer-instruction-v1-1.0.0.dar"),
      Seq(DarResource(s"splice-api-token-transfer-instruction-v1-1.0.0.dar")),
    )
    val tokenAllocation = PackageResource(
      DarResource(s"splice-api-token-allocation-v1-current.dar"),
      DarResource(s"splice-api-token-allocation-v1-1.0.0.dar"),
      Seq(DarResource(s"splice-api-token-allocation-v1-1.0.0.dar")),
    )
    val tokenAllocationRequest = PackageResource(
      DarResource(s"splice-api-token-allocation-request-v1-current.dar"),
      DarResource(s"splice-api-token-allocation-v1-1.0.0.dar"),
      Seq(DarResource(s"splice-api-token-allocation-request-v1-1.0.0.dar")),
    )
    val tokenAllocationInstruction = PackageResource(
      DarResource(s"splice-api-token-allocation-instruction-v1-current.dar"),
      DarResource(s"splice-api-token-allocation-instruction-v1-1.0.0.dar"),
      Seq(DarResource(s"splice-api-token-allocation-instruction-v1-1.0.0.dar")),
    )
    val tokenTestTradingApp = PackageResource(
      DarResource(s"splice-token-test-trading-app-current.dar"),
      DarResource(s"splice-token-test-trading-app-1.0.0.dar"),
      Seq(DarResource(s"splice-token-test-trading-app-1.0.0.dar")),
    )
    val allProductionPackageResources = Seq(
      tokenMetadata,
      tokenHolding,
      tokenTransferInstruction,
      tokenAllocation,
      tokenAllocationRequest,
      tokenAllocationInstruction,
    )
    val allPackageResources = allProductionPackageResources :+ tokenTestTradingApp
  }

  val amulet_0_1_0 = DarResource("splice-amulet-0.1.0.dar")
  val amulet_0_1_1 = DarResource("splice-amulet-0.1.1.dar")
  val amulet_0_1_2 = DarResource("splice-amulet-0.1.2.dar")
  val amulet_0_1_3 = DarResource("splice-amulet-0.1.3.dar")
  val amulet_0_1_4 = DarResource("splice-amulet-0.1.4.dar")
  val amulet_0_1_5 = DarResource("splice-amulet-0.1.5.dar")
  val amulet_0_1_6 = DarResource("splice-amulet-0.1.6.dar")
  val amulet_0_1_7 = DarResource("splice-amulet-0.1.7.dar")
  val amulet_0_1_8 = DarResource("splice-amulet-0.1.8.dar")
  val amulet_0_1_9 = DarResource("splice-amulet-0.1.9.dar")
  val amulet_0_1_10 = DarResource("splice-amulet-0.1.10.dar")
  val amulet_0_1_11 = DarResource("splice-amulet-0.1.11.dar")
  val amulet_0_1_12 = DarResource("splice-amulet-0.1.12.dar")
  val amulet_0_1_13 = DarResource("splice-amulet-0.1.13.dar")
  val amulet_0_1_14 = DarResource("splice-amulet-0.1.14.dar")
  val amulet_current = DarResource("splice-amulet-current.dar")
  val amulet = PackageResource(
    amulet_current,
    amulet_0_1_10,
    Seq(
      amulet_0_1_0,
      amulet_0_1_1,
      amulet_0_1_2,
      amulet_0_1_3,
      amulet_0_1_4,
      amulet_0_1_5,
      amulet_0_1_6,
      amulet_0_1_7,
      amulet_0_1_8,
      amulet_0_1_9,
      amulet_0_1_10,
      amulet_0_1_11,
      amulet_0_1_12,
      amulet_0_1_13,
      amulet_0_1_14,
    ),
  )

  val dsoGovernance_0_1_0 = DarResource("splice-dso-governance-0.1.0.dar")
  val dsoGovernance_0_1_1 = DarResource("splice-dso-governance-0.1.1.dar")
  val dsoGovernance_0_1_2 = DarResource("splice-dso-governance-0.1.2.dar")
  val dsoGovernance_0_1_3 = DarResource("splice-dso-governance-0.1.3.dar")
  val dsoGovernance_0_1_4 = DarResource("splice-dso-governance-0.1.4.dar")
  val dsoGovernance_0_1_5 = DarResource("splice-dso-governance-0.1.5.dar")
  val dsoGovernance_0_1_6 = DarResource("splice-dso-governance-0.1.6.dar")
  val dsoGovernance_0_1_7 = DarResource("splice-dso-governance-0.1.7.dar")
  val dsoGovernance_0_1_8 = DarResource("splice-dso-governance-0.1.8.dar")
  val dsoGovernance_0_1_9 = DarResource("splice-dso-governance-0.1.9.dar")
  val dsoGovernance_0_1_10 = DarResource("splice-dso-governance-0.1.10.dar")
  val dsoGovernance_0_1_11 = DarResource("splice-dso-governance-0.1.11.dar")
  val dsoGovernance_0_1_12 = DarResource("splice-dso-governance-0.1.12.dar")
  val dsoGovernance_0_1_13 = DarResource("splice-dso-governance-0.1.13.dar")
  val dsoGovernance_0_1_14 = DarResource("splice-dso-governance-0.1.14.dar")
  val dsoGovernance_0_1_15 = DarResource("splice-dso-governance-0.1.15.dar")
  val dsoGovernance_0_1_16 = DarResource("splice-dso-governance-0.1.16.dar")
  val dsoGovernance_0_1_17 = DarResource("splice-dso-governance-0.1.17.dar")
  val dsoGovernance_0_1_18 = DarResource("splice-dso-governance-0.1.18.dar")
  val dsoGovernance_0_1_19 = DarResource("splice-dso-governance-0.1.19.dar")
  val dsoGovernance_0_1_20 = DarResource("splice-dso-governance-0.1.20.dar")
  val dsoGovernance_current = DarResource("splice-dso-governance-current.dar")
  val dsoGovernance = PackageResource(
    dsoGovernance_current,
    dsoGovernance_0_1_14,
    Seq(
      dsoGovernance_0_1_0,
      dsoGovernance_0_1_1,
      dsoGovernance_0_1_2,
      dsoGovernance_0_1_3,
      dsoGovernance_0_1_4,
      dsoGovernance_0_1_5,
      dsoGovernance_0_1_6,
      dsoGovernance_0_1_7,
      dsoGovernance_0_1_8,
      dsoGovernance_0_1_9,
      dsoGovernance_0_1_10,
      dsoGovernance_0_1_11,
      dsoGovernance_0_1_12,
      dsoGovernance_0_1_13,
      dsoGovernance_0_1_14,
      dsoGovernance_0_1_15,
      dsoGovernance_0_1_16,
      dsoGovernance_0_1_17,
      dsoGovernance_0_1_18,
      dsoGovernance_0_1_19,
      dsoGovernance_0_1_20,
    ),
  )

  val amuletNameService_0_1_0 = DarResource("splice-amulet-name-service-0.1.0.dar")
  val amuletNameService_0_1_1 = DarResource("splice-amulet-name-service-0.1.1.dar")
  val amuletNameService_0_1_2 = DarResource("splice-amulet-name-service-0.1.2.dar")
  val amuletNameService_0_1_3 = DarResource("splice-amulet-name-service-0.1.3.dar")
  val amuletNameService_0_1_4 = DarResource("splice-amulet-name-service-0.1.4.dar")
  val amuletNameService_0_1_5 = DarResource("splice-amulet-name-service-0.1.5.dar")
  val amuletNameService_0_1_6 = DarResource("splice-amulet-name-service-0.1.6.dar")
  val amuletNameService_0_1_7 = DarResource("splice-amulet-name-service-0.1.7.dar")
  val amuletNameService_0_1_8 = DarResource("splice-amulet-name-service-0.1.8.dar")
  val amuletNameService_0_1_9 = DarResource("splice-amulet-name-service-0.1.9.dar")
  val amuletNameService_0_1_10 = DarResource("splice-amulet-name-service-0.1.10.dar")
  val amuletNameService_0_1_11 = DarResource("splice-amulet-name-service-0.1.11.dar")
  val amuletNameService_0_1_12 = DarResource("splice-amulet-name-service-0.1.12.dar")
  val amuletNameService_0_1_13 = DarResource("splice-amulet-name-service-0.1.13.dar")
  val amuletNameService_0_1_14 = DarResource("splice-amulet-name-service-0.1.14.dar")
  val amuletNameService_0_1_15 = DarResource("splice-amulet-name-service-0.1.15.dar")
  val amuletNameService_current = DarResource("splice-amulet-name-service-current.dar")
  val amuletNameService = PackageResource(
    amuletNameService_current,
    amuletNameService_0_1_10,
    Seq(
      amuletNameService_0_1_0,
      amuletNameService_0_1_1,
      amuletNameService_0_1_2,
      amuletNameService_0_1_3,
      amuletNameService_0_1_4,
      amuletNameService_0_1_5,
      amuletNameService_0_1_6,
      amuletNameService_0_1_7,
      amuletNameService_0_1_8,
      amuletNameService_0_1_9,
      amuletNameService_0_1_10,
      amuletNameService_0_1_11,
      amuletNameService_0_1_12,
      amuletNameService_0_1_13,
      amuletNameService_0_1_14,
      amuletNameService_0_1_15,
    ),
  )

  val splitwell_0_1_0 = DarResource("splitwell-0.1.0.dar")
  val splitwell_0_1_1 = DarResource("splitwell-0.1.1.dar")
  val splitwell_0_1_2 = DarResource("splitwell-0.1.2.dar")
  val splitwell_0_1_3 = DarResource("splitwell-0.1.3.dar")
  val splitwell_0_1_4 = DarResource("splitwell-0.1.4.dar")
  val splitwell_0_1_5 = DarResource("splitwell-0.1.5.dar")
  val splitwell_0_1_6 = DarResource("splitwell-0.1.6.dar")
  val splitwell_0_1_7 = DarResource("splitwell-0.1.7.dar")
  val splitwell_0_1_8 = DarResource("splitwell-0.1.8.dar")
  val splitwell_0_1_9 = DarResource("splitwell-0.1.9.dar")
  val splitwell_0_1_10 = DarResource("splitwell-0.1.10.dar")
  val splitwell_0_1_11 = DarResource("splitwell-0.1.11.dar")
  val splitwell_0_1_12 = DarResource("splitwell-0.1.12.dar")
  val splitwell_0_1_13 = DarResource("splitwell-0.1.13.dar")
  val splitwell_0_1_14 = DarResource("splitwell-0.1.14.dar")
  val splitwell_0_1_15 = DarResource("splitwell-0.1.15.dar")
  val splitwell_current = DarResource("splitwell-current.dar")
  val splitwell = PackageResource(
    splitwell_current,
    splitwell_0_1_10,
    Seq(
      splitwell_0_1_0,
      splitwell_0_1_1,
      splitwell_0_1_2,
      splitwell_0_1_3,
      splitwell_0_1_4,
      splitwell_0_1_5,
      splitwell_0_1_6,
      splitwell_0_1_7,
      splitwell_0_1_8,
      splitwell_0_1_9,
      splitwell_0_1_10,
      splitwell_0_1_11,
      splitwell_0_1_12,
      splitwell_0_1_13,
      splitwell_0_1_14,
      splitwell_0_1_15,
    ),
  )

  val wallet_0_1_0 = DarResource("splice-wallet-0.1.0.dar")
  val wallet_0_1_1 = DarResource("splice-wallet-0.1.1.dar")
  val wallet_0_1_2 = DarResource("splice-wallet-0.1.2.dar")
  val wallet_0_1_3 = DarResource("splice-wallet-0.1.3.dar")
  val wallet_0_1_4 = DarResource("splice-wallet-0.1.4.dar")
  val wallet_0_1_5 = DarResource("splice-wallet-0.1.5.dar")
  val wallet_0_1_6 = DarResource("splice-wallet-0.1.6.dar")
  val wallet_0_1_7 = DarResource("splice-wallet-0.1.7.dar")
  val wallet_0_1_8 = DarResource("splice-wallet-0.1.8.dar")
  val wallet_0_1_9 = DarResource("splice-wallet-0.1.9.dar")
  val wallet_0_1_10 = DarResource("splice-wallet-0.1.10.dar")
  val wallet_0_1_11 = DarResource("splice-wallet-0.1.11.dar")
  val wallet_0_1_12 = DarResource("splice-wallet-0.1.12.dar")
  val wallet_0_1_13 = DarResource("splice-wallet-0.1.13.dar")
  val wallet_0_1_14 = DarResource("splice-wallet-0.1.14.dar")
  val wallet_0_1_15 = DarResource("splice-wallet-0.1.15.dar")
  val wallet_current = DarResource("splice-wallet-current.dar")
  val wallet = PackageResource(
    wallet_current,
    wallet_0_1_10,
    Seq(
      wallet_0_1_0,
      wallet_0_1_1,
      wallet_0_1_2,
      wallet_0_1_3,
      wallet_0_1_4,
      wallet_0_1_5,
      wallet_0_1_6,
      wallet_0_1_7,
      wallet_0_1_8,
      wallet_0_1_9,
      wallet_0_1_10,
      wallet_0_1_11,
      wallet_0_1_12,
      wallet_0_1_13,
      wallet_0_1_14,
      wallet_0_1_15,
    ),
  )

  val walletPayments_0_1_0 = DarResource("splice-wallet-payments-0.1.0.dar")
  val walletPayments_0_1_1 = DarResource("splice-wallet-payments-0.1.1.dar")
  val walletPayments_0_1_2 = DarResource("splice-wallet-payments-0.1.2.dar")
  val walletPayments_0_1_3 = DarResource("splice-wallet-payments-0.1.3.dar")
  val walletPayments_0_1_4 = DarResource("splice-wallet-payments-0.1.4.dar")
  val walletPayments_0_1_5 = DarResource("splice-wallet-payments-0.1.5.dar")
  val walletPayments_0_1_6 = DarResource("splice-wallet-payments-0.1.6.dar")
  val walletPayments_0_1_7 = DarResource("splice-wallet-payments-0.1.7.dar")
  val walletPayments_0_1_8 = DarResource("splice-wallet-payments-0.1.8.dar")
  val walletPayments_0_1_9 = DarResource("splice-wallet-payments-0.1.9.dar")
  val walletPayments_0_1_10 = DarResource("splice-wallet-payments-0.1.10.dar")
  val walletPayments_0_1_11 = DarResource("splice-wallet-payments-0.1.11.dar")
  val walletPayments_0_1_12 = DarResource("splice-wallet-payments-0.1.12.dar")
  val walletPayments_0_1_13 = DarResource("splice-wallet-payments-0.1.13.dar")
  val walletPayments_0_1_14 = DarResource("splice-wallet-payments-0.1.14.dar")
  val walletPayments_current = DarResource("splice-wallet-payments-current.dar")
  val walletPayments = PackageResource(
    walletPayments_current,
    walletPayments_0_1_10,
    Seq(
      walletPayments_0_1_0,
      walletPayments_0_1_1,
      walletPayments_0_1_2,
      walletPayments_0_1_3,
      walletPayments_0_1_4,
      walletPayments_0_1_5,
      walletPayments_0_1_6,
      walletPayments_0_1_7,
      walletPayments_0_1_8,
      walletPayments_0_1_9,
      walletPayments_0_1_10,
      walletPayments_0_1_11,
      walletPayments_0_1_12,
      walletPayments_0_1_13,
      walletPayments_0_1_14,
    ),
  )

  val validatorLifecycle_0_1_0 = DarResource("splice-validator-lifecycle-0.1.0.dar")
  val validatorLifecycle_0_1_1 = DarResource("splice-validator-lifecycle-0.1.1.dar")
  val validatorLifecycle_0_1_2 = DarResource("splice-validator-lifecycle-0.1.2.dar")
  val validatorLifecycle_0_1_3 = DarResource("splice-validator-lifecycle-0.1.3.dar")
  val validatorLifecycle_0_1_4 = DarResource("splice-validator-lifecycle-0.1.4.dar")
  val validatorLifecycle_0_1_5 = DarResource("splice-validator-lifecycle-0.1.5.dar")
  val validatorLifecycle_current = DarResource("splice-validator-lifecycle-current.dar")
  val validatorLifecycle = PackageResource(
    validatorLifecycle_current,
    validatorLifecycle_0_1_3,
    Seq(
      validatorLifecycle_0_1_0,
      validatorLifecycle_0_1_1,
      validatorLifecycle_0_1_2,
      validatorLifecycle_0_1_3,
      validatorLifecycle_0_1_4,
      validatorLifecycle_0_1_5,
    ),
  )

  val featuredApp = PackageResource(
    DarResource("splice-api-featured-app-v1-current.dar"),
    DarResource("splice-api-featured-app-v1-1.0.0.dar"),
    Seq(DarResource("splice-api-featured-app-v1-1.0.0.dar")),
  )

  val packageResources: Seq[PackageResource] =
    TokenStandard.allPackageResources ++ Seq(
      DarResources.amulet,
      DarResources.amuletNameService,
      DarResources.splitwell,
      DarResources.dsoGovernance,
      DarResources.validatorLifecycle,
      DarResources.wallet,
      DarResources.walletPayments,
    )

  private val pkgIdToDarResource: Map[String, DarResource] =
    packageResources.view.flatMap(_.all).map(resource => resource.packageId -> resource).toMap

  // We don't index the map by PackageMetadata because that type contains some additional
  // fields that don't matter.
  private val pkgMetadataToDarResource: Map[(PackageName, PackageVersion), DarResource] =
    packageResources.view
      .flatMap(_.all)
      .map(resource => (resource.metadata.name, resource.metadata.version) -> resource)
      .toMap

  def lookupPackageId(packageId: String): Option[DarResource] =
    pkgIdToDarResource.get(packageId)

  def getDarResources(packageIds: Seq[String]): Seq[DarResource] =
    packageIds.flatMap(lookupPackageId)

  def lookupPackageMetadata(name: PackageName, version: PackageVersion): Option[DarResource] =
    pkgMetadataToDarResource.get((name, version))

  def lookupAllPackageVersions(name: PackageName): Seq[DarResource] =
    packageResources.view.flatMap(_.all).toSeq.filter(_.metadata.name == name)
}

/** All DARs for a given package
  */
final case class PackageResource(
    bootstrap: DarResource, // Used during bootstrapping or testing where we can assume a fixed package id.
    minimumInitialization: DarResource, // The minimum version that can be used for initialization of a fresh network
    others: Seq[DarResource], // Other DARs for the same package
) {
  def getPackageIdWithVersion(version: String): Option[String] = {
    getDarResource(version).map(_.packageId)
  }

  def getDarResource(version: String): Option[DarResource] = {
    all.find(_.metadata.version.toString() == version)
  }

  def all = bootstrap +: others
}

final case class DarResource(
    path: String,
    packageId: String,
    metadata: PackageMetadata,
    dependencyPackageIds: Set[String],
)

object DarResource {

  def apply(path: Path): DarResource = {
    val dar = DarUtil.readDar(path.toFile)
    apply(path.getFileName.toString, dar)
  }

  def apply(file: String): DarResource = {
    val input = getClass.getClassLoader.getResourceAsStream(file)
    if (input == null) {
      throw new IllegalArgumentException(s"Not found: $file")
    }
    val dar =
      Using.resource(input) { resourceStream =>
        DarUtil.readDar(file, resourceStream)
      }
    apply(file, dar)
  }

  private def apply(
      file: String,
      dar: Dar[(PackageId, Ast.Package)],
  ): DarResource = {
    DarResource(
      file,
      dar.main._1,
      dar.main._2.metadata,
      dar.dependencies.map(_._1).toSet,
    )
  }
}
