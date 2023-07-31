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
import cats.syntax.traverse.*
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.daml.lf.archive.DarParser
import com.daml.network.admin.api.client.commands.HttpClientBuilder
import com.daml.network.environment.{
  BaseAppConnection,
  CNLedgerConnection,
  ParticipantAdminConnection,
}
import com.daml.network.http.v0.{appManager as v0, definitions}
import com.daml.network.validator.config.AppManagerConfig
import com.daml.network.util.UploadablePackage
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.crypto.{Hash, HashAlgorithm, HashOps, HashPurpose}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.participant.domain.DomainConnectionConfig
import com.digitalasset.canton.sequencing.{GrpcSequencerConnection, SequencerConnections}
import com.digitalasset.canton.topology.PartyId
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
  BufferedInputStream,
  BufferedReader,
  ByteArrayInputStream,
  File,
  FileInputStream,
  InputStream,
  InputStreamReader,
}
import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPublicKey, RSAPrivateKey}
import java.util.{Base64, UUID}
import java.util.stream.Collectors
import java.util.zip.ZipInputStream

final case class RegisteredApp(
    dars: Set[Hash],
    name: String,
    uiUrl: Uri,
    domains: Vector[definitions.Domain],
    version: String,
)

final case class InstalledApp(
    name: String,
    uiUrl: Uri,
)

