// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import com.digitalasset.daml.lf.data.Ref.{IdString, PackageName, PackageVersion}
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules
import org.lfdecentralizedtrust.splice.util.Contract

import scala.concurrent.Future

object PackageIdResolver {
  trait HasAmuletRules {

    def getAmuletRules()(implicit
        tc: TraceContext
    ): Future[Contract[AmuletRules.ContractId, AmuletRules]]
  }
  def readPackageVersion(
      packageConfig: splice.amuletconfig.PackageConfig,
      pkg: Package,
  ): PackageVersion = {
    import Package.*
    val version = pkg match {
      case SpliceAmulet => packageConfig.amulet
      case SpliceAmuletNameService => packageConfig.amuletNameService
      case SpliceDsoGovernance => packageConfig.dsoGovernance
      case SpliceValidatorLifecycle => packageConfig.validatorLifecycle
      case SpliceWallet => packageConfig.wallet
      case SpliceWalletPayments => packageConfig.walletPayments
      case TokenStandard.SpliceApiTokenMetadataV1 =>
        DarResources.TokenStandard.tokenMetadata.latest.metadata.version.toString()
      case TokenStandard.SpliceApiTokenHoldingV1 =>
        DarResources.TokenStandard.tokenHolding.latest.metadata.version.toString()
      case TokenStandard.SpliceApiTokenTransferInstructionV1 =>
        DarResources.TokenStandard.tokenTransferInstruction.latest.metadata.version.toString()
      case TokenStandard.SpliceApiTokenAllocationV1 =>
        DarResources.TokenStandard.tokenAllocation.latest.metadata.version.toString()
      case TokenStandard.SpliceApiTokenAllocationRequestV1 =>
        DarResources.TokenStandard.tokenAllocationRequest.latest.metadata.version.toString()
      case TokenStandard.SpliceApiTokenAllocationInstructionV1 =>
        DarResources.TokenStandard.tokenAllocationInstruction.latest.metadata.version.toString()
      case TokenStandard.SpliceTokenTestTradingApp =>
        DarResources.TokenStandard.tokenTestTradingApp.latest.metadata.version.toString()
    }
    PackageVersion.assertFromString(version)
  }

  sealed abstract class Package extends Product with Serializable {
    def packageName: IdString.PackageName = {
      val clsName = this.productPrefix
      // Turn CantonAmulet into canton-amulet
      PackageName.assertFromString(
        "[A-Z]".r
          .replaceAllIn(clsName, m => (if (m.start != 0) "-" else "") + m.matched.toLowerCase())
      )
    }
  }

  object Package {

    object TokenStandard {
      final case object SpliceApiTokenMetadataV1 extends Package
      final case object SpliceApiTokenHoldingV1 extends Package
      final case object SpliceApiTokenTransferInstructionV1 extends Package
      final case object SpliceApiTokenAllocationV1 extends Package
      final case object SpliceApiTokenAllocationRequestV1 extends Package
      final case object SpliceApiTokenAllocationInstructionV1 extends Package
      final case object SpliceTokenTestTradingApp extends Package
    }
    final case object SpliceAmulet extends Package
    final case object SpliceAmuletNameService extends Package
    final case object SpliceDsoGovernance extends Package
    final case object SpliceValidatorLifecycle extends Package
    final case object SpliceWallet extends Package
    final case object SpliceWalletPayments extends Package
  }
}
