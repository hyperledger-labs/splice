package com.daml.network.integration.tests.runbook

/** Preflight test running against validator1
  */
class Validator1PreflightIntegrationTest extends PreflightValidatorIntegrationTestBase {

  override protected def auth0 = auth0UtilFromEnvVars("https://canton-network-dev.us.auth0.com")

  override protected def validatorName = "validator1"
  override protected def validatorAuth0Secret = "cf0cZaTagQUN59C1HBL2udiIBdFh2CWq"
  override protected def validatorAuth0Audience = "https://canton.network.global"

  // TODO(tech-debt): consider de-hardcoding this
  override protected def validatorWalletUser =
    "auth0|63e3d75ff4114d87a2c1e4f5"
  override protected def walletUiUrl =
    s"https://wallet.validator1.${sys.env("NETWORK_APPS_ADDRESS")}/"
  override protected def directoryUiUrl =
    s"https://directory.validator1.${sys.env("NETWORK_APPS_ADDRESS")}/"

  override protected def splitwellUiUrl =
    Some(s"https://splitwell.validator1.${sys.env("NETWORK_APPS_ADDRESS")}/")
}
