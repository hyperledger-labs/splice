package com.daml.network.environment

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

  val directoryService_0_1_0 = DarResource("directory-service-0.1.0.dar")
  val directoryService_0_2_0 = DarResource("directory-service-0.2.0.dar")
  val directoryService = PackageResource(
    directoryService_0_1_0,
    Seq(directoryService_0_2_0),
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
)

object DarResource {
  private val hashOps = new HashOps {
    override def defaultHashAlgorithm = HashAlgorithm.Sha256
  }

  def apply(file: String): DarResource = {
    val path = s"dar/$file"
    val pkgId = DarUtil.readPackageId(path)
    val darBytes =
      Using.resource(getClass.getClassLoader.getResourceAsStream(path))(ByteString.readFrom(_))
    val hash = hashOps.digest(HashPurpose.DarIdentifier, darBytes)
    DarResource(
      path,
      pkgId,
      hash,
    )
  }
}
