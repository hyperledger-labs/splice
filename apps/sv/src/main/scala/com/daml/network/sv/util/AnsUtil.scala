// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.util

import org.apache.pekko.http.scaladsl.model.Uri

import scala.util.{Failure, Success, Try}

class AnsUtil(ansAcronym: String) {
  val entryNameSuffix = s".unverified.$ansAcronym"
  val entryNameRegex =
    s"[a-z0-9_-]{1,${60 - entryNameSuffix.length()}}\\.unverified\\.${scala.util.matching.Regex quote ansAcronym}"
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
