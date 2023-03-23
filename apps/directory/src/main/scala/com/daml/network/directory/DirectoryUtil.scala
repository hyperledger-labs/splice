package com.daml.network.directory

import com.daml.network.environment.CNLedgerConnection
import com.digitalasset.canton.topology.PartyId

object DirectoryUtil {

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
}
