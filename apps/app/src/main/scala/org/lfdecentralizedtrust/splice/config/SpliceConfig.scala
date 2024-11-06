// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.config

import org.apache.pekko.http.scaladsl.model.Uri
import cats.data.Validated
import cats.syntax.either.*
import cats.syntax.functor.*
import org.lfdecentralizedtrust.splice.auth.AuthConfig
import org.lfdecentralizedtrust.splice.environment.DarResources
import org.lfdecentralizedtrust.splice.http.v0.definitions.{
  AppConfiguration,
  Domain,
  ReleaseConfiguration,
  Timespan,
}
import org.lfdecentralizedtrust.splice.http.UrlValidator
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection.BftScanClientConfig
import org.lfdecentralizedtrust.splice.scan.config.{
  ScanAppBackendConfig,
  ScanAppClientConfig,
  ScanSynchronizerConfig,
}
import org.lfdecentralizedtrust.splice.splitwell.config.{
  SplitwellAppBackendConfig,
  SplitwellAppClientConfig,
  SplitwellDomains,
  SplitwellSynchronizerConfig,
}
import org.lfdecentralizedtrust.splice.sv.config.*
import org.lfdecentralizedtrust.splice.sv.SvAppClientConfig
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingConfig.FoundDso
import org.lfdecentralizedtrust.splice.util.Codec
import org.lfdecentralizedtrust.splice.validator.config.*
import org.lfdecentralizedtrust.splice.wallet.config.{
  AutoAcceptTransfersConfig,
  TransferPreapprovalConfig,
  TreasuryConfig,
  WalletAppClientConfig,
  WalletSweepConfig,
  WalletSynchronizerConfig,
  WalletValidatorAppClientConfig,
}
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.ConfigErrors.CantonConfigError
import com.digitalasset.canton.config.*
import com.digitalasset.canton.config.RequireTypes.NonNegativeNumeric
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.participant.config.{
  CommunityParticipantConfig,
  RemoteParticipantConfig,
}
import com.digitalasset.canton.tracing.TraceContext
import com.typesafe.config.{Config, ConfigRenderOptions}
import org.slf4j.{Logger, LoggerFactory}
import pureconfig.generic.FieldCoproductHint
import pureconfig.{ConfigReader, ConfigWriter}
import pureconfig.error.{CannotConvert, FailureReason}
import pureconfig.module.cats.{nonEmptyListReader, nonEmptyListWriter}
import io.circe.parser.*
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.*
import io.circe.syntax.*

import scala.concurrent.duration.*
import java.io.File
import scala.annotation.nowarn
import scala.util.Try
import scala.util.control.NoStackTrace
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import com.digitalasset.canton.domain.mediator.RemoteMediatorConfig
import com.digitalasset.canton.domain.sequencing.config.RemoteSequencerConfig
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.daml.lf.data.Ref.PackageVersion

