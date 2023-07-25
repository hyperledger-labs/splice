package com.daml.network.validator.admin.http

import akka.http.scaladsl.model.ContentType
import cats.syntax.either.*
import com.daml.network.http.v0.{appManager as v0, definitions}
import com.daml.network.validator.config.AppManagerConfig
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.Spanning
import io.opentelemetry.api.trace.Tracer

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.{CompressorStreamFactory}
import scala.concurrent.{ExecutionContext, Future}
import com.google.protobuf.ByteString
import io.circe.parser.decode
import java.io.{
  BufferedReader,
  InputStream,
  InputStreamReader,
  BufferedInputStream,
  File,
  FileInputStream,
}
import java.util.Base64
import java.util.stream.Collectors

final case class RegisteredApp(
    bundle: ByteString,
    manifest: definitions.Manifest,
)

// TODO(#6839) Add auth to all endpoints
class HttpAppManagerHandler(
    config: AppManagerConfig,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends v0.AppManagerHandler[Unit]
    with Spanning
    with NamedLogging {

  private val workflowId = this.getClass.getSimpleName

  // TODO(#6839) Revisit storage of registered apps
  private val registeredApps: collection.concurrent.Map[String, RegisteredApp] =
    collection.concurrent.TrieMap.empty

  private def appBundleUrl(app: String) =
    config.appManagerApiUrl
      .withPath(
        config.appManagerApiUrl.path / "app-manager" / "apps" / "registered" / app / "bundle.tar.gz"
      )

  def listRegisteredApps(
      respond: v0.AppManagerResource.ListRegisteredAppsResponse.type
  )()(extracted: Unit): Future[
    v0.AppManagerResource.ListRegisteredAppsResponse
  ] =
    withNewTrace(workflowId) { _ => _ =>
      Future.successful(
        definitions.ListRegisteredAppsResponse(
          registeredApps.keys.toVector.map(name =>
            definitions.RegisteredApp(
              name,
              config.appManagerApiUrl
                .withPath(
                  config.appManagerApiUrl.path / "app-manager" / "apps" / "registered" / name / "manifest.json"
                )
                .toString,
            )
          )
        )
      )
    }

  def getAppManifest(
      respond: v0.AppManagerResource.GetAppManifestResponse.type
  )(app: String)(extracted: Unit): Future[v0.AppManagerResource.GetAppManifestResponse] =
    withNewTrace(workflowId) { _ => _ =>
      Future.successful(
        registeredApps
          .get(app)
          .fold(
            v0.AppManagerResource.GetAppManifestResponse
              .NotFound(definitions.ErrorResponse(s"App $app is not registered"))
          )(_.manifest)
      )
    }

  def getAppBundle(
      respond: v0.AppManagerResource.GetAppBundleResponse.type
  )(app: String)(extracted: Unit): Future[v0.AppManagerResource.GetAppBundleResponse] =
    withNewTrace(workflowId) { _ => _ =>
      Future.successful(
        registeredApps
          .get(app)
          .fold(
            v0.AppManagerResource.GetAppBundleResponse
              .NotFound(definitions.ErrorResponse(s"App $app is not registered"))
          )(app =>
            definitions.GetAppBundleResponse(
              Base64.getEncoder().encodeToString(app.bundle.toByteArray)
            )
          )
      )
    }

  def registerApp(respond: v0.AppManagerResource.RegisterAppResponse.type)(
      appBundle: (java.io.File, Option[String], ContentType)
  )(extracted: Unit): Future[v0.AppManagerResource.RegisterAppResponse] =
    Future {
      val manifest = readManifestFromBundle(appBundle._1)
      val expectedBundleUrl = appBundleUrl(manifest.name)
      // TODO(#6839) Consider restructuring the APIs so that the bundle URL isn't even part of the upload
      // and this check becomes unnecessary.
      if (manifest.bundle != expectedBundleUrl.toString) {
        v0.AppManagerResource.RegisterAppResponse.BadRequest(
          definitions.ErrorResponse(
            s"Bundle url ${manifest.bundle} does not match $expectedBundleUrl"
          )
        )
      } else {
        val bundleBytes = ByteString.readFrom(new FileInputStream(appBundle._1))
        val previous =
          registeredApps.putIfAbsent(manifest.name, RegisteredApp(bundleBytes, manifest))
        // TODO(#6839) Relax this to support upgrading.
        previous.fold(v0.AppManagerResource.RegisterAppResponse.Created)(_ =>
          v0.AppManagerResource.RegisterAppResponse.Conflict(
            definitions.ErrorResponse(s"App ${manifest.name} has already been registered")
          )
        )
      }
    }

  def registerAppMapFileField(
      fieldName: String,
      fileName: Option[String],
      contentType: ContentType,
  ): File =
    // File is deleted by the generated code in guardrail.
    File.createTempFile("app-bundle_", ".tgz")

  private def readTarGz(file: File): TarArchiveInputStream =
    readTarGz(new FileInputStream(file))

  private def readTarGz(inputStream: InputStream): TarArchiveInputStream = {
    val uncompressedInputStream = new CompressorStreamFactory().createCompressorInputStream(
      new BufferedInputStream(inputStream)
    )
    new TarArchiveInputStream(new BufferedInputStream(uncompressedInputStream))
  }

  private def readManifestFromBundle(file: File): definitions.Manifest = {
    val archiveInputStream = readTarGz(file)
    val _ =
      LazyList
        .continually(archiveInputStream.getNextTarEntry())
        .takeWhile(_ != null)
        .find(x => x.getName.dropWhile(x => x != '/') == "/manifest.json")
        .getOrElse(throw new IllegalArgumentException("No manifest in bundle"))
    val manifestString = new BufferedReader(new InputStreamReader(archiveInputStream))
      .lines()
      .collect(Collectors.joining("\n"));
    decode[definitions.Manifest](manifestString).valueOr(err =>
      throw new IllegalArgumentException(s"Invalid manifest: $err")
    )
  }
}
