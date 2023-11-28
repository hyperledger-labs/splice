package com.daml.network.sv.util

import org.apache.pekko.http.scaladsl.model.Uri
import com.daml.network.codegen.java.cn
import com.daml.network.util.Contract

import scala.util.{Failure, Success, Try}

object CnsUtil {

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

  def isValidCnsContent(
      cnsEntryContext: Contract[cn.cns.CnsEntryContext.ContractId, cn.cns.CnsEntryContext]
  ): Boolean = {
    isValidEntryName(cnsEntryContext.payload.name)
    && isValidEntryUrl(cnsEntryContext.payload.url)
    && isValidEntryDescription(cnsEntryContext.payload.description)
  }
}
