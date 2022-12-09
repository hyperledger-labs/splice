package com.daml.network.directory

import com.daml.network.environment.CoinLedgerConnection
import com.digitalasset.canton.topology.PartyId

object DirectoryUtil {

  /** The command-id to use for all command submissions that create a directory entry. */
  def createDirectoryEntryCommandId(
      provider: PartyId,
      entryName: String,
  ): CoinLedgerConnection.CommandId =
    CoinLedgerConnection.CommandId(
      "com.daml.network.directory.createDirectoryEntry",
      Seq(provider),
      entryName,
    )
}
