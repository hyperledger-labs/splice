// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.console

import com.daml.network.environment.SpliceConsoleEnvironment
import com.daml.network.http.v0.definitions
import com.digitalasset.canton.console.Help
import com.daml.network.auth.AuthUtil
import com.daml.network.validator.admin.api.client.commands.HttpAnsAppClient
import com.daml.network.validator.config.AnsAppExternalClientConfig

abstract class AnsExternalAppReference(
    override val spliceConsoleEnvironment: SpliceConsoleEnvironment,
    override val name: String,
) extends HttpAppReference {

  override def basePath = ""
  override protected val instanceType = "ANS user"

  @Help.Summary("Create ANS Entry")
  def createAnsEntry(
      entryName: String,
      url: String,
      description: String,
  ): HttpAnsAppClient.CreateAnsEntryResponse = {
    consoleEnvironment.run {
      httpCommand(HttpAnsAppClient.CreateAnsEntry(entryName, url, description))
    }
  }

  @Help.Summary("List ANS Entries")
  def listAnsEntries(): definitions.ListAnsEntriesResponse = {
    consoleEnvironment.run {
      httpCommand(HttpAnsAppClient.ListAnsEntries())
    }
  }
}

final class AnsExternalAppClientReference(
    override val consoleEnvironment: SpliceConsoleEnvironment,
    name: String,
    val config: AnsAppExternalClientConfig,
) extends AnsExternalAppReference(consoleEnvironment, name) {

  override protected val instanceType = "External ANS Client"
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