case class SpliceConfig(
    override val name: Option[String] = None,
    validatorApps: Map[InstanceName, ValidatorAppBackendConfig] = Map.empty,
    validatorAppClients: Map[InstanceName, ValidatorAppClientConfig] = Map.empty,
    svApps: Map[InstanceName, SvAppBackendConfig] = Map.empty,
    svAppClients: Map[InstanceName, SvAppClientConfig] = Map.empty,
    scanApps: Map[InstanceName, ScanAppBackendConfig] = Map.empty,
    scanAppClients: Map[InstanceName, ScanAppClientConfig] = Map.empty,
    walletAppClients: Map[InstanceName, WalletAppClientConfig] = Map.empty,
    appManagerAppClients: Map[InstanceName, AppManagerAppClientConfig] = Map.empty,
    ansAppExternalClients: Map[InstanceName, AnsAppExternalClientConfig] = Map.empty,
    splitwellApps: Map[InstanceName, SplitwellAppBackendConfig] = Map.empty,
    splitwellAppClients: Map[InstanceName, SplitwellAppClientConfig] = Map.empty,
    // TODO(#736): we want to remove all of the configurations options below:
    participants: Map[InstanceName, CommunityParticipantConfig] = Map.empty,
    remoteParticipants: Map[InstanceName, RemoteParticipantConfig] = Map.empty,
    participantsX: Map[InstanceName, CommunityParticipantConfig] = Map.empty,
    remoteParticipantsX: Map[InstanceName, RemoteParticipantConfig] = Map.empty,
    monitoring: MonitoringConfig = MonitoringConfig(),
    parameters: CantonParameters = CantonParameters(
      timeouts = TimeoutSettings(
        console = ConsoleCommandTimeout(
          bounded = NonNegativeDuration.tryFromDuration(2.minutes),
          requestTimeout = NonNegativeDuration.tryFromDuration(40.seconds),
        )
      )
    ),
    features: CantonFeatures = CantonFeatures(),
    override val pekkoConfig: Option[Config] = None,
) extends CantonConfig // TODO(#736): generalize or fork this trait.
    with ConfigDefaults[DefaultPorts, SpliceConfig] {

  override type ParticipantConfigType = CommunityParticipantConfig

  override def validate: Validated[NonEmpty[Seq[String]], Unit] = Validated.valid(())

  private lazy val validatorAppParameters_ : Map[InstanceName, SharedSpliceAppParameters] =
    validatorApps.fmap { validatorConfig =>
      SharedSpliceAppParameters(
        monitoring.tracing,
        monitoring.delayLoggingThreshold,
        monitoring.logging,
        monitoring.logQueryCost,
        parameters.timeouts.processing,
        parameters.timeouts.console.requestTimeout,
        UpgradesConfig(),
        validatorConfig.parameters.caching,
        parameters.enableAdditionalConsistencyChecks,
        features.enablePreviewCommands,
        parameters.nonStandardConfig,
        validatorConfig.sequencerClient,
        dontWarnOnDeprecatedPV = false,
        dbMigrateAndStart = true,
        batchingConfig = validatorConfig.parameters.batching,
      )
    }

  private[splice] def validatorAppParameters(
      participant: InstanceName
  ): SharedSpliceAppParameters =
    nodeParametersFor(validatorAppParameters_, "participant", participant)

  /** Use `validatorAppParameters`` instead!
    */
  def tryValidatorAppParametersByString(name: String): SharedSpliceAppParameters =
    validatorAppParameters(
      InstanceName.tryCreate(name)
    )

  /** Use `validators` instead!
    */
  def validatorsByString: Map[String, ValidatorAppBackendConfig] = validatorApps.map {
    case (n, c) =>
      n.unwrap -> c
  }

  private lazy val svAppParameters_ : Map[InstanceName, SharedSpliceAppParameters] =
    svApps.fmap { svConfig =>
      SharedSpliceAppParameters(
        monitoring.tracing,
        monitoring.delayLoggingThreshold,
        monitoring.logging,
        monitoring.logQueryCost,
        parameters.timeouts.processing,
        parameters.timeouts.console.requestTimeout,
        UpgradesConfig(),
        svConfig.parameters.caching,
        parameters.enableAdditionalConsistencyChecks,
        features.enablePreviewCommands,
        parameters.nonStandardConfig,
        svConfig.sequencerClient,
        dontWarnOnDeprecatedPV = false,
        dbMigrateAndStart = true,
        batchingConfig = new BatchingConfig(),
      )
    }

  private[splice] def svAppParameters(
      appName: InstanceName
  ): SharedSpliceAppParameters =
    nodeParametersFor(svAppParameters_, "sv-app-backend", appName)

  /** Use `svAppParameters` instead!
    */
  def trySvAppParametersByString(name: String): SharedSpliceAppParameters =
    svAppParameters(
      InstanceName.tryCreate(name)
    )

  /** Use `svs` instead!
    */
  def svsByString: Map[String, SvAppBackendConfig] = svApps.map { case (n, c) =>
    n.unwrap -> c
  }

  private lazy val scanAppParameters_ : Map[InstanceName, SharedSpliceAppParameters] =
    scanApps.fmap { scanConfig =>
      SharedSpliceAppParameters(
        monitoring.tracing,
        monitoring.delayLoggingThreshold,
        monitoring.logging,
        monitoring.logQueryCost,
        parameters.timeouts.processing,
        parameters.timeouts.console.requestTimeout,
        UpgradesConfig(),
        scanConfig.parameters.caching,
        parameters.enableAdditionalConsistencyChecks,
        features.enablePreviewCommands,
        parameters.nonStandardConfig,
        scanConfig.sequencerClient,
        dontWarnOnDeprecatedPV = false,
        dbMigrateAndStart = true,
        batchingConfig = new BatchingConfig(),
      )
    }

  private[splice] def scanAppParameters(
      appName: InstanceName
  ): SharedSpliceAppParameters =
    nodeParametersFor(scanAppParameters_, "scan-app", appName)

  /** Use `scanAppParameters` instead!
    */
  def tryScanAppParametersByString(name: String): SharedSpliceAppParameters =
    scanAppParameters(
      InstanceName.tryCreate(name)
    )

  /** Use `scans` instead!
    */
  def scansByString: Map[String, ScanAppBackendConfig] = scanApps.map { case (n, c) =>
    n.unwrap -> c
  }

  private lazy val splitwellAppParameters_ : Map[InstanceName, SharedSpliceAppParameters] =
    splitwellApps.fmap { splitwellConfig =>
      SharedSpliceAppParameters(
        monitoring.tracing,
        monitoring.delayLoggingThreshold,
        monitoring.logging,
        monitoring.logQueryCost,
        parameters.timeouts.processing,
        parameters.timeouts.console.requestTimeout,
        UpgradesConfig(),
        splitwellConfig.parameters.caching,
        parameters.enableAdditionalConsistencyChecks,
        features.enablePreviewCommands,
        parameters.nonStandardConfig,
        splitwellConfig.sequencerClient,
        dontWarnOnDeprecatedPV = false,
        dbMigrateAndStart = true,
        batchingConfig = new BatchingConfig(),
      )
    }

  private[splice] def splitwellAppParameters(
      appName: InstanceName
  ): SharedSpliceAppParameters =
    nodeParametersFor(splitwellAppParameters_, "splitwell-app", appName)

  /** Use `splitwellAppParameters` instead!
    */
  def trySplitwellAppParametersByString(name: String): SharedSpliceAppParameters =
    splitwellAppParameters(
      InstanceName.tryCreate(name)
    )

  /** Use `splitwells` instead!
    */
  def splitwellsByString: Map[String, SplitwellAppBackendConfig] =
    splitwellApps.map { case (n, c) =>
      n.unwrap -> c
    }

  def splitwellClientsByString: Map[String, SplitwellAppClientConfig] =
    splitwellAppClients.map { case (n, c) =>
      n.unwrap -> c
    }

  override def dumpString: String = {
    val writers = new SpliceConfig.ConfigWriters(confidential = true)
    import writers.*
    ConfigWriter[SpliceConfig].to(this).render(SpliceConfig.defaultConfigRenderer)
  }

  override def withDefaults(ports: DefaultPorts): SpliceConfig =
    this // TODO(#736): CantonCommunityConfig does more here. Do we want to copy that?
  // NOTE(Simon): in particular it handles default ports derived from the ports object introduced in https://github.com/DACH-NY/canton/commit/ccff59fccf349893cc68413a7859e8ef748a94fa

  // TODO(#736): we want to remove these mediator configs

  override def mediators: Map[InstanceName, MediatorNodeConfigType] = Map.empty

  override def remoteMediators: Map[InstanceName, RemoteMediatorConfig] = Map.empty

  override def sequencers: Map[InstanceName, SequencerNodeConfigType] = Map.empty

  override def remoteSequencers: Map[InstanceName, RemoteSequencerConfig] = Map.empty
}

// NOTE: the below is patterned after CantonCommunityConfig.
// In case of changes, recopy from there.
@nowarn("cat=lint-byname-implicit") // https://github.com/scala/bug/issues/12072
object SpliceConfig {

  final case class ConfigValidationFailed(reason: String) extends FailureReason {
    override def description: String = s"Config validation failed: $reason"
  }

  lazy val empty: SpliceConfig = SpliceConfig()

  private val logger: Logger = LoggerFactory.getLogger(classOf[SpliceConfig])
  private[config] val elc = ErrorLoggingContext(
    TracedLogger(logger),
    NamedLoggerFactory.root.properties,
    TraceContext.empty,
  )

  import CantonConfig.*
  import pureconfig.generic.semiauto.*

