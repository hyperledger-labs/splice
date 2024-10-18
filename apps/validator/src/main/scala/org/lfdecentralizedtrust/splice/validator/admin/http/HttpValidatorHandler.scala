// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.admin.http

import org.lfdecentralizedtrust.splice.auth.AuthExtractor.TracedUser
import org.lfdecentralizedtrust.splice.scan.admin.api.client.ScanConnection.GetAmuletRulesDomain
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion
import org.lfdecentralizedtrust.splice.environment.{ParticipantAdminConnection, RetryProvider}
import org.lfdecentralizedtrust.splice.http.v0.{definitions, validator as v0}
import org.lfdecentralizedtrust.splice.validator.store.ValidatorStore
import org.lfdecentralizedtrust.splice.validator.util.ValidatorUtil
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.circe.Json
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class HttpValidatorHandler(
    storeWithIngestion: AppStoreWithIngestion[ValidatorStore],
    validatorUserName: String,
    getAmuletRulesDomain: GetAmuletRulesDomain,
    participantAdminConnection: ParticipantAdminConnection,
    retryProvider: RetryProvider,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends v0.ValidatorHandler[TracedUser]
    with Spanning
    with NamedLogging {
  private val workflowId = this.getClass.getSimpleName

  def register(
      respond: v0.ValidatorResource.RegisterResponse.type
  )(body: Option[Json])(tracedUser: TracedUser): Future[v0.ValidatorResource.RegisterResponse] = {
    implicit val TracedUser(ledgerApiUser, traceContext) = tracedUser

    withSpan(s"$workflowId.register") { _ => span =>
      span.setAttribute("name", ledgerApiUser)
      onboard(ledgerApiUser).map(p => definitions.RegistrationResponse(p))
    }
  }

  private def onboard(name: String)(implicit traceContext: TraceContext): Future[String] = {
    ValidatorUtil
      .onboard(
        name,
        None,
        storeWithIngestion,
        validatorUserName,
        getAmuletRulesDomain,
        participantAdminConnection,
        retryProvider,
        logger,
      )
      .map(p => p.filterString)
  }
}
