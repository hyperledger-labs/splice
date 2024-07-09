// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.http

import com.digitalasset.canton.config.NonNegativeDuration
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}

import scala.concurrent.Future

trait CNHttpClient {

  val requestParameters: CNHttpClient.HttpRequestParameters

  def withOverrideParameters(newParameters: CNHttpClient.HttpRequestParameters): CNHttpClient

  def executeRequest(request: HttpRequest): Future[HttpResponse]

}

object CNHttpClient {
  case class HttpRequestParameters(requestTimeout: NonNegativeDuration)
}
