package com.daml.network.validator.admin.api.client

import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.Materializer
import com.daml.network.admin.api.client.AppConnection
import com.daml.network.auth.JwtCallCredential
import com.daml.network.config.CoinHttpClientConfig
import com.daml.network.util.TemplateJsonDecoder
import com.daml.network.validator.admin.api.client.commands.HttpValidatorAppClient
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}

final case class UserInfo(
    primaryParty: PartyId,
    userName: String,
)

final class ValidatorConnection(
    config: CoinHttpClientConfig,
    timeouts: ProcessingTimeout,
    loggerFactory: NamedLoggerFactory,
    credentials: JwtCallCredential,
)(implicit ec: ExecutionContextExecutor)
    extends AppConnection(config.clientConfig, timeouts, loggerFactory) {

  // cached validator reference.
  private val validatorRef: AtomicReference[Option[UserInfo]] = new AtomicReference(None)

  override val serviceName = "validator"

  /** Query for the Validator party id. This caches the result internally so
    * clients can call this repeatedly without having to implement caching themselves.
    */
  def getValidatorPartyId()(implicit
      httpClient: HttpRequest => Future[HttpResponse],
      templateDecoder: TemplateJsonDecoder,
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[PartyId] =
    getValidatorUserInfo().map(_.primaryParty)

  /** Query for the Validator party id. This caches the result internally so
    * clients can call this repeatedly without having to implement caching themselves.
    */
  def getValidatorUserInfo()(implicit
      httpClient: HttpRequest => Future[HttpResponse],
      templateDecoder: TemplateJsonDecoder,
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[UserInfo] = {
    val prev = validatorRef.get()
    prev match {
      case Some(userInfo) => Future.successful(userInfo)
      case None =>
        for {
          userInfo <- runHttpCmd(
            config.url,
            HttpValidatorAppClient.GetValidatorUserInfo(
              List(Authorization(OAuth2BearerToken(credentials.jwt)))
            ),
          )
        } yield {
          // The party id never changes so we don’t need to worry about concurrent setters writing different values.
          validatorRef.set(Some(userInfo))
          userInfo
        }
    }
  }
}
