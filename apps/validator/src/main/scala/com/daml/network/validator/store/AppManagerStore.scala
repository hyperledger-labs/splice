// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.validator.store

import org.apache.pekko.http.scaladsl.model.Uri
import cats.syntax.either.*
import com.daml.network.codegen.java.splice.appmanager.store as codegen
import com.daml.network.environment.{SpliceLedgerConnection, CommandPriority, RetryProvider}
import com.daml.network.http.v0.definitions
import com.daml.network.scan.admin.api.client.ScanConnection.GetAmuletRulesDomain
import com.daml.network.store.AppStoreWithIngestion
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.util.ContractWithState
import com.daml.network.util.PrettyInstances.*
import com.daml.network.util.JsonUtil.circeJsonToSprayJsValue
import com.daml.network.validator.store.ValidatorStore
import com.digitalasset.canton.crypto.{Hash, HashAlgorithm, HashPurpose}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting, PrettyUtil}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.circe.Decoder
import io.circe.parser.decode
import io.circe.syntax.*
import io.grpc.Status

import scala.concurrent.{ExecutionContext, Future}

/** A validator-local store for the data required by the app manager.
  * This is internally contract-based but the APIs are deliberately do not expose contracts
  * to allow switching this to a plain postgres store later (which is much more efficient).
  * Note that contrary to our contract-based stores this also provides the write endpoints.
  */
