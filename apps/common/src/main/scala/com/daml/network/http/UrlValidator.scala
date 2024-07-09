// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.http

import cats.implicits.toBifunctorOps
import com.typesafe.scalalogging.LazyLogging

import java.net.URI
import scala.util.Try

object UrlValidator extends LazyLogging {

  sealed trait InvalidUrl

  case class InvalidFormat(url: String, cause: Throwable) extends InvalidUrl
  case object InvalidScheme extends InvalidUrl
  case object InvalidHost extends InvalidUrl

  def isValid(url: String): Either[InvalidUrl, String] = {
    Try(new URI(url)).toEither
      .leftMap(InvalidFormat(url, _))
      .flatMap { url =>
        if (url.getScheme != "http" && url.getScheme != "https") {
          Left(InvalidScheme)
        } else if (Option(url.getHost).forall(_.isEmpty)) Left(InvalidHost)
        else
          Right(url.toString)
      }
  }

}