  class ConfigReaders(implicit
      private val
      elc: ErrorLoggingContext
  ) {
    import CantonConfig.ConfigReaders.*

    implicit val nonNegativeBigDecimalReader: ConfigReader[NonNegativeNumeric[BigDecimal]] =
      NonNegativeNumeric.nonNegativeNumericReader[BigDecimal]

    implicit val authConfigHint: FieldCoproductHint[AuthConfig] =
      new FieldCoproductHint[AuthConfig]("algorithm")

    implicit val hs256UnsafeConfig: ConfigReader[AuthConfig.Hs256Unsafe] =
      deriveReader[AuthConfig.Hs256Unsafe]
    implicit val rs256Config: ConfigReader[AuthConfig.Rs256] =
      deriveReader[AuthConfig.Rs256]
    implicit val authConfig: ConfigReader[AuthConfig] =
      deriveReader[AuthConfig]

    implicit val authTokenSourceConfigHint: FieldCoproductHint[AuthTokenSourceConfig] =
      new FieldCoproductHint[AuthTokenSourceConfig]("type")
    implicit val authTokenSourceNoneReader: ConfigReader[AuthTokenSourceConfig.None] =
      deriveReader[AuthTokenSourceConfig.None]
    implicit val authTokenSourceStaticReader: ConfigReader[AuthTokenSourceConfig.Static] =
      deriveReader[AuthTokenSourceConfig.Static]
    implicit val authTokenSourceSelfSignedReader: ConfigReader[AuthTokenSourceConfig.SelfSigned] =
      deriveReader[AuthTokenSourceConfig.SelfSigned]
    implicit val authTokenSourceCCReader: ConfigReader[AuthTokenSourceConfig.ClientCredentials] =
      deriveReader[AuthTokenSourceConfig.ClientCredentials]
    implicit val authTokenSourceConfigReader: ConfigReader[AuthTokenSourceConfig] =
      deriveReader[AuthTokenSourceConfig]

    implicit val uriReader: ConfigReader[Uri] =
      ConfigReader.fromNonEmptyStringTry(s => Try(Uri.parseAbsolute(s)))
    implicit val networkAppClientConfigReader: ConfigReader[NetworkAppClientConfig] =
      deriveReader[NetworkAppClientConfig]

    implicit val spliceParametersConfig: ConfigReader[SpliceParametersConfig] =
      deriveReader[SpliceParametersConfig]

    implicit val postgresSpliceDbConfigReader: ConfigReader[SpliceDbConfig.Postgres] =
      deriveReader[SpliceDbConfig.Postgres]
    implicit val spliceDbConfigReader: ConfigReader[SpliceDbConfig] = deriveReader[SpliceDbConfig]

    implicit val upgradesConfig: ConfigReader[UpgradesConfig] = deriveReader[UpgradesConfig]

    implicit val automationConfig: ConfigReader[AutomationConfig] =
      deriveReader[AutomationConfig]
    implicit val LedgerApiClientConfigReader: ConfigReader[LedgerApiClientConfig] =
      deriveReader[LedgerApiClientConfig]
    implicit val ParticipantClientConfigReader: ConfigReader[ParticipantClientConfig] =
      deriveReader[ParticipantClientConfig]
    implicit val appInstanceReader: ConfigReader[AppInstance] =
      deriveReader[AppInstance]
    implicit val scanClientConfigConfigHint: FieldCoproductHint[BftScanClientConfig] =
      new FieldCoproductHint[BftScanClientConfig]("type")
    implicit val scanClientConfigTrustSingleConfigReader
        : ConfigReader[BftScanClientConfig.TrustSingle] =
      deriveReader[BftScanClientConfig.TrustSingle]
    implicit val scanClientConfigSeedsConfigReader: ConfigReader[BftScanClientConfig.Bft] =
      deriveReader[BftScanClientConfig.Bft]
    implicit val scanClientConfigConfigReader: ConfigReader[BftScanClientConfig] =
      deriveReader[BftScanClientConfig]
    implicit val scanClientConfigReader: ConfigReader[ScanAppClientConfig] =
      deriveReader[ScanAppClientConfig]
    implicit val domainConfigReader: ConfigReader[SynchronizerConfig] =
      deriveReader[SynchronizerConfig]
    implicit val scanSynchronizerConfig: ConfigReader[ScanSynchronizerConfig] =
      deriveReader[ScanSynchronizerConfig]
    implicit val scanConfigReader: ConfigReader[ScanAppBackendConfig] =
      deriveReader[ScanAppBackendConfig]

    implicit val svClientConfigReader: ConfigReader[SvAppClientConfig] =
      deriveReader[SvAppClientConfig]

    implicit val gcpCredentialsConfigHint: FieldCoproductHint[GcpCredentialsConfig] =
      new FieldCoproductHint[GcpCredentialsConfig]("type")
    implicit val userCredentialsConfigReader: ConfigReader[GcpCredentialsConfig.User] =
      deriveReader[GcpCredentialsConfig.User]
    implicit val serviceAccountCredentialsConfigReader
        : ConfigReader[GcpCredentialsConfig.ServiceAccount] =
      deriveReader[GcpCredentialsConfig.ServiceAccount]
    implicit val gcpCredentialsConfigReader: ConfigReader[GcpCredentialsConfig] =
      deriveReader[GcpCredentialsConfig]
    implicit val gcpBucketConfig: ConfigReader[GcpBucketConfig] = deriveReader[GcpBucketConfig]
    implicit val participantBootstrapDumpConfigHint
        : FieldCoproductHint[ParticipantBootstrapDumpConfig] =
      new FieldCoproductHint[ParticipantBootstrapDumpConfig]("type")
    implicit val participantBootstrapDumpConfigFileReader
        : ConfigReader[ParticipantBootstrapDumpConfig.File] =
      deriveReader[ParticipantBootstrapDumpConfig.File]
    implicit val participantBootstrapDumpConfigReader
        : ConfigReader[ParticipantBootstrapDumpConfig] =
      deriveReader[ParticipantBootstrapDumpConfig]
    implicit val svBootstrapDumpConfigHint: FieldCoproductHint[SvBootstrapDumpConfig] =
      new FieldCoproductHint[SvBootstrapDumpConfig]("type")
    implicit val svBootstrapDumpConfigFileReader: ConfigReader[SvBootstrapDumpConfig.File] =
      deriveReader[SvBootstrapDumpConfig.File]
    implicit val svBootstrapDumpConfigGcpReader: ConfigReader[SvBootstrapDumpConfig.Gcp] =
      deriveReader[SvBootstrapDumpConfig.Gcp]
    implicit val svBootstrapDumpConfigReader: ConfigReader[SvBootstrapDumpConfig] =
      deriveReader[SvBootstrapDumpConfig]
    implicit val svCantonIdentifierConfigReader: ConfigReader[SvCantonIdentifierConfig] =
      deriveReader[SvCantonIdentifierConfig]
    implicit val validatorCantonIdentifierConfigReader
        : ConfigReader[ValidatorCantonIdentifierConfig] =
      deriveReader[ValidatorCantonIdentifierConfig]
    implicit val svOnboardingConfigHint: FieldCoproductHint[SvOnboardingConfig] =
      new FieldCoproductHint[SvOnboardingConfig]("type")
    implicit val initialAnsConfigReader: ConfigReader[InitialAnsConfig] =
      deriveReader[InitialAnsConfig]
    implicit val domainFeesConfigReader: ConfigReader[SynchronizerFeesConfig] =
      deriveReader[SynchronizerFeesConfig]
    implicit val svOnboardingFoundDsoReader: ConfigReader[SvOnboardingConfig.FoundDso] =
      deriveReader[SvOnboardingConfig.FoundDso]
    implicit val svOnboardingJoinWithKeyReader: ConfigReader[SvOnboardingConfig.JoinWithKey] =
      deriveReader[SvOnboardingConfig.JoinWithKey]
    private implicit val initialPackageConfigDecoder
        : Decoder[SvOnboardingConfig.InitialPackageConfig] =
      deriveDecoder
    implicit val svOnboardingInitialPackageConfigReader
        : ConfigReader[SvOnboardingConfig.InitialPackageConfig] =
      ConfigReader.fromNonEmptyStringTry(s =>
        parse(s).toTry.flatMap {
          _.as[SvOnboardingConfig.InitialPackageConfig].toTry
        }
      )
    implicit val svOnboardingDomainMigrationReader
        : ConfigReader[SvOnboardingConfig.DomainMigration] =
      deriveReader[SvOnboardingConfig.DomainMigration]
    implicit val svOnboardingConfigReader: ConfigReader[SvOnboardingConfig] =
      deriveReader[SvOnboardingConfig]
    implicit val expectedValidatorOnboardingConfigReader
        : ConfigReader[ExpectedValidatorOnboardingConfig] =
      deriveReader[ExpectedValidatorOnboardingConfig]
    implicit val approvedSvIdentityConfigReader: ConfigReader[ApprovedSvIdentityConfig] =
      deriveReader[ApprovedSvIdentityConfig]
    implicit val cometBftConfigReader: ConfigReader[CometBftConfig] = deriveReader
    implicit val sequencerPruningConfig: ConfigReader[SequencerPruningConfig] =
      deriveReader[SequencerPruningConfig]
    implicit val svSequencerConfig: ConfigReader[SvSequencerConfig] = {
      // Somehow the implicit search seems to have trouble finding the sequencer pruning config implicit.
      // Redefining it here works though.
      @nowarn("cat=unused")
      implicit val sequencerPruningConfig2 = sequencerPruningConfig
      deriveReader[SvSequencerConfig]
        .emap { sequencerConfig =>
          UrlValidator
            .isValid(sequencerConfig.externalPublicApiUrl)
            .bimap(
              invalidUrl =>
                ConfigValidationFailed(s"Sequencer external url is not valid: $invalidUrl"),
              _ => sequencerConfig,
            )
        }
    }
    implicit val svMediatorConfig: ConfigReader[SvMediatorConfig] =
      deriveReader[SvMediatorConfig]
    implicit val svScanConfig: ConfigReader[SvScanConfig] =
      deriveReader[SvScanConfig]
    implicit val svSynchronizerNodeConfig: ConfigReader[SvSynchronizerNodeConfig] =
      deriveReader[SvSynchronizerNodeConfig]
    implicit val svDecentralizedSynchronizerConfigReader
        : ConfigReader[SvDecentralizedSynchronizerConfig] =
      deriveReader[SvDecentralizedSynchronizerConfig]
    implicit val svSynchronizerConfigReader: ConfigReader[SvSynchronizerConfig] =
      deriveReader[SvSynchronizerConfig]
    implicit val spliceInstanceNamesConfigReader: ConfigReader[SpliceInstanceNamesConfig] =
      deriveReader[SpliceInstanceNamesConfig]
    implicit val backupDumpConfigHint: FieldCoproductHint[PeriodicBackupDumpConfig] =
      new FieldCoproductHint[PeriodicBackupDumpConfig]("type")
    implicit val backupDumpConfigDirectoryReader: ConfigReader[BackupDumpConfig.Directory] =
      deriveReader[BackupDumpConfig.Directory]
    implicit val backupDumpConfigGcpReader: ConfigReader[BackupDumpConfig.Gcp] =
      deriveReader[BackupDumpConfig.Gcp]
    implicit val backupDumpConfigReader: ConfigReader[BackupDumpConfig] =
      deriveReader[BackupDumpConfig]
    implicit val periodicBackupDumpConfigReader: ConfigReader[PeriodicBackupDumpConfig] =
      deriveReader[PeriodicBackupDumpConfig]
    implicit val partyIdConfigReader: ConfigReader[PartyId] = ConfigReader.fromString(str =>
      Codec.decode(Codec.Party)(str).left.map(err => CannotConvert(str, "PartyId", err))
    )
    implicit val beneficiaryConfigReader: ConfigReader[BeneficiaryConfig] =
      deriveReader[BeneficiaryConfig]
    implicit val svConfigReader: ConfigReader[SvAppBackendConfig] =
      deriveReader[SvAppBackendConfig].emap { conf =>
        def checkFoundDsoConfig(check: (SvAppBackendConfig, FoundDso) => Boolean) =
          conf.onboarding.fold(true) {
            case foundDso: SvOnboardingConfig.FoundDso => check(conf, foundDso)
            case _: SvOnboardingConfig.JoinWithKey => true
            case _: SvOnboardingConfig.DomainMigration => true
          }
        // We support joining nodes without sequencers/mediators but
        // sv1 must alway configure one to bootstrap the domain.
        val sv1NodeHasSynchronizerConfig =
          checkFoundDsoConfig((conf, _) => conf.localSynchronizerNode.isDefined)
        val initialPackageConfigExists =
          checkFoundDsoConfig((_, foundDsoConf) =>
            doesInitialPackageConfigExists(foundDsoConf.initialPackageConfig)
          )
        val validInitialPackageConfigDependencies =
          checkFoundDsoConfig((_, foundDsoConf) =>
            validateInitialPackageConfigDependencies(foundDsoConf.initialPackageConfig)
          )
        for {
          _ <- Either.cond(
            sv1NodeHasSynchronizerConfig,
            (),
            ConfigValidationFailed("SV1 must always specify a domain config"),
          )
          _ <- Either.cond(
            initialPackageConfigExists,
            (),
            ConfigValidationFailed(
              "Some initialPackageConfig version(s) cannot be found in DarResources"
            ),
          )
          _ <- Either.cond(
            validInitialPackageConfigDependencies,
            (),
            ConfigValidationFailed(
              "initialPackageConfig is not valid due to inconsistent dependencies"
            ),
          )
          _ <- Either.cond(
            conf.synchronizerNodes.isEmpty || conf.supportsSoftDomainMigrationPoc,
            (),
            ConfigValidationFailed(
              "synchronizerNodes must be empty unless supportsSoftDomainMigrationPoc is set to true"
            ),
          )
          _ <- Either.cond(
            conf.legacyMigrationId.forall(_ == conf.domainMigrationId - 1L),
            (),
            ConfigValidationFailed(
              "legacyMigrationId must equal to domainMigrationId - 1 unless legacyMigrationId is empty"
            ),
          )
        } yield conf
      }

    implicit val spliceAppParametersReader: ConfigReader[SharedSpliceAppParameters] =
      deriveReader[SharedSpliceAppParameters]
    implicit val validatorOnboardingConfigReader: ConfigReader[ValidatorOnboardingConfig] =
      deriveReader[ValidatorOnboardingConfig]
    implicit val treasuryConfigReader: ConfigReader[TreasuryConfig] =
      deriveReader[TreasuryConfig]
    implicit val buyExtraTrafficConfigReader: ConfigReader[BuyExtraTrafficConfig] =
      deriveReader[BuyExtraTrafficConfig]
    implicit val walletSweepConfigReader: ConfigReader[WalletSweepConfig] =
      deriveReader[WalletSweepConfig]
    implicit val autoAcceptTransfersConfigReader: ConfigReader[AutoAcceptTransfersConfig] =
      deriveReader[AutoAcceptTransfersConfig]
    implicit val validatorDecentralizedSynchronizerConfigReader
        : ConfigReader[ValidatorDecentralizedSynchronizerConfig] =
      deriveReader[ValidatorDecentralizedSynchronizerConfig].emap(config => {
        val trafficPurchasedOnEachTopup =
          config.buyExtraTraffic.targetThroughput.value * config.buyExtraTraffic.minTopupInterval.duration.toSeconds
        val reservedTraffic = config.reservedTrafficO.fold(0L)(_.value)
        Either.cond(
          // config is valid if either the validator is not configured to do top-ups
          // or the reserved traffic is less than the traffic purchased per top-up
          trafficPurchasedOnEachTopup == 0 || reservedTraffic < trafficPurchasedOnEachTopup,
          config,
          ConfigValidationFailed(
            s"The target-throughput times the min-topup-interval in the buy-extra-traffic config (currently: $trafficPurchasedOnEachTopup) " +
              s"must be greater than the reserved-traffic (currently: $reservedTraffic)"
          ),
        )
      })
    implicit val validatorExtraSynchronizerConfigReader
        : ConfigReader[ValidatorExtraSynchronizerConfig] =
      deriveReader[ValidatorExtraSynchronizerConfig]
    implicit val validatorSynchronizerConfigReader: ConfigReader[ValidatorSynchronizerConfig] =
      deriveReader[ValidatorSynchronizerConfig]
    implicit val offsetDateTimeConfigurationReader: ConfigReader[java.time.OffsetDateTime] =
      implicitly[ConfigReader[String]].map(java.time.OffsetDateTime.parse)
    implicit val timespanConfigurationReader: ConfigReader[Timespan] = deriveReader[Timespan]
    implicit val domainConfigurationReader: ConfigReader[Domain] = deriveReader[Domain]
    implicit val releaseConfigurationReader: ConfigReader[ReleaseConfiguration] =
      deriveReader[ReleaseConfiguration]
    implicit val appConfigurationReader: ConfigReader[AppConfiguration] =
      deriveReader[AppConfiguration]
    implicit val initialRegisteredAppReader: ConfigReader[InitialRegisteredApp] =
      deriveReader[InitialRegisteredApp]
    implicit val initialInstalledAppReader: ConfigReader[InitialInstalledApp] =
      deriveReader[InitialInstalledApp]
    implicit val appManagerConfigReader: ConfigReader[AppManagerConfig] =
      deriveReader[AppManagerConfig]
    implicit val transferPreapprovalConfigReader: ConfigReader[TransferPreapprovalConfig] =
      deriveReader[TransferPreapprovalConfig].emap { conf =>
        Either.cond(
          conf.renewalDuration.duration.toSeconds < conf.preapprovalLifetime.duration.toSeconds,
          conf,
          ConfigValidationFailed(
            "renewalDuration must be smaller than preapprovalLifetime for TransferPreapprovals"
          ),
        )
      }
    implicit val migrateValidatorPartyConfigReader: ConfigReader[MigrateValidatorPartyConfig] =
      deriveReader[MigrateValidatorPartyConfig]
    implicit val validatorConfigReader: ConfigReader[ValidatorAppBackendConfig] =
      deriveReader[ValidatorAppBackendConfig].emap { conf =>
        for {
          _ <- Either.cond(
            !conf.svValidator || conf.validatorPartyHint.isEmpty,
            (),
            ConfigValidationFailed("Validator party hint must not be specified for SV validators"),
          )
          _ <- Either.cond(
            conf.svValidator || conf.validatorPartyHint.isDefined,
            (),
            ConfigValidationFailed("Validator party hint must be specified for non-SV validators"),
          )
        } yield conf
      }
    implicit val validatorClientConfigReader: ConfigReader[ValidatorAppClientConfig] =
      deriveReader[ValidatorAppClientConfig]
    implicit val walletvalidatorClientConfigReader: ConfigReader[WalletValidatorAppClientConfig] =
      deriveReader[WalletValidatorAppClientConfig]
    implicit val walletSynchronizerConfigReader: ConfigReader[WalletSynchronizerConfig] =
      deriveReader[WalletSynchronizerConfig]
    implicit val WalletAppClientConfigReader: ConfigReader[WalletAppClientConfig] =
      deriveReader[WalletAppClientConfig]
    implicit val AppManagerAppClientConfigReader: ConfigReader[AppManagerAppClientConfig] =
      deriveReader[AppManagerAppClientConfig]
    implicit val ansExternalClientConfigReader: ConfigReader[AnsAppExternalClientConfig] =
      deriveReader[AnsAppExternalClientConfig]
    implicit val splitwellDomainsReader: ConfigReader[SplitwellDomains] =
      deriveReader[SplitwellDomains]
    implicit val splitwellSynchronizerConfigReader: ConfigReader[SplitwellSynchronizerConfig] =
      deriveReader[SplitwellSynchronizerConfig]
    implicit val splitwellConfigReader: ConfigReader[SplitwellAppBackendConfig] =
      deriveReader[SplitwellAppBackendConfig]
    implicit val splitwellClientConfigReader: ConfigReader[SplitwellAppClientConfig] =
      deriveReader[SplitwellAppClientConfig]

    implicit val communityParticipantConfigReader: ConfigReader[CommunityParticipantConfig] =
      deriveReader[CommunityParticipantConfig]

    implicit val spliceConfigReader: ConfigReader[SpliceConfig] = deriveReader[SpliceConfig]
  }

