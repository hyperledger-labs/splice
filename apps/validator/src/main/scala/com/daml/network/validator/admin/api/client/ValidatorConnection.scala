package com.daml.network.validator.admin.api.client

import com.daml.network.admin.api.client.AppConnection
import com.daml.network.validator.admin.api.client.commands.GrpcValidatorAppClient
import com.digitalasset.canton.config.{ClientConfig, ProcessingTimeout}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContextExecutor, Future}

final case class UserInfo(
    primaryParty: PartyId,
    userName: String,
)

final class ValidatorConnection(
    config: ClientConfig,
    timeouts: ProcessingTimeout,
    loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContextExecutor)
    extends AppConnection(config, timeouts, loggerFactory) {

  // cached validator reference.
  private val validatorRef: AtomicReference[Option[UserInfo]] = new AtomicReference(None)

  override val serviceName = "validator"

  /** Query for the Validator party id. This caches the result internally so
    * clients can call this repeatedly without having to implement caching themselves.
    */
  def getValidatorPartyId()(implicit traceContext: TraceContext): Future[PartyId] =
    getValidatorUserInfo().map(_.primaryParty)

  /** Query for the Validator party id. This caches the result internally so
    * clients can call this repeatedly without having to implement caching themselves.
    */
  def getValidatorUserInfo()(implicit traceContext: TraceContext): Future[UserInfo] = {
    val prev = validatorRef.get()
    prev match {
      case Some(userInfo) => Future.successful(userInfo)
      case None =>
        for {
          userInfo <- runCmd(GrpcValidatorAppClient.GetValidatorUserInfo())
        } yield {
          // The party id never changes so we don’t need to worry about concurrent setters writing different values.
          validatorRef.set(Some(userInfo))
          userInfo
        }
    }
  }
}
