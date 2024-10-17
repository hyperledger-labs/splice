// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.http

import com.digitalasset.canton.config.NonNegativeDuration
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}

import scala.concurrent.Future

trait HttpClient {

  val requestParameters: HttpClient.HttpRequestParameters

  def withOverrideParameters(newParameters: HttpClient.HttpRequestParameters): HttpClient

  def executeRequest(request: HttpRequest): Future[HttpResponse]

}

object HttpClient {
  case class HttpRequestParameters(requestTimeout: NonNegativeDuration)
}
