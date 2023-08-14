package com.daml.network.validator.store

import akka.http.scaladsl.model.Uri
import cats.syntax.either.*
import com.daml.network.codegen.java.cn.appmanager.store as codegen
import com.daml.network.environment.{CNLedgerConnection, RetryProvider}
import com.daml.network.http.v0.definitions
import com.daml.network.store.CNNodeAppStoreWithIngestion
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.util.ContractWithState
import com.daml.network.util.PrettyInstances
import com.daml.network.util.PrettyInstances.*
import com.daml.network.validator.store.ValidatorStore
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
import scala.jdk.CollectionConverters.*

/** A validator-local store for the data required by the app manager.
  * This is internally contract-based but the APIs are deliberately do not expose contracts
  * to allow switching this to a plain postgres store later (which is much more efficient).
  * Note that contrary to our contract-based stores this also provides the write endpoints.
  */
final class AppManagerStore(
    storeWithIngestion: CNNodeAppStoreWithIngestion[ValidatorStore],
    retryProvider: RetryProvider,
    override val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends NamedLogging {
  import AppManagerStore.*

  private val store = storeWithIngestion.store
  private val connection = storeWithIngestion.connection
  private val validator = store.key.validatorParty

  def storeAppRelease(release: AppRelease)(implicit tc: TraceContext): Future[Unit] =
    idempotentCreate(
      show"app release for ${release.provider} in version ${release.release.version}",
      store
        .lookupAppRelease(release.provider, release.release.version),
      CNLedgerConnection.CommandId(
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
    )

  def storeAppConfiguration(configuration: AppConfiguration)(implicit
      tc: TraceContext
  ): Future[Unit] =
    idempotentCreate(
      show"app configuration for ${configuration.provider} in version ${configuration.configuration.version}",
      store
        .lookupAppConfiguration(configuration.provider, configuration.configuration.version),
      CNLedgerConnection.CommandId(
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
    )

  def storeRegisteredApp(registered: RegisteredApp)(implicit tc: TraceContext): Future[Unit] =
    idempotentCreate(
      show"registered app ${registered.provider}",
      store.lookupRegisteredApp(registered.provider),
      CNLedgerConnection.CommandId(
        "com.daml.network.validator.store.storeRegisteredApp",
        Seq(validator, registered.provider),
      ),
      new codegen.RegisteredApp(
        validator.toProtoPrimitive,
        registered.provider.toProtoPrimitive,
      ),
    )

  def storeInstalledApp(installed: InstalledApp)(implicit tc: TraceContext): Future[Unit] =
    idempotentCreate(
      show"installed app ${installed.provider}",
      store.lookupInstalledApp(installed.provider),
      CNLedgerConnection.CommandId(
        "com.daml.network.validator.store.storeInstalledApp",
        Seq(validator, installed.provider),
      ),
      new codegen.InstalledApp(
        validator.toProtoPrimitive,
        installed.provider.toProtoPrimitive,
        installed.appUrl.appUrl.toString,
        installed.configuration.version,
        installed.configuration.asJson.noSpaces,
        installed.releases.view.mapValues(_.asJson.noSpaces).toMap.asJava,
      ),
    )

  private def idempotentCreate[TCid, T <: com.daml.ledger.javaapi.data.Template](
      name: String,
      lookup: => Future[QueryResult[Option[ContractWithState[TCid, T]]]],
      commandId: CNLedgerConnection.CommandId,
      payload: T,
  )(implicit tc: TraceContext): Future[Unit] =
    store.defaultAcsDomainIdF.flatMap { domain =>
      retryProvider.retryForClientCalls(
        show"Creating $name if it does not already exist",
        lookup.flatMap {
          case QueryResult(offset, None) =>
            connection
              .submit(
                actAs = Seq(validator),
                readAs = Seq(validator),
                update = payload.create,
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

  def lookupAppRelease(provider: PartyId, version: String): Future[Option[AppRelease]] =
    store
      .lookupAppRelease(provider, version)
      .map(
        _.value.map(c =>
          AppRelease(provider, decodeOrThrow[definitions.AppRelease](c.contract.payload.json))
        )
      )

  def getAppRelease(provider: PartyId, version: String): Future[AppRelease] =
    lookupAppRelease(provider, version).map(
      _.getOrElse(
        throw Status.NOT_FOUND
          .withDescription(show"No release $version found for app $provider")
          .asRuntimeException
      )
    )

  private def toInstalledApp(c: ContractWithState[_, codegen.InstalledApp]) =
    InstalledApp(
      PartyId.tryFromProtoPrimitive(c.contract.payload.provider),
      AppUrl(c.contract.payload.appUrl),
      decodeOrThrow[definitions.AppConfiguration](c.contract.payload.configurationJson),
      c.contract.payload.releasesJson.asScala.view
        .mapValues(decodeOrThrow[definitions.AppRelease](_))
        .toMap,
    )

  def lookupInstalledApp(provider: PartyId): Future[Option[InstalledApp]] =
    store.lookupInstalledApp(provider).map(_.value.map(toInstalledApp(_)))

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
  private implicit val prettyRelease: Pretty[definitions.AppRelease] =
    PrettyUtil.adHocPrettyInstance
  private implicit val prettyReleaseMap: Pretty[Map[String, definitions.AppRelease]] = {
    implicit val prettyVersion: Pretty[String] = PrettyInstances.prettyString
    PrettyInstances.prettyMap
  }

  final case class AppRelease(provider: PartyId, release: definitions.AppRelease) {
    def toJson: definitions.AppRelease = release
  }

  final case class AppConfiguration(
      provider: PartyId,
      configuration: definitions.AppConfiguration,
  ) {
    def toJson: definitions.AppConfiguration = configuration
  }

  final case class RegisteredApp(
      provider: PartyId,
      configuration: definitions.AppConfiguration,
  ) {
    def toJson(appManagerApiUrl: Uri): definitions.RegisteredApp =
      definitions.RegisteredApp(
        provider.toProtoPrimitive,
        appManagerApiUrl
          .withPath(
            appManagerApiUrl.path / "app-manager" / "apps" / "registered" / provider.toProtoPrimitive
          )
          .toString,
        configuration,
      )
  }

  final case class InstalledApp(
      provider: PartyId,
      appUrl: AppUrl,
      configuration: definitions.AppConfiguration,
      releases: Map[String, definitions.AppRelease],
  ) extends PrettyPrinting {
    def toJson: definitions.InstalledApp = definitions.InstalledApp(
      provider = provider.toProtoPrimitive,
      name = configuration.name,
      url = configuration.uiUrl,
    )

    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("provider", _.provider),
        param("appUrl", _.appUrl.appUrl),
        param("configuration", _.configuration),
        param("releases", _.releases),
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
}
