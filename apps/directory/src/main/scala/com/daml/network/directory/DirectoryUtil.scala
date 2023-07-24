package com.daml.network.directory

import com.daml.network.environment.CNLedgerConnection
import com.digitalasset.canton.topology.PartyId
import akka.http.scaladsl.model.Uri
import scala.util.{Try, Success, Failure}

object DirectoryUtil {

  val entryNameSuffix = ".unverified.cns"
  val entryNameRegex = s"[a-z0-9_-]{1,${40 - entryNameSuffix.length()}}\\.unverified\\.cns"
  val entryUrlLength = 255
  val validEntryUrlSchemes = Seq("http", "https")
  val entryDescriptionLength = 140

  /** The command-id to use for all command submissions that create a directory entry. */
  def createDirectoryEntryCommandId(
      provider: PartyId,
      entryName: String,
  ): CNLedgerConnection.CommandId =
    CNLedgerConnection.CommandId(
      "com.daml.network.directory.createDirectoryEntry",
      Seq(provider),
      entryName,
    )

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
