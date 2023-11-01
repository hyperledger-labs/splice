package com.daml.network.environment

import com.daml.lf.data.Ref.{PackageName, PackageVersion}
import com.daml.ledger.javaapi.data.{Command, Identifier}
import com.daml.network.codegen.java.cc
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import com.daml.network.util.{CoinConfigSchedule, DarUtil, QualifiedName}

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
  trait HasCoinRulesPayload {
    def getCoinRulesPayload()(implicit tc: TraceContext): Future[cc.coin.CoinRules]
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
              case Package.CantonCoin => DarResources.cantonCoin
              case Package.CantonNameService => DarResources.cantonNameService
              case Package.DirectoryService => DarResources.directoryService
              case Package.SvcGovernance => DarResources.svcGovernance
              case Package.ValidatorLifecycle => DarResources.validatorLifecycle
              case Package.Wallet => DarResources.wallet
              case Package.WalletPayments => DarResources.walletPayments
            }
        }
    }

  /** Infer the package ids based on the current config in CoinRules.
    * Templates not covered by CoinRules can be specified in `extraPackageIdResolver`
    * which takes precedence over CoinRules.
    */
  def inferFromCoinRules(
      clock: Clock,
      coinRulesFetcher: HasCoinRulesPayload,
      loggerFactory0: NamedLoggerFactory,
      extraPackageIdResolver: QualifiedName => Option[String] = _ => None,
  )(implicit ec: ExecutionContext) = new PackageIdResolver with NamedLogging {

    override val loggerFactory = loggerFactory0

    private def fromCoinRules(coinRules: cc.coin.CoinRules, name: QualifiedName): String = {
      val schedule = CoinConfigSchedule(coinRules)
      val config = schedule.getConfigAsOf(clock.now)
      val pkg = modulePackages
        .get(name.moduleName)
        .getOrElse(throw new IllegalArgumentException(s"Unknown template $name"))
      val version = readPackageVersion(config.packageConfig, pkg)
      packageMap
        .get((pkg.packageName, version))
        .fold(
          throw new IllegalArgumentException(
            s"No package with name ${pkg.packageName} and version ${version} is known"
          )
        )(_.packageId)
    }

    override def resolvePackageId(
        name: QualifiedName
    )(implicit tc: TraceContext): Future[String] = {
      val pkgId = extraPackageIdResolver(name) match {
        case None =>
          coinRulesFetcher.getCoinRulesPayload().map { coinRules =>
            fromCoinRules(coinRules, name)
          }
        case Some(pkgId) => Future.successful(pkgId)
      }
      logger.trace(s"Resolving template $name to package id $pkgId")
      pkgId
    }
  }

  def lookupPackage(pkg: Package, version: PackageVersion): Option[DarResource] =
    packageMap.get((pkg.packageName, version))

  // Map from (pkgName, pkgVersion) -> DarResource
  private lazy val packageMap =
    packageResources
      .flatMap(pkgResource => pkgResource.all)
      .map { darResource =>
        val mainDalf =
          scala.util.Using.resource(getClass.getClassLoader.getResourceAsStream(darResource.path)) {
            inputStream =>
              DarUtil.readDar(darResource.path, inputStream).main
          }
        val metadata = mainDalf._2.metadata.getOrElse(
          throw new AssertionError(s"Package is missing metadata which is mandatory in LF >= 1.8")
        )
        ((metadata.name, metadata.version) -> darResource)
      }
      .toMap

  def readPackageVersion(
      packageConfig: cc.coinconfig.PackageConfig,
      pkg: Package,
  ): PackageVersion = {
    import Package.*
    val version = pkg match {
      case CantonCoin => packageConfig.cantonCoin
      case CantonNameService => packageConfig.cantonNameService
      case DirectoryService => packageConfig.directoryService
      case SvcGovernance => packageConfig.svcGovernance
      case ValidatorLifecycle => packageConfig.validatorLifecycle
      case Wallet => packageConfig.wallet
      case WalletPayments => packageConfig.walletPayments
    }
    PackageVersion.assertFromString(version)
  }

  private val packageResources: Seq[PackageResource] =
    Seq(
      DarResources.cantonCoin,
      DarResources.cantonNameService,
      DarResources.directoryService,
      DarResources.svcGovernance,
      DarResources.validatorLifecycle,
      DarResources.wallet,
      DarResources.walletPayments,
    )

  // Map from module name to package containing that module
  private val modulePackages: Map[String, Package] = Map(
    "CC.Coin" -> Package.CantonCoin,
    "CC.CoinImport" -> Package.CantonCoin,
    "CC.GlobalDomain" -> Package.CantonCoin,
    "CC.ValidatorLicense" -> Package.CantonCoin,
    "CC.Round" -> Package.CantonCoin,
    "CN.Cns" -> Package.CantonNameService,
    "CN.Directory" -> Package.DirectoryService,
    "CN.SvcBootstrap" -> Package.SvcGovernance,
    "CN.SvcRules" -> Package.SvcGovernance,
    "CN.SVC.CoinPrice" -> Package.SvcGovernance,
    "CN.SvOnboarding" -> Package.SvcGovernance,
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
      // Turn CantonCoin into canton-coin
      PackageName.assertFromString(
        "[A-Z]".r
          .replaceAllIn(clsName, m => (if (m.start != 0) "-" else "") + m.matched.toLowerCase())
      )
    }
  }

  object Package {
    final case object CantonCoin extends Package
    final case object CantonNameService extends Package
    final case object DirectoryService extends Package
    final case object SvcGovernance extends Package
    final case object ValidatorLifecycle extends Package
    final case object Wallet extends Package
    final case object WalletPayments extends Package
  }
}
