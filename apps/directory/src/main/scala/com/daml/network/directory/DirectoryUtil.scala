package com.daml.network.directory

import com.daml.network.environment.CNLedgerConnection
import com.digitalasset.canton.topology.PartyId

object DirectoryUtil {

  val entryNameSuffix = ".unverified.cns"
  val entryNameRegex2 = "^[a-z0-9_-]{1,25}\\.unverified\\.cns$"

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

  /** Validate the format of a directory entry name
    */
  def isValidEntryName(name: String): Boolean =
    name.matches(entryNameRegex2)

}
