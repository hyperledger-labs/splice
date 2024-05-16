package com.daml.network.environment

import com.daml.lf.data.Ref.{PackageName, PackageVersion}
import com.daml.lf.language.Ast.PackageMetadata
import com.daml.network.util.DarUtil
import com.digitalasset.canton.crypto.{Hash, HashAlgorithm, HashOps, HashPurpose}
import com.google.protobuf.ByteString
import scala.util.Using

object DarResources {
  val amulet_0_1_0 = DarResource("splice-amulet-0.1.0.dar")
  val amulet_0_1_1 = DarResource("splice-amulet-0.1.1.dar")
  val amulet_current = DarResource("splice-amulet-current.dar")
  val amulet = PackageResource(
    amulet_current,
    Seq(amulet_0_1_0, amulet_0_1_1),
  )

  val dsoGovernance_0_1_0 = DarResource("splice-dso-governance-0.1.0.dar")
  val dsoGovernance_0_1_1 = DarResource("splice-dso-governance-0.1.1.dar")
  val dsoGovernance_current = DarResource("splice-dso-governance-current.dar")
  val dsoGovernance = PackageResource(
    dsoGovernance_current,
    Seq(dsoGovernance_0_1_0, dsoGovernance_0_1_1),
  )

  val amuletNameService_0_1_0 = DarResource("splice-amulet-name-service-0.1.0.dar")
  val amuletNameService_0_1_1 = DarResource("splice-amulet-name-service-0.1.1.dar")
  val amuletNameService_current = DarResource("splice-amulet-name-service-current.dar")
  val amuletNameService = PackageResource(
    amuletNameService_current,
    Seq(amuletNameService_0_1_0, amuletNameService_0_1_1),
  )

  val splitwell_0_1_0 = DarResource("splitwell-0.1.0.dar")
  val splitwell_0_1_1 = DarResource("splitwell-0.1.1.dar")
  val splitwell_current = DarResource("splitwell-current.dar")
  val splitwell = PackageResource(
    splitwell_current,
    Seq(splitwell_0_1_0, splitwell_0_1_1),
  )

  val wallet_0_1_0 = DarResource("splice-wallet-0.1.0.dar")
  val wallet_0_1_1 = DarResource("splice-wallet-0.1.1.dar")
  val wallet_current = DarResource("splice-wallet-current.dar")
  val wallet = PackageResource(
    wallet_current,
    Seq(wallet_0_1_0, wallet_0_1_1),
  )

  val walletPayments_0_1_0 = DarResource("splice-wallet-payments-0.1.0.dar")
  val walletPayments_0_1_1 = DarResource("splice-wallet-payments-0.1.1.dar")
  val walletPayments_current = DarResource("splice-wallet-payments-current.dar")
  val walletPayments = PackageResource(
    walletPayments_current,
    Seq(walletPayments_0_1_0, walletPayments_0_1_1),
  )

  val validatorLifecycle_0_1_0 = DarResource("splice-validator-lifecycle-0.1.0.dar")
  val validatorLifecycle_current = DarResource("splice-validator-lifecycle-current.dar")
  val validatorLifecycle = PackageResource(
    validatorLifecycle_current,
    Seq(validatorLifecycle_0_1_0),
  )

  val appManager_0_1_0 = DarResource("splice-app-manager-0.1.0.dar")
  val appManager_current = DarResource("splice-app-manager-current.dar")
  val appManager = PackageResource(
    appManager_current,
    Seq(appManager_0_1_0),
  )

  private val packageResources: Seq[PackageResource] =
    Seq(
      DarResources.appManager,
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
    override def defaultHashAlgorithm = HashAlgorithm.Sha256
  }

  def apply(file: String): DarResource = {
    val path = s"$file"
    val (darBytes, dar) =
      Using.resource(getClass.getClassLoader.getResourceAsStream(path)) { resourceStream =>
        val bytes = ByteString.readFrom(resourceStream)
        val metadata = Using.resource(bytes.newInput())(DarUtil.readDar(path, _))
        (bytes, metadata)
      }
    val hash = hashOps.digest(HashPurpose.DarIdentifier, darBytes)
    DarResource(
      path,
      dar.main._1,
      hash,
      dar.main._2.metadata,
      dar.dependencies.map(_._1).toSet,
    )
  }
}
