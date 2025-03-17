// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import com.digitalasset.daml.lf.data.Ref.{PackageName, PackageRef, PackageVersion}
import com.daml.ledger.javaapi.data.{Command, Identifier}
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.util.{AmuletConfigSchedule, Contract, QualifiedName}

import scala.concurrent.{ExecutionContext, Future}

abstract class PackageIdResolver()(implicit ec: ExecutionContext) {
  protected def resolvePackageId(
      templateId: QualifiedName
  )(implicit tc: TraceContext): Future[String]

  private def resolvePackageId(
      identifier: Identifier
  )(implicit tc: TraceContext): Future[Identifier] = {
    resolvePackageId(QualifiedName(identifier)).map(pkgId =>
      new Identifier(
        pkgId,
        identifier.getModuleName,
        identifier.getEntityName,
      )
    )
  }

  def resolvePackageId(
      command: Command
  )(implicit tc: TraceContext): Future[Command] = {
    import com.daml.ledger.javaapi.data.{
      CreateCommand,
      ExerciseCommand,
      CreateAndExerciseCommand,
      ExerciseByKeyCommand,
    }
    command match {
      case create: CreateCommand =>
        resolvePackageId(create.getTemplateId).map(templateId =>
          new CreateCommand(
            templateId,
            create.getCreateArguments,
          )
        )
      case exercise: ExerciseCommand =>
        resolvePackageId(exercise.getTemplateId).map(templateId =>
          new ExerciseCommand(
            templateId,
            exercise.getContractId,
            exercise.getChoice,
            exercise.getChoiceArgument,
          )
        )
      case createAndExercise: CreateAndExerciseCommand =>
        // For some reason getTemplateId on CreateAndExerciseCommand is private.
        resolvePackageId(Identifier.fromProto(createAndExercise.toProto.getTemplateId)).map(
          templateId =>
            new CreateAndExerciseCommand(
              templateId,
              createAndExercise.getCreateArguments,
              createAndExercise.getChoice,
              createAndExercise.getChoiceArgument,
            )
        )
      case exerciseByKey: ExerciseByKeyCommand =>
        resolvePackageId(exerciseByKey.getTemplateId).map(templateId =>
          new ExerciseByKeyCommand(
            templateId,
            exerciseByKey.getContractKey,
            exerciseByKey.getChoice,
            exerciseByKey.getChoiceArgument,
          )
        )
      case _ => throw new AssertionError(s"Unknown command type: $command")
    }
  }
}

object PackageIdResolver {

  private class CantonTopologyAwarePackageIdResolver(
      extraModules: Map[String, PackageName]
  ) extends PackageIdResolver()(ExecutionContext.global) {

    override protected def resolvePackageId(templateId: QualifiedName)(implicit
        tc: TraceContext
    ): Future[String] = Future.successful(
      PackageRef
        .Name(
          modulePackages
            .get(templateId.moduleName)
            .map(_.packageName)
            .getOrElse(
              extraModules(
                templateId.moduleName
              )
            )
        )
        .toString()
    )
  }

  trait HasAmuletRules {
    def getAmuletRules()(implicit
        tc: TraceContext
    ): Future[Contract[AmuletRules.ContractId, AmuletRules]]
  }

  /** Package id resolver for direct command submissions in tests.
    * This statically picks a package id.
    */
  def staticTesting(implicit ec: ExecutionContext): PackageIdResolver =
    new PackageIdResolver {
      override def resolvePackageId(
          templateId: QualifiedName
      )(implicit tc: TraceContext): Future[String] =
        Future {
          resolvePackageResource(templateId).bootstrap.packageId
        }

      def resolvePackageResource(templateId: QualifiedName): PackageResource =
        modulePackages.get(templateId.moduleName) match {
          case None =>
            templateId.moduleName match {
              case "Splice.Splitwell" => DarResources.splitwell
              case _ => throw new IllegalArgumentException(s"Unknown template $templateId")
            }
          case Some(pkg) =>
            pkg match {
              case Package.SpliceAmulet => DarResources.amulet
              case Package.SpliceAmuletNameService => DarResources.amuletNameService
              case Package.SpliceDsoGovernance => DarResources.dsoGovernance
              case Package.SpliceValidatorLifecycle => DarResources.validatorLifecycle
              case Package.SpliceWallet => DarResources.wallet
              case Package.SpliceWalletPayments => DarResources.walletPayments
              case Package.TokenStandard.TokenMetadata =>
                DarResources.TokenStandard.tokenMetadata
              case Package.TokenStandard.TokenHolding =>
                DarResources.TokenStandard.tokenHolding
              case Package.TokenStandard.TokenTransferInstruction =>
                DarResources.TokenStandard.tokenTransferInstruction
              case Package.TokenStandard.TokenAllocation =>
                DarResources.TokenStandard.tokenAllocation
              case Package.TokenStandard.TokenAllocationRequest =>
                DarResources.TokenStandard.tokenAllocationRequest
              case Package.TokenStandard.TokenAllocationInstruction =>
                DarResources.TokenStandard.tokenAllocationInstruction
            }
        }
    }

