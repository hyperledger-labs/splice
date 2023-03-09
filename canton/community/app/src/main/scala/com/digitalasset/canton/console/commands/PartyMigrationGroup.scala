// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.console.commands

import com.digitalasset.canton.admin.api.client.commands.ParticipantAdminCommands
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.console.{
  AdminCommandRunner,
  ConsoleEnvironment,
  FeatureFlag,
  Help,
  Helpful,
}
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.version.ProtocolVersion
import com.google.protobuf.ByteString

import java.time.Instant

class PartyMigrationGroup(runner: AdminCommandRunner, consoleEnvironment: ConsoleEnvironment)
    extends Helpful {
  @Help.Summary("Download ACS for selected parties", FeatureFlag.Preview)
  @Help.Description("""Provides a binary of ACS with the following options:
        | - Restricted to selected parties
        | - (Optionally) restricted to a selected domain (prefix)
        | - (Optionally) at a specific point in time in the past
        | - (Optionally) serialized for specific protocol version (i.e. for intended target domain)
        |""")
  def downloadAcsSnapshot(
      parties: Set[PartyId],
      filterDomainId: String = "",
      timestamp: Option[Instant] = None,
      protocolVersion: Option[ProtocolVersion] = None,
  ): (Map[DomainId, Long], ByteString) = {
    consoleEnvironment.run {
      runner.adminCommand(
        ParticipantAdminCommands.PartyMigration
          .DownloadAcsSnapshot(parties, filterDomainId, timestamp, protocolVersion)
      )
    }
  }

  @Help.Summary("Import ACS snapshot", FeatureFlag.Preview)
  @Help.Description("""Uploads a binary into the participant's ACS""")
  def uploadAcsSnapshot(
      acsSnapshot: ByteString,
      batchSize: PositiveInt = PositiveInt.tryCreate(1000),
  ): Unit = {
    consoleEnvironment.run {
      runner.adminCommand(
        ParticipantAdminCommands.PartyMigration.UploadAcsSnapshot(acsSnapshot, batchSize)
      )
    }
  }

}
