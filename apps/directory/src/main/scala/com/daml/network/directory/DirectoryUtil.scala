package com.daml.network.directory

import com.daml.network.environment.CNLedgerConnection
import com.digitalasset.canton.topology.PartyId

object DirectoryUtil {

  private val entryNameSuffix = ".unverified.cns"
  private val entryNameLength = 40
  private val entryNameRegex = "[a-z0-9_-]+"

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
  def isValidEntryName(name: String): Boolean = {
    name.size <= entryNameLength && name
      .stripSuffix(entryNameSuffix)
      .matches(entryNameRegex) && name.endsWith(entryNameSuffix)
  }

}
