// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.http

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.environment.PackageVersionSupport
import org.lfdecentralizedtrust.splice.http.v0.definitions.FeatureSupportResponse

import scala.concurrent.{ExecutionContext, Future}

trait HttpFeatureSupportHandler extends Spanning with NamedLogging {

  protected val packageVersionSupport: PackageVersionSupport
  protected val workflowId: String
  protected implicit val tracer: Tracer

  def readFeatureSupport(
      party: PartyId
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
      tracer: Tracer,
  ): Future[FeatureSupportResponse] = {
    withSpan(s"$workflowId.featureSupport") { implicit tc => _ =>
      packageVersionSupport
        .supportsNewGovernanceFlow(
          Seq(
            party
          ),
          CantonTimestamp.now(),
        )
        .map { featureSupport =>
          FeatureSupportResponse(
            featureSupport.supported
          )
        }
    }

  }
}