  @nowarn("cat=unused")
  class ConfigWriters(confidential: Boolean) {
    val writers = new CantonConfig.ConfigWriters(confidential)

    import writers.*
    import DeprecatedConfigUtils.*

    implicit val nonNegativeBigDecimalWriter: ConfigWriter[NonNegativeNumeric[BigDecimal]] =
      ConfigWriter.toString(x => x.unwrap.toString)

    // Use a `confidentialWriter` if a config can contain confidential values!
    // Also consider revisiting if the "leaked secrets check" in
    // `.circleci/canton-scripts/check-logs.sh` catches the new type of secret.

    implicit val authConfigHint: FieldCoproductHint[AuthConfig] =
      new FieldCoproductHint[AuthConfig]("algorithm")

    implicit val hs256UnsafeConfig: ConfigWriter[AuthConfig.Hs256Unsafe] =
      deriveWriter[AuthConfig.Hs256Unsafe]
    implicit val rs256Config: ConfigWriter[AuthConfig.Rs256] =
      deriveWriter[AuthConfig.Rs256]
    implicit val authConfig: ConfigWriter[AuthConfig] =
      confidentialWriter[AuthConfig](AuthConfig.hideConfidential)

    implicit val spliceParametersConfig: ConfigWriter[SpliceParametersConfig] =
      deriveWriter[SpliceParametersConfig]

