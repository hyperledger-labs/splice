// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv.scan

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.config.{NetworkAppClientConfig, UpgradesConfig}
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.scan.admin.api.client.{ScanConnection, SingleScanConnection}
import org.lfdecentralizedtrust.splice.scan.config.ScanAppClientConfig
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters.MapHasAsScala
import scala.jdk.OptionConverters.RichOptional

class AggregatingScanConnection(
    dsoStore: SvDsoStore,
    upgradesConfig: UpgradesConfig,
    clock: Clock,
    retryProvider: RetryProvider,
    val loggerFactory: NamedLoggerFactory,
)(implicit
    httpClient: HttpClient,
    mat: Materializer,
    templateJsonDecoder: TemplateJsonDecoder,
    ec: ExecutionContextExecutor,
) extends NamedLogging {

  def fromAllScans[T](includeSelf: Boolean)(f: SingleScanConnection => Future[T])(implicit
      tc: TraceContext
  ): Future[Seq[T]] = {
    for {
      scanUrls <- getScanUrls(includeSelf)
      result <- MonadUtil.sequentialTraverse(scanUrls)(url => withScanConnection(url)(f))
    } yield result
  }

  private def getScanUrls(
      includeSelf: Boolean
  )(implicit tc: TraceContext): Future[Seq[String]] = {
    for {
      dsoRulesWithSvNodeStates <- dsoStore.getDsoRulesWithSvNodeStates()
    } yield {
      val svNodes = dsoRulesWithSvNodeStates.svNodeStates.values
      // TODO(DACH-NY/canton-network-node#13301) We should use the internal URL for the SV’s own scan to avoid a loopback requirement
      (if (includeSelf)
         svNodes
       else svNodes.filterNot(_.payload.sv == dsoStore.key.svParty.filterString))
        .flatMap(
          _.payload.state.synchronizerNodes.asScala.values
            .flatMap(_.scan.toScala.toList.map(_.publicUrl))
        )
        .toList
        // sorted to make it deterministic
        .sorted
    }
  }

  private def withScanConnection[T](
      url: String
  )(f: SingleScanConnection => Future[T])(implicit
      tc: TraceContext
  ): Future[T] = {
    val connection = ScanConnection.directConnection(
      ScanAppClientConfig(
        NetworkAppClientConfig(
          url
        )
      ),
      upgradesConfig,
      clock,
      retryProvider,
      loggerFactory,
    )
    f(
      connection
    ).andThen(_ => connection.close())
  }

}
