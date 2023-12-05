package com.daml.network.integration.tests.runbook

final class NonDevNetPreflightValidatorIntegrationTest extends PreflightValidatorIntegrationTest {
  override protected val isDevNet = false
}

final class Validator1NonDevNetPreflightIntegrationTest extends Validator1PreflightIntegrationTest {
  override protected val isDevNet = false
}