    implicit val authTokenSourceConfigHint: FieldCoproductHint[AuthTokenSourceConfig] =
      new FieldCoproductHint[AuthTokenSourceConfig]("type")
    implicit val authTokenSourceNoneWriter: ConfigWriter[AuthTokenSourceConfig.None] =
      deriveWriter[AuthTokenSourceConfig.None]
    implicit val authTokenSourceStaticWriter: ConfigWriter[AuthTokenSourceConfig.Static] =
      deriveWriter[AuthTokenSourceConfig.Static]
    implicit val authTokenSourceSelfSignedWriter: ConfigWriter[AuthTokenSourceConfig.SelfSigned] =
      deriveWriter[AuthTokenSourceConfig.SelfSigned]
    implicit val authTokenSourceCCWriter: ConfigWriter[AuthTokenSourceConfig.ClientCredentials] =
      deriveWriter[AuthTokenSourceConfig.ClientCredentials]
    implicit val authTokenSourceConfigWriter: ConfigWriter[AuthTokenSourceConfig] =
      confidentialWriter[AuthTokenSourceConfig](AuthTokenSourceConfig.hideConfidential)

    implicit val uriConfigWriter: ConfigWriter[Uri] =
      ConfigWriter.stringConfigWriter.contramap(_.toString())
    implicit val networkAppClientConfigReader: ConfigWriter[NetworkAppClientConfig] =
      deriveWriter[NetworkAppClientConfig]

