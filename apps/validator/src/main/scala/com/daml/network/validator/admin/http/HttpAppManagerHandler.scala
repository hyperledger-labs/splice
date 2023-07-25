package com.daml.network.validator.admin.http

import akka.stream.Materializer
import akka.http.scaladsl.model.{
  ContentType,
  ContentTypes,
  HttpRequest,
  HttpResponse,
  Uri,
  StatusCodes,
}
import akka.http.scaladsl.unmarshalling.Unmarshal
import cats.data.EitherT
import cats.syntax.either.*
import cats.syntax.foldable.*
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.daml.lf.archive.DarParser
import com.daml.network.admin.api.client.commands.HttpClientBuilder
import com.daml.network.environment.{BaseAppConnection, ParticipantAdminConnection}
import com.daml.network.http.v0.{appManager as v0, definitions}
import com.daml.network.validator.config.AppManagerConfig
import com.daml.network.util.UploadablePackage
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.participant.domain.DomainConnectionConfig
import com.digitalasset.canton.sequencing.{GrpcSequencerConnection, SequencerConnections}
import com.digitalasset.canton.tracing.Spanning
import com.digitalasset.canton.util.EitherTUtil
import io.opentelemetry.api.trace.Tracer

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.{CompressorStreamFactory}
import scala.concurrent.{ExecutionContext, Future}
import com.google.protobuf.ByteString
import io.circe.Json
import io.circe.parser.decode
import java.io.{
  ByteArrayInputStream,
  BufferedReader,
  InputStream,
  InputStreamReader,
  BufferedInputStream,
  File,
  FileInputStream,
}
import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPublicKey, RSAPrivateKey}
import java.util.{Base64, UUID}
import java.util.stream.Collectors
import java.util.zip.ZipInputStream

final case class RegisteredApp(
    bundle: ByteString,
    manifest: definitions.Manifest,
)

