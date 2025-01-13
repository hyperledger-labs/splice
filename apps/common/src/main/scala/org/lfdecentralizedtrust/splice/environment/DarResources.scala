// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import com.digitalasset.daml.lf.data.Ref.{PackageName, PackageVersion}
import com.digitalasset.daml.lf.language.Ast.PackageMetadata
import org.lfdecentralizedtrust.splice.util.DarUtil
import com.digitalasset.canton.crypto.{Hash, HashAlgorithm, HashOps, HashPurpose}
import com.google.protobuf.ByteString
import scala.util.Using

object DarResources {
  val amulet_0_1_0 = DarResource("splice-amulet-0.1.0.dar")
  val amulet_0_1_1 = DarResource("splice-amulet-0.1.1.dar")
  val amulet_0_1_2 = DarResource("splice-amulet-0.1.2.dar")
  val amulet_0_1_3 = DarResource("splice-amulet-0.1.3.dar")
  val amulet_0_1_4 = DarResource("splice-amulet-0.1.4.dar")
  val amulet_0_1_5 = DarResource("splice-amulet-0.1.5.dar")
  val amulet_0_1_6 = DarResource("splice-amulet-0.1.6.dar")
  val amulet_0_1_7 = DarResource("splice-amulet-0.1.7.dar")
  val amulet_current = DarResource("splice-amulet-current.dar")
  val amulet = PackageResource(
    amulet_current,
    Seq(
      amulet_0_1_0,
      amulet_0_1_1,
      amulet_0_1_2,
      amulet_0_1_3,
      amulet_0_1_4,
      amulet_0_1_5,
      amulet_0_1_6,
      amulet_0_1_7,
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
  val dsoGovernance_current = DarResource("splice-dso-governance-current.dar")
  val dsoGovernance = PackageResource(
    dsoGovernance_current,
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
  val amuletNameService_current = DarResource("splice-amulet-name-service-current.dar")
  val amuletNameService = PackageResource(
    amuletNameService_current,
    Seq(
      amuletNameService_0_1_0,
      amuletNameService_0_1_1,
      amuletNameService_0_1_2,
      amuletNameService_0_1_3,
      amuletNameService_0_1_4,
      amuletNameService_0_1_5,
      amuletNameService_0_1_6,
      amuletNameService_0_1_7,
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
  val splitwell_current = DarResource("splitwell-current.dar")
  val splitwell = PackageResource(
    splitwell_current,
    Seq(
      splitwell_0_1_0,
      splitwell_0_1_1,
      splitwell_0_1_2,
      splitwell_0_1_3,
      splitwell_0_1_4,
      splitwell_0_1_5,
      splitwell_0_1_6,
      splitwell_0_1_7,
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
  val wallet_current = DarResource("splice-wallet-current.dar")
  val wallet = PackageResource(
    wallet_current,
    Seq(
      wallet_0_1_0,
      wallet_0_1_1,
      wallet_0_1_2,
      wallet_0_1_3,
      wallet_0_1_4,
      wallet_0_1_5,
      wallet_0_1_6,
      wallet_0_1_7,
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
  val walletPayments_current = DarResource("splice-wallet-payments-current.dar")
  val walletPayments = PackageResource(
    walletPayments_current,
    Seq(
      walletPayments_0_1_0,
      walletPayments_0_1_1,
      walletPayments_0_1_2,
      walletPayments_0_1_3,
      walletPayments_0_1_4,
      walletPayments_0_1_5,
      walletPayments_0_1_6,
      walletPayments_0_1_7,
    ),
  )

  val validatorLifecycle_0_1_0 = DarResource("splice-validator-lifecycle-0.1.0.dar")
  val validatorLifecycle_0_1_1 = DarResource("splice-validator-lifecycle-0.1.1.dar")
  val validatorLifecycle_current = DarResource("splice-validator-lifecycle-current.dar")
  val validatorLifecycle = PackageResource(
    validatorLifecycle_current,
    Seq(validatorLifecycle_0_1_0, validatorLifecycle_0_1_1),
  )

  private val packageResources: Seq[PackageResource] =
    Seq(
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
    darHash: Hash,
    metadata: PackageMetadata,
    dependencyPackageIds: Set[String],
)

object DarResource {
  private val hashOps = new HashOps {
    override def defaultHashAlgorithm: com.digitalasset.canton.crypto.HashAlgorithm.Sha256.type =
      HashAlgorithm.Sha256
  }

  def apply(file: String): DarResource = {
    val (darBytes, dar) =
      Using.resource(getClass.getClassLoader.getResourceAsStream(file)) { resourceStream =>
        val bytes = ByteString.readFrom(resourceStream)
        val metadata = Using.resource(bytes.newInput())(DarUtil.readDar(file, _))
        (bytes, metadata)
      }
    val hash = hashOps.digest(HashPurpose.DarIdentifier, darBytes)
    DarResource(
      file,
      dar.main._1,
      hash,
      dar.main._2.metadata,
      dar.dependencies.map(_._1).toSet,
    )
  }
}