    implicit val postgresSpliceDbConfigWriter: ConfigWriter[SpliceDbConfig.Postgres] =
      confidentialWriter[SpliceDbConfig.Postgres](pg =>
        pg.copy(config = DbConfig.hideConfidential(pg.config))
      )
    implicit val spliceDbConfigWriter: ConfigWriter[SpliceDbConfig] = deriveWriter[SpliceDbConfig]

    implicit val upgradesConfig: ConfigWriter[UpgradesConfig] = deriveWriter[UpgradesConfig]

    implicit val automationConfig: ConfigWriter[AutomationConfig] =
      deriveWriter[AutomationConfig]
    implicit val LedgerApiClientConfigWriter: ConfigWriter[LedgerApiClientConfig] =
      deriveWriter[LedgerApiClientConfig]
    implicit val ParticipantClientConfigWriter: ConfigWriter[ParticipantClientConfig] =
      deriveWriter[ParticipantClientConfig]
    implicit val appInstanceWriter: ConfigWriter[AppInstance] =
      deriveWriter[AppInstance]
    implicit val scanClientConfigConfigHint: FieldCoproductHint[BftScanClientConfig] =
      new FieldCoproductHint[BftScanClientConfig]("type")
    implicit val scanClientConfigTrustSingleConfigWriter
        : ConfigWriter[BftScanClientConfig.TrustSingle] =
      deriveWriter[BftScanClientConfig.TrustSingle]
    implicit val scanClientConfigSeedsConfigWriter: ConfigWriter[BftScanClientConfig.Bft] =
      deriveWriter[BftScanClientConfig.Bft]
    implicit val scanClientConfigConfigWriter: ConfigWriter[BftScanClientConfig] =
      deriveWriter[BftScanClientConfig]
    implicit val scanClientConfigWriter: ConfigWriter[ScanAppClientConfig] =
      deriveWriter[ScanAppClientConfig]
    implicit val scanSynchronizerConfig: ConfigWriter[ScanSynchronizerConfig] =
      deriveWriter[ScanSynchronizerConfig]
    implicit val scanConfigWriter: ConfigWriter[ScanAppBackendConfig] =
      deriveWriter[ScanAppBackendConfig]

    implicit val svClientConfigWriter: ConfigWriter[SvAppClientConfig] =
      deriveWriter[SvAppClientConfig]

    implicit val gcpCredentialsConfigHint: FieldCoproductHint[GcpCredentialsConfig] =
      new FieldCoproductHint[GcpCredentialsConfig]("type")
    implicit val userCredentialsConfigWriter: ConfigWriter[GcpCredentialsConfig.User] =
      deriveWriter[GcpCredentialsConfig.User]
    implicit val serviceAccountCredentialsConfigWriter
        : ConfigWriter[GcpCredentialsConfig.ServiceAccount] =
      deriveWriter[GcpCredentialsConfig.ServiceAccount]
    implicit val gcpCredentialsConfigWriter: ConfigWriter[GcpCredentialsConfig] =
      confidentialWriter[GcpCredentialsConfig](GcpCredentialsConfig.hideConfidential)
    implicit val gcpBucketConfig: ConfigWriter[GcpBucketConfig] = deriveWriter[GcpBucketConfig]
    implicit val participantBootstrapDumpConfigHint
        : FieldCoproductHint[ParticipantBootstrapDumpConfig] =
      new FieldCoproductHint[ParticipantBootstrapDumpConfig]("type")
    implicit val participantBootstrapDumpConfigFileWriter
        : ConfigWriter[ParticipantBootstrapDumpConfig.File] =
      deriveWriter[ParticipantBootstrapDumpConfig.File]
    implicit val participantBootstrapDumpConfigWriter
        : ConfigWriter[ParticipantBootstrapDumpConfig] =
      deriveWriter[ParticipantBootstrapDumpConfig]
    implicit val svBootstrapDumpConfigHint: FieldCoproductHint[SvBootstrapDumpConfig] =
      new FieldCoproductHint[SvBootstrapDumpConfig]("type")
    implicit val svBootstrapDumpConfigFileWriter: ConfigWriter[SvBootstrapDumpConfig.File] =
      deriveWriter[SvBootstrapDumpConfig.File]
    implicit val svBootstrapDumpConfigGcpWriter: ConfigWriter[SvBootstrapDumpConfig.Gcp] =
      deriveWriter[SvBootstrapDumpConfig.Gcp]
    implicit val svBootstrapDumpConfigWriter: ConfigWriter[SvBootstrapDumpConfig] =
      deriveWriter[SvBootstrapDumpConfig]
    implicit val svCantonIdentifierConfigWriter: ConfigWriter[SvCantonIdentifierConfig] =
      deriveWriter[SvCantonIdentifierConfig]
    implicit val validatorCantonIdentifierConfigWriter
        : ConfigWriter[ValidatorCantonIdentifierConfig] =
      deriveWriter[ValidatorCantonIdentifierConfig]
    implicit val svOnboardingConfigHint: FieldCoproductHint[SvOnboardingConfig] =
      new FieldCoproductHint[SvOnboardingConfig]("type")
    implicit val initialAnsConfigWriter: ConfigWriter[InitialAnsConfig] =
      deriveWriter[InitialAnsConfig]
    implicit val domainFeesConfigWriter: ConfigWriter[SynchronizerFeesConfig] =
      deriveWriter[SynchronizerFeesConfig]
    implicit val svOnboardingFoundDsoWriter: ConfigWriter[SvOnboardingConfig.FoundDso] =
      deriveWriter[SvOnboardingConfig.FoundDso]
    implicit val svOnboardingJoinWithKeyWriter: ConfigWriter[SvOnboardingConfig.JoinWithKey] =
      deriveWriter[SvOnboardingConfig.JoinWithKey]
    private implicit val initialPackageConfigEncoder
        : Encoder[SvOnboardingConfig.InitialPackageConfig] =
      deriveEncoder
    implicit val svOnboardingInitialPackageConfigWriter
        : ConfigWriter[SvOnboardingConfig.InitialPackageConfig] =
      ConfigWriter.stringConfigWriter.contramap(_.asJson.noSpaces)
    implicit val svOnboardingDomainMigrationWriter
        : ConfigWriter[SvOnboardingConfig.DomainMigration] =
      deriveWriter[SvOnboardingConfig.DomainMigration]
    implicit val svOnboardingConfigWriter: ConfigWriter[SvOnboardingConfig] =
      confidentialWriter[SvOnboardingConfig](SvOnboardingConfig.hideConfidential)

