package com.daml.network.environment

import com.daml.lf.data.Ref.{PackageName, PackageVersion}
import com.daml.ledger.javaapi.data.{Command, Identifier}
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.amuletrules.AmuletRules
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import com.daml.network.util.{AmuletConfigSchedule, Contract, QualifiedName}

import scala.concurrent.{ExecutionContext, Future}

abstract class PackageIdResolver()(implicit ec: ExecutionContext) {
  def resolvePackageId(
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
              case "CN.Splitwell" => DarResources.splitwell
              case "CN.AppManager.Store" => DarResources.appManager
              case _ => throw new IllegalArgumentException(s"Unknown template $templateId")
            }
          case Some(pkg) =>
            pkg match {
              case Package.CantonAmulet => DarResources.cantonAmulet
              case Package.CantonNameService => DarResources.cantonNameService
              case Package.DsoGovernance => DarResources.dsoGovernance
              case Package.ValidatorLifecycle => DarResources.validatorLifecycle
              case Package.Wallet => DarResources.wallet
              case Package.WalletPayments => DarResources.walletPayments
            }
        }
    }

  /** Infer the package ids based on the current config in AmuletRules.
    * Templates not covered by AmuletRules can be specified in `extraPackageIdResolver`
    * which takes precedence over AmuletRules.
    */
  def inferFromAmuletRules(
      clock: Clock,
      amuletRulesFetcher: HasAmuletRules,
      loggerFactory0: NamedLoggerFactory,
      // Resolver that is checked before all other resolvers. Crucially this one
      // can work even if AmuletRules cannot be fetched, e.g., during bootstrapping
      // when AmuletRules is not yet created.
      bootstrapPackageIdResolver: QualifiedName => Option[String] = _ => None,
      // Resolver that is checked when none of the standard packages match.
      extraPackageIdResolver: (cc.amuletconfig.PackageConfig, QualifiedName) => Option[String] =
        (_, _) => None,
  )(implicit ec: ExecutionContext) = new PackageIdResolver with NamedLogging {

    override val loggerFactory = loggerFactory0

    private def fromAmuletRules(
        packageConfig: cc.amuletconfig.PackageConfig,
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
                s"No package with name ${pkg.packageName} and version ${version} is known"
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
      packageConfig: cc.amuletconfig.PackageConfig,
      pkg: Package,
  ): PackageVersion = {
    import Package.*
    val version = pkg match {
      case CantonAmulet => packageConfig.cantonAmulet
      case CantonNameService => packageConfig.cantonNameService
      case DsoGovernance => packageConfig.dsoGovernance
      case ValidatorLifecycle => packageConfig.validatorLifecycle
      case Wallet => packageConfig.wallet
      case WalletPayments => packageConfig.walletPayments
    }
    PackageVersion.assertFromString(version)
  }

  // Map from module name to package containing that module
  private val modulePackages: Map[String, Package] = Map(
    "CC.Amulet" -> Package.CantonAmulet,
    "CC.AmuletRules" -> Package.CantonAmulet,
    "CC.AmuletImport" -> Package.CantonAmulet,
    "CC.GlobalDomain" -> Package.CantonAmulet,
    "CC.ValidatorLicense" -> Package.CantonAmulet,
    "CC.Round" -> Package.CantonAmulet,
    "CN.Ans" -> Package.CantonNameService,
    "CN.DsoBootstrap" -> Package.DsoGovernance,
    "CN.DsoRules" -> Package.DsoGovernance,
    "CN.DSO.AmuletPrice" -> Package.DsoGovernance,
    "CN.SvOnboarding" -> Package.DsoGovernance,
    "CN.ValidatorOnboarding" -> Package.ValidatorLifecycle,
    "CN.Wallet.Install" -> Package.Wallet,
    "CN.Wallet.TopUpState" -> Package.Wallet,
    "CN.Wallet.TransferOffer" -> Package.Wallet,
    "CN.Wallet.Payment" -> Package.WalletPayments,
    "CN.Wallet.Subscriptions" -> Package.WalletPayments,
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
    final case object CantonAmulet extends Package
    final case object CantonNameService extends Package
    final case object DsoGovernance extends Package
    final case object ValidatorLifecycle extends Package
    final case object Wallet extends Package
    final case object WalletPayments extends Package
  }
}
