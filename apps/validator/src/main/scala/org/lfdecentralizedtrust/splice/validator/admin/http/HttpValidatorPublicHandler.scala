// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.admin.http

import org.lfdecentralizedtrust.splice.http.v0.{definitions, validator_public as v0}
import org.lfdecentralizedtrust.splice.validator.store.ValidatorStore
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.Spanning
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class HttpValidatorPublicHandler(
    store: ValidatorStore,
    validatorUserName: String,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends v0.ValidatorPublicHandler[Unit]
    with Spanning
    with NamedLogging {
  private val workflowId = this.getClass.getSimpleName

  def getValidatorUserInfo(
      respond: v0.ValidatorPublicResource.GetValidatorUserInfoResponse.type
  )()(
      fake: Unit
  ): Future[v0.ValidatorPublicResource.GetValidatorUserInfoResponse] =
    withNewTrace(workflowId) { implicit tc => _ =>
      for {
        featuredAppRight <- store.lookupValidatorFeaturedAppRight()
      } yield definitions.GetValidatorUserInfoResponse(
        store.key.validatorParty.filterString,
        validatorUserName,
        featuredAppRight.isDefined,
      )
    }
}