// TODO(#6839) Add auth to all endpoints
class HttpAppManagerHandler(
    config: AppManagerConfig,
    ledgerConnection: CNLedgerConnection,
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

  private val hashOps = new HashOps {
    override def defaultHashAlgorithm = HashAlgorithm.Sha256
  }

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
  private val registeredApps: collection.concurrent.Map[PartyId, RegisteredApp] =
    collection.concurrent.TrieMap.empty

  // TODO(#6839) Revisit storage of installed apps
  private val installedApps: collection.concurrent.Map[PartyId, InstalledApp] =
    collection.concurrent.TrieMap.empty

  private val storedDars: collection.concurrent.Map[Hash, ByteString] =
    collection.concurrent.TrieMap.empty

  // Map from authorization code to user id
  private val codes: collection.concurrent.Map[String, String] =
    collection.concurrent.TrieMap.empty

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
    lazy val latestAppRelease: Uri =
      appUrl.withPath(appUrl.path / "release" / "latest" / "release.json")
    def darUrl(hash: String): Uri =
      appManagerUri.withPath(appManagerUri.path / "dars" / hash)
  }

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
          installedApps.toVector.map { case (provider, app) =>
            definitions.InstalledApp(
              provider.toProtoPrimitive,
              app.name,
              app.uiUrl.toString,
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
          registeredApps.toVector.map { case (provider, app) =>
            definitions.RegisteredApp(
              provider.toProtoPrimitive,
              app.name,
              config.appManagerApiUrl
                .withPath(
                  config.appManagerApiUrl.path / "app-manager" / "apps" / "registered" / provider.toProtoPrimitive
                )
                .toString,
            )
          }
        )
      )
    }

  def getDarFile(
      respond: v0.AppManagerResource.GetDarFileResponse.type
  )(darHashStr: String)(
      extracted: Unit
  ): Future[v0.AppManagerResource.GetDarFileResponse] =
    withNewTrace(workflowId) { _ => _ =>
      Future {
        val darHash = Hash.tryFromHexString(darHashStr)
        storedDars
          .get(darHash)
          .fold(
            v0.AppManagerResource.GetDarFileResponse
              .NotFound(definitions.ErrorResponse(s"DAR with hash $darHash was not found"))
          ) { dar => definitions.DarFile(Base64.getEncoder().encodeToString(dar.toByteArray)) }
      }
    }

  def getLatestAppConfiguration(
      respond: v0.AppManagerResource.GetLatestAppConfigurationResponse.type
  )(provider: String)(
      extracted: Unit
  ): Future[v0.AppManagerResource.GetLatestAppConfigurationResponse] =
    withNewTrace(workflowId) { _ => _ =>
      Future {
        val providerPartyId = PartyId.tryFromProtoPrimitive(provider)
        registeredApps
          .get(providerPartyId)
          .fold(
            v0.AppManagerResource.GetLatestAppConfigurationResponse
              .NotFound(definitions.ErrorResponse(s"App $provider is not registered"))
          ) { app =>
            definitions.AppConfiguration(
              // TODO(#6839) Support updating the configuration and bump version
              // on each update.
              version = 1,
              name = app.name,
              uiUrl = app.uiUrl.toString,
              domains = app.domains,
            )
          }
      }
    }

  def getLatestAppRelease(respond: v0.AppManagerResource.GetLatestAppReleaseResponse.type)(
      provider: String
  )(extracted: Unit): Future[v0.AppManagerResource.GetLatestAppReleaseResponse] =
    withNewTrace(workflowId) { _ => _ =>
      Future {
        val providerPartyId = PartyId.tryFromProtoPrimitive(provider)
        registeredApps
          .get(providerPartyId)
          .fold(
            v0.AppManagerResource.GetLatestAppReleaseResponse
              .NotFound(definitions.ErrorResponse(s"App $provider is not registered"))
          )(app =>
            definitions.AppRelease(
              app.version,
              app.dars.map(_.toHexString).toVector,
            )
          )
      }
    }

  def registerApp(respond: v0.AppManagerResource.RegisterAppResponse.type)(
      providerUserId: String,
      name: String,
      uiUrl: String,
      domains: String,
      release: (java.io.File, Option[String], ContentType),
  )(extracted: Unit): Future[v0.AppManagerResource.RegisterAppResponse] =
    for {
      providerPartyId <- ledgerConnection.getPrimaryParty(providerUserId)
    } yield {
      val decodedDomains = decode[Vector[definitions.Domain]](domains).valueOr(err =>
        throw new IllegalArgumentException(s"Invalid domains: $err")
      )
      val releaseManifest = readAppRelease(release._1)
      val dars = readDars(new FileInputStream(release._1))
      val darMap = dars.view.map { case (_, hash, bytes) =>
        (hash, bytes)
      }.toMap
      val app = RegisteredApp(
        darMap.keySet,
        name,
        uiUrl,
        decodedDomains,
        releaseManifest.version,
      )
      storedDars ++= darMap
      val previous =
        registeredApps.putIfAbsent(providerPartyId, app)
      // TODO(#6839) Relax this to support upgrading.
      previous.fold(v0.AppManagerResource.RegisterAppResponse.Created)(_ =>
        v0.AppManagerResource.RegisterAppResponse.Conflict(
          definitions.ErrorResponse(s"App ${providerPartyId} has already been registered")
        )
      )
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
      val appUrl = AppUrl(body.appUrl)
      for {
        configuration <- getHttpJson[definitions.AppConfiguration](appUrl.latestAppConfiguration)
        release <- getHttpJson[definitions.AppRelease](appUrl.latestAppRelease)
        _ <- configuration.domains.traverse_ { domain =>
          participantAdminConnection.ensureDomainRegistered(
            DomainConnectionConfig(
              // TODO(#6839) Fix the alias here, we can't assume that app providers use distinct aliases. Maybe
              // Namespace through interned app provider
              DomainAlias.tryCreate(domain.alias),
              SequencerConnections.single(GrpcSequencerConnection.tryCreate(domain.url)),
            )
          )
        }
        darFiles <- release.darHashes.traverse { hash =>
          for {
            darResponse <- getHttpJson[definitions.DarFile](appUrl.darUrl(hash))
          } yield readDar(
            hash.toString,
            new ByteArrayInputStream(Base64.getDecoder().decode(darResponse.base64Dar)),
          )._1
        }
        _ <- participantAdminConnection.uploadDarFiles(
          darFiles,
          lock,
        )
      } yield {
        val providerPartyId = appUrl.provider
        val app = InstalledApp(
          configuration.name,
          configuration.uiUrl,
        )
        val previous = installedApps.putIfAbsent(providerPartyId, app)
        // TODO(#6839) Relax this to support upgrading.
        previous.fold(v0.AppManagerResource.InstallAppResponse.Created)(_ =>
          v0.AppManagerResource.InstallAppResponse.Conflict(
            definitions.ErrorResponse(s"App ${providerPartyId} has already been installed")
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

  private def readDar(
      name: String,
      inputStream: InputStream,
  ): (UploadablePackage, Hash, ByteString) = {
    val darFile = ByteString.readFrom(inputStream)
    val pkgId = DarParser
      .readArchive(name, new ZipInputStream(darFile.newInput))
      .valueOr(err => throw new IllegalArgumentException(s"Failed to decode dar: $err"))
      .main
      .getHash
    val darHash = hashOps.digest(HashPurpose.DarIdentifier, darFile)
    (
      new UploadablePackage {
        override def packageId = pkgId
        override def resourcePath = name
        override def inputStream() = darFile.newInput
      },
      darHash,
      darFile,
    )
  }

  private def readDars(file: InputStream): Seq[(UploadablePackage, Hash, ByteString)] = {
    val archiveInputStream = readTarGz(file)
    LazyList
      .continually(archiveInputStream.getNextTarEntry())
      .takeWhile(_ != null)
      .filter(entry => entry.getName.dropWhile(x => x != '/').startsWith("/dars/") && entry.isFile)
      .map { entry =>
        readDar(new File(entry.getName).getName, archiveInputStream)
      }
  }

  private def getHttpJson[T](uri: Uri)(implicit decoder: io.circe.Decoder[T]): Future[T] =
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
