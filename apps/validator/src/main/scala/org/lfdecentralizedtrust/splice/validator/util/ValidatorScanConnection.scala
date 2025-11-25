// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.util

import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.validator.store.ValidatorConfigProvider
import org.lfdecentralizedtrust.splice.validator.store.ValidatorConfigProvider.ScanUrlInternalConfig

import scala.concurrent.{ExecutionContext, Future}

object ValidatorScanConnection {
  def persistScanUrlListBuilder(
      validatorConfigProvider: ValidatorConfigProvider
  )(implicit traceContext: TraceContext): Seq[(String, String)] => Future[Unit] = {

    (connections: Seq[(String, String)]) =>
      {
        val internalConfigs: Seq[ScanUrlInternalConfig] = connections.map { case (svName, url) =>
          ScanUrlInternalConfig(
            svName = svName,
            url = url,
          )
        }
        validatorConfigProvider.setScanUrlInternalConfig(internalConfigs)
      }
  }

  def getPersistedScanList(
      validatorConfigProvider: ValidatorConfigProvider
  )(implicit
      traceContext: TraceContext,
      executionContext: ExecutionContext,
  ): () => Future[Option[List[(String, String)]]] = { () =>
    {
      val optionTConfig = validatorConfigProvider.getScanUrlInternalConfig()

      optionTConfig.map { internalConfigs =>
        internalConfigs.map { internalConfig =>
          (internalConfig.svName, internalConfig.url)
        }.toList
      }.value
    }
  }
}