// TODO(#6839) Add auth to all endpoints
class HttpAppManagerHandler(
    config: AppManagerConfig,
    participantAdminConnection: ParticipantAdminConnection,
    lock: (String, Boolean, () => Future[Unit]) => Future[Unit],
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
    httpClient: HttpRequest => Future[HttpResponse],
    mat: Materializer,
) extends v0.AppManagerHandler[Unit]
    with Spanning
    with NamedLogging {

  // TODO(#6839) Consider persisting this so that a restart does not require a token refresh.
  private val keyId = UUID.randomUUID.toString

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  private def generateKey() = {
    val keyGen = KeyPairGenerator.getInstance("RSA")
    val keyPair = keyGen.generateKeyPair()
    (keyPair.getPublic.asInstanceOf[RSAPublicKey], keyPair.getPrivate.asInstanceOf[RSAPrivateKey])
  }

  private val (publicKey, privateKey) = generateKey()

  private val workflowId = this.getClass.getSimpleName

  // TODO(#6839) Revisit storage of registered apps
  private val registeredApps: collection.concurrent.Map[String, RegisteredApp] =
    collection.concurrent.TrieMap.empty

  // TODO(#6839) Revisit storage of installed apps
  private val installedApps: collection.concurrent.Map[String, definitions.Manifest] =
    collection.concurrent.TrieMap.empty

  // Map from authorization code to user id
  private val codes: collection.concurrent.Map[String, String] =
    collection.concurrent.TrieMap.empty

  private def appBundleUrl(app: String) =
    config.appManagerApiUrl
      .withPath(
        config.appManagerApiUrl.path / "app-manager" / "apps" / "registered" / app / "bundle.tar.gz"
      )

  // This is the endpoint that the app manager frontend calls after the user got redirected to /authorize
  // and logged in and confirmed that they want to authorize the app.
  def authorize(
      respond: v0.AppManagerResource.AuthorizeResponse.type
  )(redirectUri: String, state: String, userId: String)(
      extracted: Unit
  ): Future[v0.AppManagerResource.AuthorizeResponse] =
    withNewTrace(workflowId) { _ => _ =>
      val authorizationCode = UUID.randomUUID.toString
      codes += authorizationCode -> userId
      Future.successful(
        // TODO(#6839) Consider just letting the frontend compute this instead of returning it here.
        definitions.AuthorizeResponse(
          Uri(redirectUri)
            .withQuery(Uri.Query("code" -> authorizationCode, "state" -> state))
            .toString
        )
      )
    }

  def oauth2Jwks(
      respond: v0.AppManagerResource.Oauth2JwksResponse.type
  )()(extracted: Unit): scala.concurrent.Future[v0.AppManagerResource.Oauth2JwksResponse] =
    withNewTrace(workflowId) { _ => _ =>
      Future.successful(
        definitions.JwksResponse(
          Vector(
            definitions.Jwk(
              kid = keyId,
              kty = "RSA",
              use = "sig",
              alg = "RS256",
              // Somewhat annoyingly the Auth0 library only does JWKS decoding but not encoding
              // so we somewhat handroll it here.
              n = Base64.getUrlEncoder().encodeToString(publicKey.getModulus.toByteArray),
              e = Base64.getUrlEncoder().encodeToString(publicKey.getPublicExponent.toByteArray),
            )
          )
        )
      )
    }
  def oauth2OpenIdConfiguration(
      respond: v0.AppManagerResource.Oauth2OpenIdConfigurationResponse.type
  )()(extracted: Unit): Future[v0.AppManagerResource.Oauth2OpenIdConfigurationResponse] =
    withNewTrace(workflowId) { _ => _ =>
      Future.successful(
        definitions.OpenIdConfigurationResponse(
          issuer = config.issuerUrl.toString,
          authorizationEndpoint =
            config.appManagerUiUrl.withPath(config.appManagerUiUrl.path / "authorize").toString,
          tokenEndpoint = config.appManagerApiUrl
            .withPath(config.appManagerApiUrl.path / "app-manager" / "oauth2" / "token")
            .toString,
          jwksUri = config.appManagerApiUrl
            .withPath(config.appManagerApiUrl.path / "app-manager" / ".well-known" / "jwks.json")
            .toString,
        )
      )
    }
  def oauth2Token(
      respond: v0.AppManagerResource.Oauth2TokenResponse.type
  )(grantType: String, code: String, redirectUri: String, clientId: String)(
      extracted: Unit
  ): Future[v0.AppManagerResource.Oauth2TokenResponse] =
    withNewTrace(workflowId) { _ => _ =>
      codes.remove(code) match {
        case None =>
          Future.successful(
            v0.AppManagerResource.Oauth2TokenResponse.BadRequest(
              definitions.ErrorResponse("invalid_grant")
            )
          )
        case Some(userId) =>
          // TODO(#6839) Properly validate client_id, redirect_uri, ...
          val jwt = JWT
            .create()
            .withSubject(userId)
            .withAudience(config.audience)
            // Needed so the JSON API parses the token properly
            .withClaim("scope", "daml_ledger_api")
            .withIssuer(config.issuerUrl.toString)
            .withKeyId(keyId)
            .sign(Algorithm.RSA256(publicKey, privateKey))
          Future.successful(
            v0.AppManagerResource.Oauth2TokenResponse.OK(
              definitions.TokenResponse(
                accessToken = jwt,
                tokenType = "bearer",
              )
            )
          )
      }
    }

  def listInstalledApps(
      respond: v0.AppManagerResource.ListInstalledAppsResponse.type
  )()(extracted: Unit): Future[
    v0.AppManagerResource.ListInstalledAppsResponse
  ] =
    withNewTrace(workflowId) { _ => _ =>
      Future.successful(
        definitions.ListInstalledAppsResponse(
          installedApps.toVector.map { case (name, manifest) =>
            definitions.InstalledApp(
              name,
              manifest.uiUrl,
            )
          }
        )
      )
    }

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

  def installApp(respond: v0.AppManagerResource.InstallAppResponse.type)(
      body: definitions.InstallAppRequest
  )(extracted: Unit): Future[v0.AppManagerResource.InstallAppResponse] =
    withNewTrace(workflowId) { implicit tc => _ =>
      for {
        manifest <- getHttpJson[definitions.Manifest](body.manifestUrl)
        bundleResponse <- getHttpJson[definitions.GetAppBundleResponse](manifest.bundle)
        appBundle = Base64.getDecoder().decode(bundleResponse.base64Bundle)
        dars = readDars(new ByteArrayInputStream(appBundle))
        _ <- participantAdminConnection.uploadDarFiles(
          dars,
          lock,
        )
        _ <- manifest.domains.traverse_ { domain =>
          participantAdminConnection.ensureDomainRegistered(
            DomainConnectionConfig(
              // TODO(#6839) Fix the alias here, we can't assume that app providers use distinct aliases. Maybe
              // appName.alias is sufficient namespacing?
              DomainAlias.tryCreate(domain.alias),
              SequencerConnections.single(GrpcSequencerConnection.tryCreate(domain.url)),
            )
          )
        }
      } yield {
        val previous = installedApps.putIfAbsent(manifest.name, manifest)
        // TODO(#6839) Relax this to support upgrading.
        previous.fold(v0.AppManagerResource.InstallAppResponse.Created)(_ =>
          v0.AppManagerResource.InstallAppResponse.Conflict(
            definitions.ErrorResponse(s"App ${manifest.name} has already been installed")
          )
        )
      }
    }

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

  private def readDars(file: InputStream): Seq[UploadablePackage] = {
    val archiveInputStream = readTarGz(file)
    LazyList
      .continually(archiveInputStream.getNextTarEntry())
      .takeWhile(_ != null)
      .filter(entry => entry.getName.dropWhile(x => x != '/').startsWith("/dars/") && entry.isFile)
      .map { entry =>
        val darFile = ByteString.readFrom(archiveInputStream)
        val hash = DarParser
          .readArchive(entry.getName, new ZipInputStream(darFile.newInput))
          .valueOr(err => throw new IllegalArgumentException(s"Failed to decode dar: $err"))
          .main
          .getHash
        new UploadablePackage {
          override def packageId = hash
          override def resourcePath = entry.getName
          override def inputStream() = darFile.newInput
        }
      }
  }

  private def getHttpJson[T](uri: String)(implicit decoder: io.circe.Decoder[T]): Future[T] =
    for {
      response <- httpClient(HttpRequest(uri = uri))
      decoded <- response.status match {
        case StatusCodes.OK if (response.entity.contentType == ContentTypes.`application/json`) =>
          Unmarshal(response.entity).to[String].map { json =>
            decode[T](json).valueOr(err =>
              throw new IllegalArgumentException(s"Failed to decode manifest: $err")
            )
          }
        case _ => Future.failed(new BaseAppConnection.UnexpectedHttpResponse(response))
      }
    } yield decoded

  // Reverse proxy for JSON API to add CORS headers.
  val jsonApiClient = v0.AppManagerClient.httpClient(
    HttpClientBuilder().buildClient,
    config.jsonApiUrl.toString,
  )

  def handleResponse[R](response: EitherT[Future, Either[Throwable, HttpResponse], R]): Future[R] =
    EitherTUtil.toFuture(response.leftMap[Throwable] {
      case Left(throwable) => throwable
      case Right(response) => new BaseAppConnection.UnexpectedHttpResponse(response)
    })

  def jsonApiCreate(
      respond: v0.AppManagerResource.JsonApiCreateResponse.type
  )(body: Json, authorization: String)(
      extracted: Unit
  ): Future[v0.AppManagerResource.JsonApiCreateResponse] =
    handleResponse(
      jsonApiClient.jsonApiCreate(
        body,
        authorization,
      )
    ).map(_.fold(v0.AppManagerResource.JsonApiCreateResponse.OK(_)))
  def jsonApiExercise(
      respond: v0.AppManagerResource.JsonApiExerciseResponse.type
  )(body: Json, authorization: String)(
      extracted: Unit
  ): Future[v0.AppManagerResource.JsonApiExerciseResponse] =
    handleResponse(
      jsonApiClient.jsonApiExercise(
        body,
        authorization,
      )
    ).map(_.fold(v0.AppManagerResource.JsonApiExerciseResponse.OK(_)))
  def jsonApiQuery(
      respond: v0.AppManagerResource.JsonApiQueryResponse.type
  )(body: Json, authorization: String)(
      extracted: Unit
  ): Future[v0.AppManagerResource.JsonApiQueryResponse] =
    handleResponse(
      jsonApiClient.jsonApiQuery(
        body,
        authorization,
      )
    ).map(_.fold(v0.AppManagerResource.JsonApiQueryResponse.OK(_)))
  def jsonApiUser(
      respond: v0.AppManagerResource.JsonApiUserResponse.type
  )(body: Json, authorization: String)(
      extracted: Unit
  ): Future[v0.AppManagerResource.JsonApiUserResponse] =
    handleResponse(
      jsonApiClient.jsonApiUser(
        body,
        authorization,
      )
    ).map(_.fold(v0.AppManagerResource.JsonApiUserResponse.OK(_)))
}