  /** Infer the package ids based on the current config in AmuletRules.
    * Templates not covered by AmuletRules can be specified in `extraPackageIdResolver`
    * which takes precedence over AmuletRules.
    */
  def inferFromAmuletRulesIfEnabled(
      enableCantonPackageSelection: Boolean,
      clock: Clock,
      amuletRulesFetcher: HasAmuletRules,
      loggerFactory0: NamedLoggerFactory,
      // Resolver that is checked before all other resolvers. Crucially this one
      // can work even if AmuletRules cannot be fetched, e.g., during bootstrapping
      // when AmuletRules is not yet created.
      bootstrapPackageIdResolver: QualifiedName => Option[String] = _ => None,
      // Resolver that is checked when none of the standard packages match.
      extraPackageIdResolver: (splice.amuletconfig.PackageConfig, QualifiedName) => Option[String] =
        (_, _) => None,
      extraModules: Map[String, PackageName] = Map.empty,
  )(implicit ec: ExecutionContext): PackageIdResolver = if (enableCantonPackageSelection) {
    new CantonTopologyAwarePackageIdResolver(extraModules)
  } else
    new PackageIdResolver with NamedLogging {

      override val loggerFactory: NamedLoggerFactory = loggerFactory0

      private def fromAmuletRules(
          packageConfig: splice.amuletconfig.PackageConfig,
          name: QualifiedName,
      ): Option[String] = {
        modulePackages
          .get(name.moduleName)
          .map { pkg =>
            val version = readPackageVersion(packageConfig, pkg)
            DarResources
              .lookupPackageMetadata(pkg.packageName, version)
              .fold(
                throw new IllegalArgumentException(
                  s"No package with name ${pkg.packageName} and version $version is known"
                )
              )(_.packageId)
          }
      }

      override def resolvePackageId(
          name: QualifiedName
      )(implicit tc: TraceContext): Future[String] = {
        val pkgId = bootstrapPackageIdResolver(name) match {
          case None =>
            amuletRulesFetcher.getAmuletRules().map { amuletRules =>
              val schedule = AmuletConfigSchedule(amuletRules)
              val config = schedule.getConfigAsOf(clock.now)
              fromAmuletRules(config.packageConfig, name)
                .orElse(extraPackageIdResolver(config.packageConfig, name))
                .getOrElse(throw new IllegalArgumentException(s"Unknown template $name"))
            }
          case Some(pkgId) => Future.successful(pkgId)
        }
        logger.trace(s"Resolving template $name to package id $pkgId")
        pkgId
      }
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
      case TokenStandard.TokenMetadata =>
        DarResources.TokenStandard.tokenMetadata.bootstrap.metadata.version.toString()
      case TokenStandard.TokenHolding =>
        DarResources.TokenStandard.tokenHolding.bootstrap.metadata.version.toString()
      case TokenStandard.TokenTransferInstruction =>
        DarResources.TokenStandard.tokenTransferInstruction.bootstrap.metadata.version.toString()
      case TokenStandard.TokenAllocation =>
        DarResources.TokenStandard.tokenAllocation.bootstrap.metadata.version.toString()
      case TokenStandard.TokenAllocationRequest =>
        DarResources.TokenStandard.tokenAllocationRequest.bootstrap.metadata.version.toString()
      case TokenStandard.TokenAllocationInstruction =>
        DarResources.TokenStandard.tokenAllocationInstruction.bootstrap.metadata.version.toString()
    }
    PackageVersion.assertFromString(version)
  }

  // Map from module name to package containing that module
  private val modulePackages: Map[String, Package] = Map(
    "Splice.Amulet" -> Package.SpliceAmulet,
    "Splice.AmuletRules" -> Package.SpliceAmulet,
    "Splice.AmuletImport" -> Package.SpliceAmulet,
    "Splice.DecentralizedSynchronizer" -> Package.SpliceAmulet,
    "Splice.ExternalPartyAmuletRules" -> Package.SpliceAmulet,
    "Splice.ValidatorLicense" -> Package.SpliceAmulet,
    "Splice.Round" -> Package.SpliceAmulet,
    "Splice.Ans" -> Package.SpliceAmuletNameService,
    "Splice.DsoBootstrap" -> Package.SpliceDsoGovernance,
    "Splice.DsoRules" -> Package.SpliceDsoGovernance,
    "Splice.DSO.AmuletPrice" -> Package.SpliceDsoGovernance,
    "Splice.DSO.SvState" -> Package.SpliceDsoGovernance,
    "Splice.SvOnboarding" -> Package.SpliceDsoGovernance,
    "Splice.ValidatorOnboarding" -> Package.SpliceValidatorLifecycle,
    "Splice.Wallet.Install" -> Package.SpliceWallet,
    "Splice.Wallet.TopUpState" -> Package.SpliceWallet,
    "Splice.Wallet.TransferOffer" -> Package.SpliceWallet,
    "Splice.Wallet.Payment" -> Package.SpliceWalletPayments,
    "Splice.Wallet.Subscriptions" -> Package.SpliceWalletPayments,
    "Splice.Wallet.ExternalParty" -> Package.SpliceWallet,
    "Splice.Wallet.TransferPreapproval" -> Package.SpliceWallet,
    "Splice.Api.Token.MetadataV1" -> Package.TokenStandard.TokenMetadata,
    "Splice.Api.Token.HoldingV1" -> Package.TokenStandard.TokenHolding,
    "Splice.Api.Token.TransferInstructionV1" -> Package.TokenStandard.TokenTransferInstruction,
    "Splice.Api.Token.AllocationV1" -> Package.TokenStandard.TokenAllocation,
    "Splice.Api.Token.AllocationRequestV1" -> Package.TokenStandard.TokenAllocationRequest,
    "Splice.Api.Token.AllocationInstructionV1" -> Package.TokenStandard.TokenAllocationInstruction,
  )

  sealed abstract class Package extends Product with Serializable {
    def packageName = {
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
      final case object TokenMetadata extends Package
      final case object TokenHolding extends Package
      final case object TokenTransferInstruction extends Package
      final case object TokenAllocation extends Package
      final case object TokenAllocationRequest extends Package
      final case object TokenAllocationInstruction extends Package
    }
    final case object SpliceAmulet extends Package
    final case object SpliceAmuletNameService extends Package
    final case object SpliceDsoGovernance extends Package
    final case object SpliceValidatorLifecycle extends Package
    final case object SpliceWallet extends Package
    final case object SpliceWalletPayments extends Package
  }

  def supportsValidatorLicenseMetadata(now: CantonTimestamp, amuletRules: AmuletRules): Boolean = {
    val currentConfig = AmuletConfigSchedule(amuletRules).getConfigAsOf(now)
    val spliceAmuletVersion = PackageVersion.assertFromString(currentConfig.packageConfig.amulet)
    spliceAmuletVersion >= DarResources.amulet_0_1_3.metadata.version
  }

  def supportsValidatorLicenseActivity(now: CantonTimestamp, amuletRules: AmuletRules): Boolean =
    supportsValidatorLicenseMetadata(now, amuletRules)

  def supportsPruneAmuletConfigSchedule(now: CantonTimestamp, amuletRules: AmuletRules): Boolean = {
    val currentConfig = AmuletConfigSchedule(amuletRules).getConfigAsOf(now)
    val dsoGovernanceVersion =
      PackageVersion.assertFromString(currentConfig.packageConfig.dsoGovernance)
    dsoGovernanceVersion >= DarResources.dsoGovernance_0_1_5.metadata.version
  }

  def supportsMergeDuplicatedValidatorLicense(
      now: CantonTimestamp,
      amuletRules: AmuletRules,
  ): Boolean = {
    val currentConfig = AmuletConfigSchedule(amuletRules).getConfigAsOf(now)
    val dsoGovernanceVersion =
      PackageVersion.assertFromString(currentConfig.packageConfig.dsoGovernance)
    dsoGovernanceVersion >= DarResources.dsoGovernance_0_1_8.metadata.version
  }

  def supportsLegacySequencerConfig(
      now: CantonTimestamp,
      amuletRules: AmuletRules,
  ): Boolean = {
    val currentConfig = AmuletConfigSchedule(amuletRules).getConfigAsOf(now)
    val dsoGovernanceVersion =
      PackageVersion.assertFromString(currentConfig.packageConfig.dsoGovernance)
    dsoGovernanceVersion >= DarResources.dsoGovernance_0_1_7.metadata.version
  }

  def supportsValidatorLivenessActivityRecord(
      now: CantonTimestamp,
      amuletRules: AmuletRules,
  ): Boolean = {
    val currentConfig = AmuletConfigSchedule(amuletRules).getConfigAsOf(now)
    val amuletVersion =
      PackageVersion.assertFromString(currentConfig.packageConfig.amulet)
    amuletVersion >= DarResources.amulet_0_1_5.metadata.version
  }

  def supportsExternalPartyAmuletRules(
      now: CantonTimestamp,
      amuletRules: AmuletRules,
  ): Boolean = {
    val currentConfig = AmuletConfigSchedule(amuletRules).getConfigAsOf(now)
    val amuletVersion =
      PackageVersion.assertFromString(currentConfig.packageConfig.amulet)
    amuletVersion >= DarResources.amulet_0_1_6.metadata.version
  }
}
