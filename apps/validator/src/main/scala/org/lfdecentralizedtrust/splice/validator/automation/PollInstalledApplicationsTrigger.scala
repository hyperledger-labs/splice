// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.automation

import org.apache.pekko.stream.Materializer
import cats.syntax.foldable.*
import cats.syntax.traverse.*
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.validator.config.AppManagerConfig
import org.lfdecentralizedtrust.splice.validator.store.AppManagerStore
import org.lfdecentralizedtrust.splice.validator.util.HttpUtil
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

final class PollInstalledApplicationsTrigger(
    config: AppManagerConfig,
    triggerContext: TriggerContext,
    store: AppManagerStore,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
    httpClient: HttpClient,
    mat: Materializer,
) extends PollingParallelTaskExecutionTrigger[AppManagerStore.InstalledApp] {

  override protected lazy val context = triggerContext.copy(
    config = triggerContext.config.copy(
      pollingInterval = config.installedAppsPollingInterval
    )
  )

  protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[AppManagerStore.InstalledApp]] = store.listInstalledApps()

  protected def completeTask(
      app: AppManagerStore.InstalledApp
  )(implicit tc: TraceContext): Future[TaskOutcome] =
    for {
      configuration <- HttpUtil
        .getHttpJson[definitions.GetAppConfigurationResult](
          app.appUrl.latestAppConfiguration
        )
        .map(_.configuration)
      outcome <-
        if (app.latestConfiguration.version > configuration.version) {
          Future.successful(show"No new configuration for app ${app.provider}")
        } else {
          for {
            releases <- configuration.releaseConfigurations.traverse { releaseConfig =>
              HttpUtil.getHttpJson[definitions.AppRelease](
                app.appUrl.appRelease(releaseConfig.releaseVersion)
              )
            }
            // We store the releases here just to make sure there is no attack vector where the provider mutates them at some point.
            // Storing the release is idempotent so we don't need to check if it changed.
            _ <- releases.traverse_(release =>
              store.storeAppRelease(AppManagerStore.AppRelease(app.appUrl.provider, release))
            )
            _ <- store.storeAppConfiguration(
              AppManagerStore.AppConfiguration(
                app.appUrl.provider,
                configuration,
              )
            )
          } yield show"Found new app configuration ${configuration.version} for app ${app.provider} (previous version: ${app.latestConfiguration.version})"
        }
    } yield TaskSuccess(outcome)

  protected def isStaleTask(task: AppManagerStore.InstalledApp)(implicit
      tc: TraceContext
  ): Future[Boolean] =
    store.lookupInstalledAppUrl(task.provider).map(_.isEmpty)
}
