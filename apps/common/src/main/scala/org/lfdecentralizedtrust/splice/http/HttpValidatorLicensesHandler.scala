// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.http

import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense as vl
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.http.v0.definitions.ListValidatorLicensesResponse
import org.lfdecentralizedtrust.splice.store.{AppStore, Limit, PageLimit, SortOrder}
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

trait HttpValidatorLicensesHandler extends Spanning with NamedLogging {

  protected val validatorLicensesStore: AppStore
  protected val workflowId: String
  protected implicit val tracer: Tracer

  def listValidatorLicenses(after: Option[Long], limit: Option[Int])(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[ListValidatorLicensesResponse] = {
    withSpan(s"$workflowId.listValidatorLicenses") { _ => _ =>
      for {
        resultsInPage <- validatorLicensesStore.multiDomainAcsStore
          .listContractsPaginated(
            vl.ValidatorLicense.COMPANION,
            after,
            limit.fold(PageLimit.Max)(limit =>
              PageLimit.tryCreate(limit, Limit.DefaultMaxPageSize)
            ),
            SortOrder.Descending,
          )
          .map(_.mapResultsInPage(_.contract))
      } yield {
        definitions.ListValidatorLicensesResponse(
          resultsInPage.resultsInPage.map(_.toHttp).toVector,
          resultsInPage.nextPageToken,
        )
      }
    }
  }

}
