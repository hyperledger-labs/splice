package com.daml.network.environment

import com.daml.lf.data.Ref.{PackageName, PackageVersion}
import com.daml.ledger.javaapi.data.{Command, Identifier}
import com.daml.network.codegen.java.cc
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import com.daml.network.util.{CoinConfigSchedule, DarUtil, QualifiedName}

import java.io.File
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

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

  /** Resolver that always fails. This is useful for conections that are never used for command submissions
    * which is often the case during initialization.
    */
  // TODO(#8018) Split connection types to avoid the runtime errors if you do call a command submission
  // on a connection configured with this.
  def NO_COMMAND_SUBMISSION(implicit ec: ExecutionContext): PackageIdResolver =
    new PackageIdResolver {
      override def resolvePackageId(templateId: QualifiedName)(implicit
          tc: TraceContext
      ): Future[String] =
        Future.failed(
          new IllegalArgumentException(
            "Package id resolver not configured, use a different connection to submit commands"
          )
        )
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
      val version = PackageVersion.assertFromString(readPackageVersion(config.packageConfig, pkg))
      packageMap
        .get((pkg.packageName, version))
        .getOrElse(
          throw new IllegalArgumentException(
            s"No package with name ${pkg.packageName} and version ${version} is known"
          )
        )
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

  // All DAR files in the dar resource folder.
  private lazy val darFiles = getClass.getClassLoader
    .getResources("dar")
    .asScala
    .toSeq
    .flatMap(darFolder => new File(darFolder.toURI).listFiles().toSeq)

  // Map from (pkgName, pkgVersion) -> pkgId
  private lazy val packageMap = darFiles.map { file =>
    val mainDalf = DarUtil.readDar(file).main
    val metadata = mainDalf._2.metadata.getOrElse(
      throw new AssertionError(s"Package is missing metadata which is mandatory in LF >= 1.8")
    )
    ((metadata.name, metadata.version) -> mainDalf._1)
  }.toMap

  private def readPackageVersion(
      packageConfig: cc.coinconfig.PackageConfig,
      pkg: Package,
  ): String = {
    import Package.*
    pkg match {
      case CantonCoin => packageConfig.cantonCoin
      case CantonNameService => packageConfig.cantonNameService
      case DirectoryService => packageConfig.directoryService
      case SvcGovernance => packageConfig.svcGovernance
      case ValidatorLifecycle => packageConfig.validatorLifecycle
      case Wallet => packageConfig.wallet
      case WalletPayments => packageConfig.walletPayments
    }
  }

  // Map from module name to package containing that module
  private val modulePackages: Map[String, Package] = Map(
    "CC.Coin" -> Package.CantonCoin,
    "CC.GlobalDomain" -> Package.CantonCoin,
    "CC.ValidatorLicense" -> Package.CantonCoin,
    "CC.Round" -> Package.CantonCoin,
    "CN.Cns" -> Package.CantonNameService,
    "CN.Directory" -> Package.DirectoryService,
    "CN.SvcBootstrap" -> Package.SvcGovernance,
    "CN.SvcRules" -> Package.SvcGovernance,
    "CN.SvOnboarding" -> Package.SvcGovernance,
    "CN.ValidatorOnboarding" -> Package.ValidatorLifecycle,
    "CN.Wallet.Install" -> Package.Wallet,
    "CN.Wallet.TopUpState" -> Package.Wallet,
    "CN.Wallet.TransferOffer" -> Package.Wallet,
    "CN.Wallet.Payment" -> Package.WalletPayments,
    "CN.Wallet.Subscriptions" -> Package.WalletPayments,
  )

  private sealed abstract class Package extends Product with Serializable {
    def packageName = {
      val clsName = this.productPrefix
      // Turn CantonCoin into canton-coin
      PackageName.assertFromString(
        "[A-Z]".r
          .replaceAllIn(clsName, m => (if (m.start != 0) "-" else "") + m.matched.toLowerCase())
      )
    }
  }

  private object Package {
    final case object CantonCoin extends Package
    final case object CantonNameService extends Package
    final case object DirectoryService extends Package
    final case object SvcGovernance extends Package
    final case object ValidatorLifecycle extends Package
    final case object Wallet extends Package
    final case object WalletPayments extends Package
  }
}
