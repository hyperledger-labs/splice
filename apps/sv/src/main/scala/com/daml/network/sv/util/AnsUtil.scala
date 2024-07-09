// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.util

import org.apache.pekko.http.scaladsl.model.Uri

import scala.util.{Failure, Success, Try}

object AnsUtil {

  val entryNameSuffix = ".unverified.cns"
  val entryNameRegex = s"[a-z0-9_-]{1,${60 - entryNameSuffix.length()}}\\.unverified\\.cns"
  val entryUrlLength = 255
  val validEntryUrlSchemes = Seq("http", "https")
  val entryDescriptionLength = 140

  def isValidEntryName(name: String): Boolean =
    name.matches(entryNameRegex)

  def isValidEntryUrl(url: String): Boolean = {
    if (url.isEmpty()) true
    else {
      Try(Uri(url)) match {
        case Success(parsedUri) =>
          url.length <= entryUrlLength && parsedUri.isAbsolute && validEntryUrlSchemes.contains(
            parsedUri.scheme
          )
        case Failure(_) =>
          false
      }
    }
  }

  def isValidEntryDescription(description: String): Boolean =
    description.length <= entryDescriptionLength
}
