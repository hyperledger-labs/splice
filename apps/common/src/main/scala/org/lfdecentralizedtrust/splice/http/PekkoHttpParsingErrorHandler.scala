// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.http

import org.apache.pekko.event.LoggingAdapter
import org.apache.pekko.http.ParsingErrorHandler
import org.apache.pekko.http.scaladsl.model.{ErrorInfo, HttpResponse, StatusCode}
import org.apache.pekko.http.scaladsl.settings.ServerSettings

/** Replaces org.apache.pekko.http.DefaultParsingErrorHandler to downgrade the logs to info level,
  * as they're logged by default to warning with no possibility to configure it beyond disabling
  * its logging completely.
  */
object PekkoHttpParsingErrorHandler extends ParsingErrorHandler {

  override def handle(
      status: StatusCode,
      info: ErrorInfo,
      log: LoggingAdapter,
      settings: ServerSettings,
  ): HttpResponse = {
    log.info(
      info.withSummaryPrepended(s"Illegal request, responding with status '$status'").formatPretty
    )
    val msg = info.formatPretty
    HttpResponse(status, entity = msg)
  }
}
