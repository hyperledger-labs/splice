package com.daml.network.integration.tests.runbook

/** Preflight test running against validator-runbook
  */

class PreflightValidatorIntegrationTest extends PreflightValidatorIntegrationTestBase {

  override protected def auth0 =
    auth0UtilFromEnvVars("https://canton-network-validator-test.us.auth0.com", Some("validator"))

  override protected def validatorName = "validator"
  override protected def validatorAuth0Secret = "cznBUeB70fnpfjaq9TzblwiwjkVyvh5z"
  override protected def validatorAuth0Audience = "https://validator.example.com/api"

  // TODO(tech-debt): consider de-hardcoding this
  override protected def validatorWalletUser =
    "auth0|6526fab5214c99a9a8e1e3cc"
  override protected def walletUiUrl =
    s"https://wallet.validator.${sys.env("NETWORK_APPS_ADDRESS")}/"
  override protected def directoryUiUrl =
    s"https://directory.validator.${sys.env("NETWORK_APPS_ADDRESS")}/"
  override protected def splitwellUiUrl = None
}
