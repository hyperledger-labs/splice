package com.daml.network.validator.admin.http

import akka.stream.Materializer
import akka.http.scaladsl.model.{ContentType, HttpRequest, HttpResponse}
import cats.data.EitherT
import cats.syntax.either.*
import cats.syntax.foldable.*
import cats.syntax.traverse.*
import com.daml.network.environment.{CNLedgerConnection, ParticipantAdminConnection, RetryProvider}
import com.daml.network.http.v0.{appManagerAdmin as v0, definitions}
import com.daml.network.validator.store.AppManagerStore
import com.daml.network.validator.util.{DarUtil, HttpUtil}
import com.daml.network.util.UploadablePackage
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.crypto.Hash
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.participant.domain.DomainConnectionConfig
import com.digitalasset.canton.sequencing.{GrpcSequencerConnection, SequencerConnections}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.digitalasset.canton.util.EitherTUtil
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.{CompressorStreamFactory}
import scala.concurrent.{ExecutionContext, Future}
import com.google.protobuf.ByteString
import io.circe.parser.decode
import io.grpc.Status
import java.io.{
  BufferedInputStream,
  BufferedReader,
  ByteArrayInputStream,
  File,
  FileInputStream,
  InputStream,
  InputStreamReader,
}
import java.util.Base64
import java.util.stream.Collectors