    implicit val expectedValidatorOnboardingConfigWriter
        : ConfigWriter[ExpectedValidatorOnboardingConfig] =
      confidentialWriter[ExpectedValidatorOnboardingConfig](
        ExpectedValidatorOnboardingConfig.hideConfidential
      )
    implicit val approvedSvIdentityConfigWriter: ConfigWriter[ApprovedSvIdentityConfig] =
      deriveWriter[ApprovedSvIdentityConfig]
    implicit val cometBftConfigWriter: ConfigWriter[CometBftConfig] = deriveWriter
    implicit val svSequencerConfig: ConfigWriter[SvSequencerConfig] =
      deriveWriter[SvSequencerConfig]
    implicit val sequencerPruningConfig: ConfigWriter[SequencerPruningConfig] =
      deriveWriter[SequencerPruningConfig]
    implicit val svMediatorConfig: ConfigWriter[SvMediatorConfig] =
      deriveWriter[SvMediatorConfig]
    implicit val svScanConfig: ConfigWriter[SvScanConfig] =
      deriveWriter[SvScanConfig]
    implicit val svSynchronizerNodeConfig: ConfigWriter[SvSynchronizerNodeConfig] =
      deriveWriter[SvSynchronizerNodeConfig]
    implicit val spliceInstanceNamesConfigWriter: ConfigWriter[SpliceInstanceNamesConfig] =
      deriveWriter[SpliceInstanceNamesConfig]
    implicit val svDecentralizedSynchronizerConfigWriter
        : ConfigWriter[SvDecentralizedSynchronizerConfig] =
      deriveWriter[SvDecentralizedSynchronizerConfig]
    implicit val svSynchronizerConfigWriter: ConfigWriter[SvSynchronizerConfig] =
      deriveWriter[SvSynchronizerConfig]
    implicit val backupDumpConfigHint: FieldCoproductHint[PeriodicBackupDumpConfig] =
      new FieldCoproductHint[PeriodicBackupDumpConfig]("type")
    implicit val backupDumpConfigDirectoryWriter: ConfigWriter[BackupDumpConfig.Directory] =
      deriveWriter[BackupDumpConfig.Directory]
    implicit val backupDumpConfigGcpWriter: ConfigWriter[BackupDumpConfig.Gcp] =
      deriveWriter[BackupDumpConfig.Gcp]
    implicit val backupDumpConfigWriter: ConfigWriter[BackupDumpConfig] =
      deriveWriter[BackupDumpConfig]
    implicit val periodicBackupDumpConfigWriter: ConfigWriter[PeriodicBackupDumpConfig] =
      deriveWriter[PeriodicBackupDumpConfig]
    implicit val partyIdConfigWriter: ConfigWriter[PartyId] =
      implicitly[ConfigWriter[String]].contramap(_.toString)
    implicit val beneficiaryConfigWriter: ConfigWriter[BeneficiaryConfig] =
      deriveWriter[BeneficiaryConfig]
    implicit val svConfigWriter: ConfigWriter[SvAppBackendConfig] =
      deriveWriter[SvAppBackendConfig]

    implicit val spliceAppParametersWriter: ConfigWriter[SharedSpliceAppParameters] =
      deriveWriter[SharedSpliceAppParameters]
    implicit val domainConfigWriter: ConfigWriter[SynchronizerConfig] =
      deriveWriter[SynchronizerConfig]

