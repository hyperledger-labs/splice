package com.daml.network.integration.tests.auth

import com.daml.network.environment.EnvironmentImpl
import com.daml.network.integration.EnvironmentDefinition
import com.daml.network.integration.tests.SpliceTests.{
  IntegrationTestWithSharedEnvironment,
  SpliceTestConsoleEnvironment,
}
import com.daml.network.integration.tests.runbook.PreflightIntegrationTestUtil
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class Auth0CredentialsPreflightIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with PreflightIntegrationTestUtil
    with PreflightAuthUtil {

  override lazy val resetRequiredTopologyState: Boolean = false

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
    EnvironmentDefinition.preflightTopology(
      this.getClass.getSimpleName
    )

  // Refreshes the auth0-preflight-token-cache secret on k8s if needed. This secret holds auth0 tokens for backends
  // as needed for preflight tests. Note that currently, this function refreshes only tokens for those clients
  // listed in PreflightAuthUtil.clientIds, while other preflight tests also create/refresh tokens for other
  // clients.
  // TODO(#10352) consider merging this secret with the FIXED_TOKENS cache created in Pulumi.
  "Refresh auth0 credentials" in { _ =>
    clientIds.foreach { case (_, id) =>
      getAuth0ClientCredential(id, sys.env("OIDC_AUTHORITY_VALIDATOR_AUDIENCE"), auth0)(
        noTracingLogger
      )
    }
  }
}
