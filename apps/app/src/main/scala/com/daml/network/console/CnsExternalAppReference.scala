package com.daml.network.console

import com.daml.network.environment.CNNodeConsoleEnvironment
import com.daml.network.http.v0.definitions
import com.digitalasset.canton.console.{BaseInspection, Help}
import com.digitalasset.canton.participant.ParticipantNode
import com.daml.network.auth.AuthUtil
import com.daml.network.validator.admin.api.client.commands.HttpCnsAppClient
import com.daml.network.validator.config.CnsAppExternalClientConfig

abstract class CnsExternalAppReference(
    override val cnNodeConsoleEnvironment: CNNodeConsoleEnvironment,
    override val name: String,
) extends HttpCNNodeAppReference {

  override def basePath = ""
  override protected val instanceType = "CNS user"

  @Help.Summary("Create CNS Entry")
  def createCnsEntry(
      entryName: String,
      url: String,
      description: String,
  ): HttpCnsAppClient.CreateCnsEntryResponse = {
    consoleEnvironment.run {
      httpCommand(HttpCnsAppClient.CreateCnsEntry(entryName, url, description))
    }
  }

  @Help.Summary("List CNS Entries")
  def listCnsEntries(): definitions.ListCnsEntriesResponse = {
    consoleEnvironment.run {
      httpCommand(HttpCnsAppClient.ListCnsEntries())
    }
  }
}

final class CnsExternalAppClientReference(
    override val consoleEnvironment: CNNodeConsoleEnvironment,
    name: String,
    val config: CnsAppExternalClientConfig,
) extends CnsExternalAppReference(consoleEnvironment, name)
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "External CNS Client"
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
