package com.daml.network.environment

import com.daml.lf.data.Ref.{PackageName, PackageVersion}
import com.daml.lf.language.Ast.PackageMetadata
import com.daml.network.util.DarUtil
import com.digitalasset.canton.crypto.{Hash, HashAlgorithm, HashOps, HashPurpose}
import com.google.protobuf.ByteString
import scala.util.Using

object DarResources {
  val cantonCoin_0_1_0 = DarResource("canton-coin-0.1.0.dar")
  val cantonCoin_0_2_0 = DarResource("canton-coin-0.2.0.dar")
  val cantonCoin = PackageResource(
    cantonCoin_0_1_0,
    Seq(cantonCoin_0_2_0),
  )

  val svcGovernance_0_1_0 = DarResource("svc-governance-0.1.0.dar")
  val svcGovernance_0_2_0 = DarResource("svc-governance-0.2.0.dar")
  val svcGovernance = PackageResource(
    svcGovernance_0_1_0,
    Seq(svcGovernance_0_2_0),
  )

  val cantonNameService_0_1_0 = DarResource("canton-name-service-0.1.0.dar")
  val cantonNameService_0_2_0 = DarResource("canton-name-service-0.2.0.dar")
  val cantonNameService = PackageResource(
    cantonNameService_0_1_0,
    Seq(cantonNameService_0_2_0),
  )

  val splitwell_0_1_0 = DarResource("splitwell-0.1.0.dar")
  val splitwell_0_2_0 = DarResource("splitwell-0.2.0.dar")
  val splitwell = PackageResource(
    splitwell_0_1_0,
    Seq(splitwell_0_2_0),
  )

  val wallet_0_1_0 = DarResource("wallet-0.1.0.dar")
  val wallet_0_2_0 = DarResource("wallet-0.2.0.dar")
  val wallet = PackageResource(
    wallet_0_1_0,
    Seq(wallet_0_2_0),
  )

  val walletPayments_0_1_0 = DarResource("wallet-payments-0.1.0.dar")
  val walletPayments_0_2_0 = DarResource("wallet-payments-0.2.0.dar")
  val walletPayments = PackageResource(
    walletPayments_0_1_0,
    Seq(walletPayments_0_2_0),
  )

  val validatorLifecycle_0_1_0 = DarResource("validator-lifecycle-0.1.0.dar")
  val validatorLifecycle = PackageResource(
    validatorLifecycle_0_1_0,
    Seq.empty,
  )

  val appManager_0_1_0 = DarResource("app-manager-0.1.0.dar")
  val appManager = PackageResource(
    appManager_0_1_0,
    Seq.empty,
  )

  val svLocal_0_1_0 = DarResource("sv-local-0.1.0.dar")
  val svLocal = PackageResource(
    svLocal_0_1_0,
    Seq.empty,
  )

  private val packageResources: Seq[PackageResource] =
    Seq(
      DarResources.appManager,
      DarResources.cantonCoin,
      DarResources.cantonNameService,
      DarResources.splitwell,
      DarResources.svcGovernance,
      DarResources.svLocal,
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
    val path = s"dar/$file"
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
