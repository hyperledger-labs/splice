package com.daml.network.console

import com.daml.network.environment.CNNodeConsoleEnvironment
import com.daml.network.http.v0.definitions
import com.digitalasset.canton.console.{BaseInspection, Help}
import com.digitalasset.canton.participant.ParticipantNode
import com.daml.network.directory.admin.api.client.commands.HttpDirectoryAppClient
import com.daml.network.auth.AuthUtil
import com.daml.network.directory.config.DirectoryAppExternalClientConfig

abstract class DirectoryExternalAppReference(
    override val cnNodeConsoleEnvironment: CNNodeConsoleEnvironment,
    override val name: String,
) extends HttpCNNodeAppReference {

  override protected val instanceType = "Directory user"

  @Help.Summary("Create Directory Entry")
  def createDirectoryEntry(
      entryName: String,
      url: String,
      description: String,
  ): HttpDirectoryAppClient.CreateDirectoryEntryResponse = {
    consoleEnvironment.run {
      httpCommand(HttpDirectoryAppClient.CreateDirectoryEntry(entryName, url, description))
    }
  }

  @Help.Summary("List Directory Entries")
  def listDirectoryEntries(): definitions.ListDirectoryEntriesResponse = {
    consoleEnvironment.run {
      httpCommand(HttpDirectoryAppClient.ListDirectoryEntries())
    }
  }
}

final class DirectoryExternalAppClientReference(
    override val consoleEnvironment: CNNodeConsoleEnvironment,
    name: String,
    val config: DirectoryAppExternalClientConfig,
) extends DirectoryExternalAppReference(consoleEnvironment, name)
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "External Directory Client"
  override def httpClientConfig = config.adminApi
  override def token: Option[String] = {
    Some(
      AuthUtil.testToken(
        audience = AuthUtil.testAudience,
        user = config.ledgerApiUser,
        secret = AuthUtil.testSecret,
      )
    )
  }
}