final class AppManagerStore(
    getAmuletRulesDomain: GetAmuletRulesDomain,
    storeWithIngestion: AppStoreWithIngestion[ValidatorStore],
    retryProvider: RetryProvider,
    override val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends NamedLogging {
  import AppManagerStore.*

  private val store = storeWithIngestion.store
  private val connection = storeWithIngestion.connection
  private val validator = store.key.validatorParty

  def storeAppRelease(release: AppRelease, priority: CommandPriority = CommandPriority.Low)(implicit
      tc: TraceContext
  ): Future[Unit] =
    idempotentCreate(
      show"app release for ${release.provider} in version ${release.release.version}",
      store
        .lookupAppRelease(release.provider, release.release.version),
      SpliceLedgerConnection.CommandId(
        "com.daml.network.validator.store.storeAppRelease",
        Seq(validator, release.provider),
        release.release.version,
      ),
      new codegen.AppRelease(
        validator.toProtoPrimitive,
        release.provider.toProtoPrimitive,
        release.release.version,
        release.release.asJson.noSpaces,
      ),
      priority,
    )

  def storeAppConfiguration(
      configuration: AppConfiguration,
      priority: CommandPriority = CommandPriority.Low,
  )(implicit
      tc: TraceContext
  ): Future[Unit] =
    idempotentCreate(
      show"app configuration for ${configuration.provider} in version ${configuration.configuration.version}",
      store
        .lookupAppConfiguration(configuration.provider, configuration.configuration.version),
      SpliceLedgerConnection.CommandId(
        "com.daml.network.validator.store.storeAppConfiguration",
        Seq(validator, configuration.provider),
        configuration.configuration.version.toString,
      ),
      new codegen.AppConfiguration(
        validator.toProtoPrimitive,
        configuration.provider.toProtoPrimitive,
        configuration.configuration.version,
        configuration.configuration.asJson.noSpaces,
      ),
      priority,
    )

  def storeRegisteredApp(
      registered: RegisteredApp,
      priority: CommandPriority = CommandPriority.Low,
  )(implicit tc: TraceContext): Future[Unit] =
    idempotentCreate(
      show"registered app ${registered.provider}",
      store.lookupRegisteredApp(registered.provider),
      SpliceLedgerConnection.CommandId(
        "com.daml.network.validator.store.storeRegisteredApp",
        Seq(validator, registered.provider),
      ),
      new codegen.RegisteredApp(
        validator.toProtoPrimitive,
        registered.provider.toProtoPrimitive,
      ),
      priority,
    )

  def storeInstalledApp(installed: InstalledApp)(implicit tc: TraceContext): Future[Unit] =
    idempotentCreate(
      show"installed app ${installed.provider}",
      store.lookupInstalledApp(installed.provider),
      SpliceLedgerConnection.CommandId(
        "com.daml.network.validator.store.storeInstalledApp",
        Seq(validator, installed.provider),
      ),
      new codegen.InstalledApp(
        validator.toProtoPrimitive,
        installed.provider.toProtoPrimitive,
        installed.appUrl.appUrl.toString,
      ),
    )

  def storeApprovedReleaseConfiguration(releaseConfiguration: ApprovedReleaseConfiguration)(implicit
      tc: TraceContext
  ): Future[Unit] = {
    val configHash = hashReleaseConfiguration(releaseConfiguration.releaseConfiguration)
    idempotentCreate(
      show"release configuration ${releaseConfiguration}",
      store.lookupApprovedReleaseConfiguration(
        releaseConfiguration.provider,
        configHash,
      ),
      SpliceLedgerConnection.CommandId(
        "com.daml.network.validator.store.storeApprovedReleaseConfiguration",
        Seq(validator, releaseConfiguration.provider),
        configHash.toHexString,
      ),
      new codegen.ApprovedReleaseConfiguration(
        validator.toProtoPrimitive,
        releaseConfiguration.provider.toProtoPrimitive,
        releaseConfiguration.configurationVersion,
        releaseConfiguration.releaseConfiguration.asJson.noSpaces,
        configHash.toHexString,
      ),
    )
  }

  private def idempotentCreate[TCid, T <: com.daml.ledger.javaapi.data.Template](
      name: String,
      lookup: => Future[QueryResult[Option[ContractWithState[TCid, T]]]],
      commandId: SpliceLedgerConnection.CommandId,
      payload: T,
      priority: CommandPriority = CommandPriority.Low,
  )(implicit tc: TraceContext): Future[Unit] =
    retryProvider
      .retryForClientCalls(
        "fetch_amulet_rules_domain",
        "Fetching global domain from AmuletRules",
        getAmuletRulesDomain()(tc),
        logger,
      )
      .flatMap { domain =>
        retryProvider.retryForClientCalls(
          "create",
          show"Creating $name if it does not already exist",
          lookup.flatMap {
            case QueryResult(offset, None) =>
              connection
                .submit(
                  actAs = Seq(validator),
                  readAs = Seq(validator),
                  update = payload.create,
                  priority = priority,
                )
                .withDomainId(domain)
                .withDedup(
                  commandId,
                  offset,
                )
                .yieldUnit()
            case QueryResult(_, Some(existing)) =>
              if (existing.contract.payload == payload)
                Future.unit
              else
                Future.failed(
                  Status.INVALID_ARGUMENT
                    .withDescription(
                      show"There is already an existing ${payload.getClass.toString} but payloads do not match: existing: ${existing.contract.payload}, submitted: $payload"
                    )
                    .asRuntimeException
                )
          },
          logger,
        )
      }

  def getLatestAppConfiguration(
      provider: PartyId
  )(implicit tc: TraceContext): Future[AppConfiguration] =
    store
      .lookupLatestAppConfiguration(provider)
      .map(
        _.fold(
          throw Status.NOT_FOUND
            .withDescription(show"No configuration found for app $provider")
            .asRuntimeException
        )((c: ContractWithState[_, codegen.AppConfiguration]) =>
          AppConfiguration(
            provider,
            decodeOrThrow[definitions.AppConfiguration](c.contract.payload.json),
          )
        )
      )

  def lookupLatestAppConfigurationByName(
      name: String
  )(implicit tc: TraceContext): Future[Option[AppConfiguration]] =
    store
      .lookupLatestAppConfigurationByName(name)
      .map(
        _.map((c: ContractWithState[_, codegen.AppConfiguration]) =>
          AppConfiguration(
            PartyId.tryFromProtoPrimitive(c.payload.provider),
            decodeOrThrow[definitions.AppConfiguration](c.contract.payload.json),
          )
        )
      )

  def lookupAppConfiguration(
      provider: PartyId,
      version: Long,
  )(implicit tc: TraceContext): Future[Option[AppConfiguration]] =
    store
      .lookupAppConfiguration(provider, version)
      .map(
        _.value.map(c =>
          AppConfiguration(
            provider,
            decodeOrThrow[definitions.AppConfiguration](c.contract.payload.json),
          )
        )
      )

  def getAppConfiguration(provider: PartyId, version: Long)(implicit
      tc: TraceContext
  ): Future[AppConfiguration] =
    lookupAppConfiguration(provider, version).map(
      _.getOrElse(
        throw Status.NOT_FOUND
          .withDescription(show"App $provider has no configuration with version $version")
          .asRuntimeException
      )
    )

  def lookupAppRelease(provider: PartyId, version: String)(implicit
      tc: TraceContext
  ): Future[Option[AppRelease]] =
    store
      .lookupAppRelease(provider, version)
      .map(
        _.value.map(c =>
          AppRelease(provider, decodeOrThrow[definitions.AppRelease](c.contract.payload.json))
        )
      )

  def getAppRelease(provider: PartyId, version: String)(implicit
      tc: TraceContext
  ): Future[AppRelease] =
    lookupAppRelease(provider, version).map(
      _.getOrElse(
        throw Status.NOT_FOUND
          .withDescription(show"No release $version found for app $provider")
          .asRuntimeException
      )
    )

  private def toInstalledApp(c: ValidatorStore.InstalledApp) =
    InstalledApp(
      PartyId.tryFromProtoPrimitive(c.installed.contract.payload.provider),
      AppUrl(c.installed.contract.payload.appUrl),
      decodeOrThrow[definitions.AppConfiguration](c.latestConfiguration.contract.payload.json),
      c.approvedReleaseConfigurations.map(c =>
        decodeOrThrow[definitions.ReleaseConfiguration](c.contract.payload.json)
      ),
    )

  def lookupInstalledAppUrl(provider: PartyId)(implicit
      tc: TraceContext
  ): Future[Option[AppUrl]] =
    store
      .lookupInstalledApp(provider)
      .map(_.value.map(c => AppUrl(c.contract.payload.appUrl)))

  def getInstalledAppUrl(provider: PartyId)(implicit tc: TraceContext): Future[AppUrl] =
    lookupInstalledAppUrl(provider).map(
      _.getOrElse(
        throw Status.NOT_FOUND.withDescription(show"App $provider not found").asRuntimeException()
      )
    )

  def listInstalledApps()(implicit tc: TraceContext): Future[Seq[InstalledApp]] =
    store
      .listInstalledApps()
      .map(
        _.map(toInstalledApp(_))
      )

  def listRegisteredApps()(implicit tc: TraceContext): Future[Seq[RegisteredApp]] =
    store
      .listRegisteredApps()
      .map(
        _.map(app =>
          RegisteredApp(
            PartyId.tryFromProtoPrimitive(app.registered.contract.payload.provider),
            decodeOrThrow[definitions.AppConfiguration](
              app.configuration.contract.payload.json
            ),
          )
        )
      )

  private def decodeOrThrow[A: Decoder](input: String): A =
    decode(input).valueOr(err =>
      throw Status.INTERNAL
        .withDescription(show"Failed to decode JSON payload read from store: $err")
        .asRuntimeException
    )
}

object AppManagerStore {
  private implicit val prettyConfiguration: Pretty[definitions.AppConfiguration] =
    PrettyUtil.adHocPrettyInstance
  private implicit val prettyReleaseConfiguration: Pretty[definitions.ReleaseConfiguration] =
    PrettyUtil.adHocPrettyInstance

  final case class AppRelease(provider: PartyId, release: definitions.AppRelease) {
    def toHttp: definitions.AppRelease = release
  }

  final case class AppConfiguration(
      provider: PartyId,
      configuration: definitions.AppConfiguration,
  ) {
    def toHttp: definitions.GetAppConfigurationResult =
      definitions.GetAppConfigurationResult(provider.toProtoPrimitive, configuration)
  }

  final case class RegisteredApp(
      provider: PartyId,
      configuration: definitions.AppConfiguration,
  ) {
    def toHttp(appManagerApiUrl: Uri): definitions.RegisteredApp =
      definitions.RegisteredApp(
        provider.toProtoPrimitive,
        appManagerApiUrl
          .withPath(
            appManagerApiUrl.path / "v0" / "app-manager" / "apps" / "registered" / provider.toProtoPrimitive
          )
          .toString,
        configuration,
      )
  }

  final case class InstalledApp(
      provider: PartyId,
      appUrl: AppUrl,
      latestConfiguration: definitions.AppConfiguration,
      approvedReleaseConfigurations: Seq[definitions.ReleaseConfiguration],
  ) extends PrettyPrinting {
    def toHttp: definitions.InstalledApp = definitions.InstalledApp(
      provider = provider.toProtoPrimitive,
      latestConfiguration = latestConfiguration,
      approvedReleaseConfigurations = approvedReleaseConfigurations.toVector,
      unapprovedReleaseConfigurations =
        latestConfiguration.releaseConfigurations.zipWithIndex.flatMap { case (conf, i) =>
          Option.when(!approvedReleaseConfigurations.contains(conf))(
            definitions.UnapprovedReleaseConfiguration(i, conf)
          )
        },
    )

    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("provider", _.provider),
        param("appUrl", _.appUrl.appUrl),
        param("latestConfiguration", _.latestConfiguration),
        param("approvedReleaseConfigurations", _.approvedReleaseConfigurations),
      )
  }

  final case class ApprovedReleaseConfiguration(
      provider: PartyId,
      configurationVersion: Long,
      releaseConfiguration: definitions.ReleaseConfiguration,
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("provider", _.provider),
        param("configurationVersion", _.configurationVersion),
        param("releaseConfiguraiton", _.releaseConfiguration),
      )
  }

  case class AppUrl(appUrl: Uri) {
    private val appUriPathRegex: scala.util.matching.Regex = raw"(.*)/apps/registered/(.*)".r
    val (provider: PartyId, appManagerUri: Uri) =
      appUrl.path.toString match {
        case appUriPathRegex(prefix, provider) =>
          (PartyId.tryFromProtoPrimitive(provider), appUrl.withPath(Uri.Path(prefix)))
        case _ =>
          throw new IllegalArgumentException(
            s"App URL $appUrl does not match the format of a valid app URL"
          )
      }
    lazy val latestAppConfiguration: Uri =
      appUrl.withPath(appUrl.path / "configuration" / "latest" / "configuration.json")
    def appRelease(version: String): Uri =
      appUrl.withPath(appUrl.path / "release" / version / "release.json")
    def darUrl(hash: String): Uri =
      appManagerUri.withPath(appManagerUri.path / "dars" / hash)
  }

  // Intentionally using a very large value for the HashPurpose to avoid clashes with newly added purposes by Canton.
  private val releaseConfigurationHashPurpose = HashPurpose(42_000, "ReleaseConfiguration")

  private def hashReleaseConfiguration(configuration: definitions.ReleaseConfiguration): Hash = {
    import com.digitalasset.canton.platform.apiserver.meteringreport.Jcs
    val serialized = Jcs
      .serialize(circeJsonToSprayJsValue(configuration.asJson))
      .valueOr(err =>
        throw Status.INTERNAL
          .withDescription(show"Failed to serialize release configuration as JSON: $err")
          .asRuntimeException
      )
    Hash.build(releaseConfigurationHashPurpose, HashAlgorithm.Sha256).add(serialized).finish()
  }
}