class HttpAppManagerAdminHandler(
    ledgerConnection: CNLedgerConnection,
    participantAdminConnection: ParticipantAdminConnection,
    store: AppManagerStore,
    retryProvider: RetryProvider,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
    httpClient: HttpRequest => Future[HttpResponse],
    mat: Materializer,
) extends v0.AppManagerAdminHandler[Unit]
    with Spanning
    with NamedLogging {

  private val workflowId = this.getClass.getSimpleName

  def registerApp(respond: v0.AppManagerAdminResource.RegisterAppResponse.type)(
      providerUserId: String,
      configuration: String,
      release: (java.io.File, Option[String], ContentType),
  )(extracted: Unit): Future[v0.AppManagerAdminResource.RegisterAppResponse] =
    withNewTrace(workflowId) { implicit tc => _ =>
      decode[definitions.AppConfiguration](configuration) match {
        case Left(err) =>
          Future.successful(
            v0.AppManagerAdminResource.RegisterAppResponse.BadRequest(
              definitions.ErrorResponse(
                s"App configuration could not be decoded: $err"
              )
            )
          )
        case Right(configuration) =>
          if (configuration.version != 0) {
            Future.successful(
              v0.AppManagerAdminResource.RegisterAppResponse.BadRequest(
                definitions.ErrorResponse(
                  s"Configuration version on registration must be 0 but was ${configuration.version}"
                )
              )
            )
          } else {
            for {
              providerPartyId <- ledgerConnection.getPrimaryParty(providerUserId)
              _ <- storeAppRelease(providerPartyId, release._1)
              _ <- store.storeAppConfiguration(
                AppManagerStore.AppConfiguration(
                  providerPartyId,
                  configuration,
                )
              )
              _ <- store.storeRegisteredApp(
                AppManagerStore.RegisteredApp(
                  providerPartyId,
                  configuration,
                )
              )
            } yield v0.AppManagerAdminResource.RegisterAppResponse.Created
          }
      }
    }

  def registerAppMapFileField(
      fieldName: String,
      fileName: Option[String],
      contentType: ContentType,
  ): File =
    // File is deleted by the generated code in guardrail.
    File.createTempFile("release_", ".tgz")

  def publishAppRelease(
      respond: v0.AppManagerAdminResource.PublishAppReleaseResponse.type
  )(provider: String, release: (File, Option[String], ContentType))(
      extracted: Unit
  ): Future[v0.AppManagerAdminResource.PublishAppReleaseResponse] =
    withNewTrace(workflowId) { implicit tc => _ =>
      val providerParty = PartyId.tryFromProtoPrimitive(provider)
      for {
        _ <- store.getLatestAppConfiguration(providerParty) // Check that app is registered
        _ <- storeAppRelease(providerParty, release._1)
      } yield v0.AppManagerAdminResource.PublishAppReleaseResponse.Created
    }

  def publishAppReleaseMapFileField(
      fieldName: String,
      fileName: Option[String],
      contentType: ContentType,
  ): File =
    // File is deleted by the generated code in guardrail.
    File.createTempFile("release_", ".tgz")

  private def storeAppRelease(provider: PartyId, release: java.io.File)(implicit
      tc: TraceContext
  ): Future[Unit] =
    for {
      releaseManifest <- Future { readAppRelease(release) }
      dars <- Future { readDars(new FileInputStream(release)) }
      _ <- participantAdminConnection.uploadDarFiles(
        dars.map(_._1)
      )
      _ <- store.storeAppRelease(
        AppManagerStore.AppRelease(
          provider,
          definitions.AppRelease(
            releaseManifest.version,
            dars.map(_._2.toHexString).toVector,
          ),
        )
      )
    } yield ()

  def updateAppConfiguration(
      respond: v0.AppManagerAdminResource.UpdateAppConfigurationResponse.type
  )(provider: String, body: definitions.UpdateAppConfigurationRequest)(
      extracted: Unit
  ): Future[v0.AppManagerAdminResource.UpdateAppConfigurationResponse] =
    withNewTrace(workflowId) { implicit tc => _ =>
      val providerParty = PartyId.tryFromProtoPrimitive(provider)
      validateAppConfiguration(providerParty, body.configuration).flatMap {
        case Left(err) => Future.successful(err)
        case Right(()) =>
          for {
            _ <- store.storeAppConfiguration(
              AppManagerStore.AppConfiguration(providerParty, body.configuration)
            )
          } yield v0.AppManagerAdminResource.UpdateAppConfigurationResponse.Created
      }
    }

  private def validateAppConfiguration(
      provider: PartyId,
      configuration: definitions.AppConfiguration,
  )(implicit
      tc: TraceContext
  ): Future[Either[v0.AppManagerAdminResource.UpdateAppConfigurationResponse, Unit]] =
    (for {
      latestConfig <- EitherT.right(store.getLatestAppConfiguration(provider))
      _ <- EitherTUtil.ifThenET(latestConfig.configuration.version + 1 != configuration.version)(
        EitherT.leftT[Future, Unit](
          v0.AppManagerAdminResource.UpdateAppConfigurationResponse.Conflict(
            definitions.ErrorResponse(
              show"Tried to update configuration to version ${configuration.version} but previous config was version ${latestConfig.configuration.version}"
            )
          )
        )
      )
      _ <- configuration.releaseConfigurations.traverse { config =>
        EitherT.fromOptionF(
          store.lookupAppRelease(provider, config.releaseVersion),
          v0.AppManagerAdminResource.UpdateAppConfigurationResponse.BadRequest(
            definitions.ErrorResponse(
              show"Release configuration references release version ${config.releaseVersion} but release with that version has not been uploaded"
            )
          ),
        )
      }
    } yield ()).value

  def installApp(respond: v0.AppManagerAdminResource.InstallAppResponse.type)(
      body: definitions.InstallAppRequest
  )(extracted: Unit): Future[v0.AppManagerAdminResource.InstallAppResponse] =
    withNewTrace(workflowId) { implicit tc => _ =>
      val appUrl = AppManagerStore.AppUrl(body.appUrl)
      for {
        configuration <- HttpUtil.getHttpJson[definitions.AppConfiguration](
          appUrl.latestAppConfiguration
        )
        releases <- configuration.releaseConfigurations.traverse { releaseConfig =>
          HttpUtil.getHttpJson[definitions.AppRelease](
            appUrl.appRelease(releaseConfig.releaseVersion)
          )
        }
        _ <- releases.traverse_(release =>
          store.storeAppRelease(AppManagerStore.AppRelease(appUrl.provider, release))
        )
        _ <- store.storeAppConfiguration(
          AppManagerStore.AppConfiguration(
            appUrl.provider,
            configuration,
          )
        )
        _ <- store.storeInstalledApp(
          AppManagerStore.InstalledApp(
            appUrl.provider,
            appUrl,
            configuration,
            Seq.empty,
          )
        )
      } yield v0.AppManagerAdminResource.InstallAppResponse.Created
    }

  def approveAppReleaseConfiguration(
      respond: v0.AppManagerAdminResource.ApproveAppReleaseConfigurationResponse.type
  )(provider: String, body: definitions.ApproveAppReleaseConfigurationRequest)(
      extracted: Unit
  ): Future[v0.AppManagerAdminResource.ApproveAppReleaseConfigurationResponse] =
    withNewTrace(workflowId) { implicit tc => _ =>
      val providerParty = PartyId.tryFromProtoPrimitive(provider)
      for {
        config <- store.getAppConfiguration(providerParty, body.configurationVersion)
        releaseConfig =
          config.configuration.releaseConfigurations
            .lift(body.releaseConfigurationIndex)
            .getOrElse(
              throw Status.NOT_FOUND
                .withDescription(
                  show"App $providerParty has only ${config.configuration.releaseConfigurations.length} release configs but tried to approve release config at index ${body.releaseConfigurationIndex}"
                )
                .asRuntimeException
            )
        release <-
          store.getAppRelease(providerParty, releaseConfig.releaseVersion)
        // TODO(#7206) Consider switching this to a reconciliation trigger to avoid
        // partial changes where we change domain config/dars but don't store the ApprovedReleaseConfiguration.
        _ <- releaseConfig.domains.traverse_ { domain =>
          participantAdminConnection.ensureDomainRegistered(
            DomainConnectionConfig(
              // TODO(#6839) Fix the alias here, we can't assume that app providers use distinct aliases.
              DomainAlias.tryCreate(domain.alias),
              SequencerConnections.single(GrpcSequencerConnection.tryCreate(domain.url)),
            )
          )
        }
        appUrl <- store.getInstalledAppUrl(providerParty)
        _ <- release.release.darHashes.traverse_(ensureDar(appUrl, _))
        _ <- store.storeApprovedReleaseConfiguration(
          AppManagerStore.ApprovedReleaseConfiguration(
            providerParty,
            body.configurationVersion,
            releaseConfig,
          )
        )
      } yield v0.AppManagerAdminResource.ApproveAppReleaseConfigurationResponse.OK
    }

  private def ensureDar(appUrl: AppManagerStore.AppUrl, darHash: String)(implicit
      tc: TraceContext
  ): Future[Unit] =
    retryProvider.ensureThatO(
      show"DAR $darHash is uploaded",
      participantAdminConnection.lookupDar(Hash.tryFromHexString(darHash)).map(_.map(_ => ())),
      for {
        darResponse <- HttpUtil.getHttpJson[definitions.DarFile](appUrl.darUrl(darHash))
        dar = DarUtil
          .readDar(
            darHash.toString,
            new ByteArrayInputStream(Base64.getDecoder().decode(darResponse.base64Dar)),
          )
          ._1
        _ <- participantAdminConnection.uploadDarFiles(
          Seq(dar)
        )
      } yield (),
      logger,
    )

  private def readTarGz(file: File): TarArchiveInputStream =
    readTarGz(new FileInputStream(file))

  private def readTarGz(inputStream: InputStream): TarArchiveInputStream = {
    val uncompressedInputStream = new CompressorStreamFactory().createCompressorInputStream(
      new BufferedInputStream(inputStream)
    )
    new TarArchiveInputStream(new BufferedInputStream(uncompressedInputStream))
  }

  private def readAppRelease(file: File): definitions.AppReleaseUpload = {
    val archiveInputStream = readTarGz(file)
    val _ =
      LazyList
        .continually(archiveInputStream.getNextTarEntry())
        .takeWhile(_ != null)
        .find(x => x.getName.dropWhile(x => x != '/') == "/release.json")
        .getOrElse(throw new IllegalArgumentException("No release manifest in bundle"))
    val manifestString = new BufferedReader(new InputStreamReader(archiveInputStream))
      .lines()
      .collect(Collectors.joining("\n"));
    decode[definitions.AppReleaseUpload](manifestString).valueOr(err =>
      throw new IllegalArgumentException(s"Invalid release manifest: $err")
    )
  }

  private def readDars(file: InputStream): Seq[(UploadablePackage, Hash, ByteString)] = {
    val archiveInputStream = readTarGz(file)
    LazyList
      .continually(archiveInputStream.getNextTarEntry())
      .takeWhile(_ != null)
      .filter(entry => entry.getName.dropWhile(x => x != '/').startsWith("/dars/") && entry.isFile)
      .map { entry =>
        DarUtil.readDar(new File(entry.getName).getName, archiveInputStream)
      }
  }
}