    implicit val validatorOnboardingConfigWriter: ConfigWriter[ValidatorOnboardingConfig] =
      confidentialWriter[ValidatorOnboardingConfig](ValidatorOnboardingConfig.hideConfidential)
    implicit val buyExtraTrafficWriter: ConfigWriter[BuyExtraTrafficConfig] =
      deriveWriter[BuyExtraTrafficConfig]
    implicit val walletSweepConfigWriter: ConfigWriter[WalletSweepConfig] =
      deriveWriter[WalletSweepConfig]
    implicit val autoAcceptTransfersConfigWriter: ConfigWriter[AutoAcceptTransfersConfig] =
      deriveWriter[AutoAcceptTransfersConfig]
    implicit val validatorDecentralizedSynchronizerConfigWriter
        : ConfigWriter[ValidatorDecentralizedSynchronizerConfig] =
      deriveWriter[ValidatorDecentralizedSynchronizerConfig]
    implicit val validatorExtraSynchronizerConfigWriter
        : ConfigWriter[ValidatorExtraSynchronizerConfig] =
      deriveWriter[ValidatorExtraSynchronizerConfig]
    implicit val validatorSynchronizerConfigWriter: ConfigWriter[ValidatorSynchronizerConfig] =
      deriveWriter[ValidatorSynchronizerConfig]
    implicit val offsetDateTimeConfigurationWriter: ConfigWriter[java.time.OffsetDateTime] =
      implicitly[ConfigWriter[String]].contramap(_.toString)
    implicit val timespanConfigurationWriter: ConfigWriter[Timespan] = deriveWriter[Timespan]
    implicit val domainConfigurationWriter: ConfigWriter[Domain] = deriveWriter[Domain]
    implicit val releaseConfigurationWriter: ConfigWriter[ReleaseConfiguration] =
      deriveWriter[ReleaseConfiguration]
    implicit val appConfigurationWriter: ConfigWriter[AppConfiguration] =
      deriveWriter[AppConfiguration]
    implicit val initialRegisteredAppWriter: ConfigWriter[InitialRegisteredApp] =
      deriveWriter[InitialRegisteredApp]
    implicit val initialInstalledAppWriter: ConfigWriter[InitialInstalledApp] =
      deriveWriter[InitialInstalledApp]
    implicit val appManagerConfigWriter: ConfigWriter[AppManagerConfig] =
      deriveWriter[AppManagerConfig]
    implicit val transferPreapprovalConfigWriter: ConfigWriter[TransferPreapprovalConfig] =
      deriveWriter[TransferPreapprovalConfig]
    implicit val migrateValidatorPartyConfigWriter: ConfigWriter[MigrateValidatorPartyConfig] =
      deriveWriter[MigrateValidatorPartyConfig]
    implicit val validatorConfigWriter: ConfigWriter[ValidatorAppBackendConfig] =
      deriveWriter[ValidatorAppBackendConfig]
    implicit val validatorClientConfigWriter: ConfigWriter[ValidatorAppClientConfig] =
      deriveWriter[ValidatorAppClientConfig]
    implicit val walletvalidatorClientConfigWriter: ConfigWriter[WalletValidatorAppClientConfig] =
      deriveWriter[WalletValidatorAppClientConfig]
    implicit val treasuryConfigWriter: ConfigWriter[TreasuryConfig] =
      deriveWriter[TreasuryConfig]
    implicit val walletSynchronizerConfigWriter: ConfigWriter[WalletSynchronizerConfig] =
      deriveWriter[WalletSynchronizerConfig]
    implicit val WalletAppClientConfigWriter: ConfigWriter[WalletAppClientConfig] =
      deriveWriter[WalletAppClientConfig]
    implicit val AppManagerAppClientConfigWriter: ConfigWriter[AppManagerAppClientConfig] =
      deriveWriter[AppManagerAppClientConfig]
    implicit val ansExternalClientConfigWriter: ConfigWriter[AnsAppExternalClientConfig] =
      deriveWriter[AnsAppExternalClientConfig]
    implicit val splitwellDomains: ConfigWriter[SplitwellDomains] =
      deriveWriter[SplitwellDomains]
    implicit val splitwellSynchronizerConfigWriter: ConfigWriter[SplitwellSynchronizerConfig] =
      deriveWriter[SplitwellSynchronizerConfig]
    implicit val splitwellConfigWriter: ConfigWriter[SplitwellAppBackendConfig] =
      deriveWriter[SplitwellAppBackendConfig]
    implicit val splitwellClientConfigWriter: ConfigWriter[SplitwellAppClientConfig] =
      deriveWriter[SplitwellAppClientConfig]

    implicit val communityParticipantConfigWriter: ConfigWriter[CommunityParticipantConfig] =
      deriveWriter[CommunityParticipantConfig]

    implicit val spliceConfigWriter: ConfigWriter[SpliceConfig] =
      deriveWriter[SpliceConfig]

  }

  private implicit def configReader(implicit
      elc: ErrorLoggingContext
  ): ConfigReader[SpliceConfig] = {
    val readers = new ConfigReaders()(elc)
    readers.spliceConfigReader
  }

  def load(config: Config)(implicit
      elc: ErrorLoggingContext = elc
  ): Either[CantonConfigError, SpliceConfig] =
    CantonConfig.loadAndValidate[SpliceConfig](config)

  def parseAndLoadOrThrow(files: Seq[File])(implicit
      elc: ErrorLoggingContext = elc
  ): SpliceConfig =
    CantonConfig
      .parseAndLoad[SpliceConfig](files)
      .valueOr(error => throw SpliceConfigException(error))

  def loadOrThrow(config: Config)(implicit elc: ErrorLoggingContext = elc): SpliceConfig = {
    CantonConfig
      .loadAndValidate[SpliceConfig](config)
      .valueOr(error => throw SpliceConfigException(error))
  }

  lazy val defaultConfigRenderer: ConfigRenderOptions =
    ConfigRenderOptions.defaults().setOriginComments(false).setComments(false).setJson(false)

  // Used in scripts/transform-config.sc when spinning up nodes for UI development
  def writeToFile(config: SpliceConfig, path: Path, confidential: Boolean = true): Unit = {
    val writers = new SpliceConfig.ConfigWriters(confidential)
    import writers.*
    val renderer = ConfigRenderOptions
      .defaults()
      .setOriginComments(false)
      .setComments(false)
      .setJson(false)
    val content = "canton { " + ConfigWriter[SpliceConfig]
      .to(config)
      .render(renderer) + "}"
    Files.write(path, content.getBytes(StandardCharsets.UTF_8)).discard
  }

  private def packageResourceToRequiredVersions(
      initialPackageConfig: SvOnboardingConfig.InitialPackageConfig
  ) = Seq(
    DarResources.amulet -> initialPackageConfig.amuletVersion,
    DarResources.amuletNameService -> initialPackageConfig.amuletNameServiceVersion,
    DarResources.dsoGovernance -> initialPackageConfig.dsoGovernanceVersion,
    DarResources.validatorLifecycle -> initialPackageConfig.validatorLifecycleVersion,
    DarResources.wallet -> initialPackageConfig.walletVersion,
    DarResources.walletPayments -> initialPackageConfig.walletPaymentsVersion,
  )

  private def doesInitialPackageConfigExists(
      initialPackageConfig: SvOnboardingConfig.InitialPackageConfig
  ): Boolean = {
    packageResourceToRequiredVersions(initialPackageConfig).forall {
      case (packageResource, version) =>
        packageResource.getDarResource(version).isDefined
    }
  }

  private def validateInitialPackageConfigDependencies(
      initialPackageConfig: SvOnboardingConfig.InitialPackageConfig
  ): Boolean = {
    val packageResourceToVersions = packageResourceToRequiredVersions(initialPackageConfig)
    val mismatchVersionPackageIds =
      packageResourceToVersions.flatMap { case (packageResource, requiredVersion) =>
        val required = PackageVersion.assertFromString(requiredVersion)
        packageResource.others.collect {
          case darResource if darResource.metadata.version != required =>
            darResource.packageId
        }.toSet
      }.toSet
    packageResourceToVersions.forall { case (packageResource, version) =>
      val darResource = packageResource.getDarResource(version)
      darResource.exists(
        // ensure the correct version of dependencies are specified
        _.dependencyPackageIds.intersect(mismatchVersionPackageIds).isEmpty
      )
    }
  }
}

object SpliceConfigException {
  def apply(error: CantonConfigError): SpliceConfigException =
    error.throwableO.fold(new SpliceConfigException(error.cause))(t =>
      new SpliceConfigException(error.cause, t)
    )
}

@SuppressWarnings(Array("org.wartremover.warts.Null"))
final case class SpliceConfigException(message: String, cause: Throwable = null)
    extends RuntimeException(message, cause)
    with NoStackTrace
