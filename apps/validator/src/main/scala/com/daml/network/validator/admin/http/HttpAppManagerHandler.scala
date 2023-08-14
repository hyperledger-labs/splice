package com.daml.network.validator.admin.http

import akka.stream.Materializer
import akka.http.scaladsl.model.{ContentType, HttpRequest, HttpResponse, Uri}
import cats.data.EitherT
import cats.syntax.either.*
import cats.syntax.foldable.*
import cats.syntax.traverse.*
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.daml.network.admin.api.client.commands.HttpClientBuilder
import com.daml.network.environment.{
  BaseAppConnection,
  CNLedgerConnection,
  ParticipantAdminConnection,
  RetryProvider,
}
import com.daml.network.http.v0.{appManager as v0, definitions}
import com.daml.network.validator.config.AppManagerConfig
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

// TODO(#6839) Add auth to all endpoints
class HttpAppManagerHandler(
    config: AppManagerConfig,
    ledgerConnection: CNLedgerConnection,
    participantAdminConnection: ParticipantAdminConnection,
    store: AppManagerStore,
    lock: (String, Boolean, () => Future[Unit]) => Future[Unit],
    retryProvider: RetryProvider,
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

  // Map from authorization code to user id
  private val codes: collection.concurrent.Map[String, String] =
    collection.concurrent.TrieMap.empty

  def authorizeApp(
      respond: v0.AppManagerResource.AuthorizeAppResponse.type
  )(provider: String, userId: String)(
      extracted: Unit
  ): Future[v0.AppManagerResource.AuthorizeAppResponse] =
    withNewTrace(workflowId) { _ => _ =>
      for {
        primaryParty <- ledgerConnection.getPrimaryParty(userId)
        providerPartyId = PartyId.tryFromProtoPrimitive(provider)
        // TODO(#7099) This relies on pretty printing of the provider party id to
        // make the string short enough that it satisfies the user id limits. Switch to
        // interned provider party id instead.
        appUserId = s"app.$userId.$providerPartyId"
        _ <- ledgerConnection.createUserWithPrimaryParty(appUserId, primaryParty, Seq.empty)
        _ <- ledgerConnection.ensureUserMetadataAnnotation(
          appUserId,
          Map("provider" -> provider, "user" -> userId),
        )
      } yield v0.AppManagerResource.AuthorizeAppResponse.OK
    }

  // This is the endpoint that the app manager frontend calls after the user got redirected to /authorize
  // as part of the OAuth2 flow to confirm that they
  // authorized the app.
  def checkAppAuthorized(
      respond: v0.AppManagerResource.CheckAppAuthorizedResponse.type
  )(provider: String, redirectUri: String, state: String, userId: String)(
      extracted: Unit
  ): Future[v0.AppManagerResource.CheckAppAuthorizedResponse] =
    withNewTrace(workflowId) { _ => _ =>
      // TODO(#7092) Avoid linear search across all users.
      ledgerConnection
        .findUserProto(user =>
          user.hasMetadata && user.getMetadata.getAnnotationsOrDefault(
            "provider",
            "",
          ) == provider && user.getMetadata.getAnnotationsOrDefault("user", "") == userId
        )
        .flatMap {
          case None =>
            Future.successful(
              v0.AppManagerResource.CheckAppAuthorizedResponse.Forbidden(
                definitions.ErrorResponse(
                  s"App $provider is not authorized for $userId"
                )
              )
            )
          case Some(appUser) =>
            val authorizationCode = UUID.randomUUID.toString
            codes += authorizationCode -> appUser.getId()
            Future.successful(
              // TODO(#6839) Consider just letting the frontend compute this instead of returning it here.
              definitions.CheckAppAuthorizedResponse(
                Uri(redirectUri)
                  .withQuery(Uri.Query("code" -> authorizationCode, "state" -> state))
                  .toString
              )
            )
        }
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
    withNewTrace(workflowId) { implicit tc => _ =>
      store
        .listInstalledApps()
        .map(apps => definitions.ListInstalledAppsResponse(apps.map(_.toJson).toVector))
    }

  def listRegisteredApps(
      respond: v0.AppManagerResource.ListRegisteredAppsResponse.type
  )()(extracted: Unit): Future[
    v0.AppManagerResource.ListRegisteredAppsResponse
  ] =
    withNewTrace(workflowId) { implicit tc => _ =>
      store
        .listRegisteredApps()
        .map(apps =>
          definitions.ListRegisteredAppsResponse(
            apps.map(_.toJson(config.appManagerApiUrl)).toVector
          )
        )
    }

  def getDarFile(
      respond: v0.AppManagerResource.GetDarFileResponse.type
  )(darHashStr: String)(
      extracted: Unit
  ): Future[v0.AppManagerResource.GetDarFileResponse] =
    withNewTrace(workflowId) { implicit tc => _ =>
      val darHash = Hash.tryFromHexString(darHashStr)
      participantAdminConnection
        .lookupDar(darHash)
        .map { darO =>
          darO.fold(
            v0.AppManagerResource.GetDarFileResponse
              .NotFound(definitions.ErrorResponse(show"DAR with hash $darHash does not exist"))
          )(dar => definitions.DarFile(Base64.getEncoder().encodeToString(dar.toByteArray)))
        }
    }

  def getLatestAppConfiguration(
      respond: v0.AppManagerResource.GetLatestAppConfigurationResponse.type
  )(provider: String)(
      extracted: Unit
  ): Future[v0.AppManagerResource.GetLatestAppConfigurationResponse] =
    withNewTrace(workflowId) { implicit tc => _ =>
      store.getLatestAppConfiguration(PartyId.tryFromProtoPrimitive(provider)).map(_.toJson)
    }

  def getAppRelease(respond: v0.AppManagerResource.GetAppReleaseResponse.type)(
      provider: String,
      version: String,
  )(extracted: Unit): Future[v0.AppManagerResource.GetAppReleaseResponse] =
    withNewTrace(workflowId) { _ => _ =>
      store.getAppRelease(PartyId.tryFromProtoPrimitive(provider), version).map(_.toJson)
    }

  def registerApp(respond: v0.AppManagerResource.RegisterAppResponse.type)(
      providerUserId: String,
      name: String,
      uiUrl: String,
      domains: String,
      release: (java.io.File, Option[String], ContentType),
  )(extracted: Unit): Future[v0.AppManagerResource.RegisterAppResponse] =
    withNewTrace(workflowId) { implicit tc => _ =>
      for {
        providerPartyId <- ledgerConnection.getPrimaryParty(providerUserId)
        decodedDomains = decode[Vector[definitions.Domain]](domains).valueOr(err =>
          throw new IllegalArgumentException(s"Invalid domains: $err")
        )
        releaseManifest = readAppRelease(release._1)
        dars = readDars(new FileInputStream(release._1))
        _ <- participantAdminConnection.uploadDarFiles(
          dars.map(_._1),
          lock,
        )
        _ <- store.storeAppRelease(
          AppManagerStore.AppRelease(
            providerPartyId,
            definitions.AppRelease(
              releaseManifest.version,
              dars.map(_._2.toHexString).toVector,
            ),
          )
        )
        // TODO(#6839) Support updating the configuration and bump version
        // on each update.
        configurationVersion = 1L
        _ <- store.storeAppConfiguration(
          AppManagerStore.AppConfiguration(
            providerPartyId,
            definitions.AppConfiguration(
              version = configurationVersion,
              name = name,
              uiUrl = uiUrl,
              releaseConfigurations = Vector(
                definitions.ReleaseConfiguration(
                  domains = decodedDomains,
                  releaseVersion = releaseManifest.version,
                  requiredFor = definitions.Timespan(),
                )
              ),
            ),
          )
        )
        _ <- store.storeRegisteredApp(
          AppManagerStore.RegisteredApp(
            providerPartyId,
            name,
            configurationVersion,
          )
        )
      } yield v0.AppManagerResource.RegisterAppResponse.Created
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
      val appUrl = AppManagerStore.AppUrl(body.appUrl)
      for {
        configuration <- HttpUtil.getHttpJson[definitions.AppConfiguration](
          appUrl.latestAppConfiguration
        )
        // TODO(#7089) Add a review step to install and only add changes for approved releases
        releases <- configuration.releaseConfigurations.traverse { releaseConfig =>
          HttpUtil.getHttpJson[definitions.AppRelease](
            appUrl.appRelease(releaseConfig.releaseVersion)
          )
        }
        _ <- configuration.releaseConfigurations.flatMap(_.domains).traverse_ { domain =>
          participantAdminConnection.ensureDomainRegistered(
            DomainConnectionConfig(
              // TODO(#6839) Fix the alias here, we can't assume that app providers use distinct aliases.
              DomainAlias.tryCreate(domain.alias),
              SequencerConnections.single(GrpcSequencerConnection.tryCreate(domain.url)),
            )
          )
        }
        _ <- releases.flatMap(_.darHashes).traverse_(ensureDar(appUrl, _))
        _ <- store.storeInstalledApp(
          AppManagerStore.InstalledApp(
            appUrl.provider,
            appUrl,
            configuration,
            releases.map(release => release.version -> release).toMap,
          )
        )
      } yield v0.AppManagerResource.InstallAppResponse.Created
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
          Seq(dar),
          lock,
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
