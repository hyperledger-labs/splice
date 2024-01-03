package com.daml.network.integration.tests.runbook

final class RunbookValidatorNonDevNetPreflightIntegrationTest
    extends RunbookValidatorPreflightIntegrationTest {
  override protected val isDevNet = false
}

final class Validator1NonDevNetPreflightIntegrationTest extends Validator1PreflightIntegrationTest {
  override protected val isDevNet = false
}
