package com.daml.network.integration.tests.runbook

import better.files.*
import com.daml.network.LiveDevNetTest
import com.daml.network.config.CoinConfigTransforms
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
}
import com.daml.network.util.Auth0Util
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.integration.tests.HasConsoleScriptRunner
import monocle.macros.syntax.lens.*

import scala.util.Using

/** Integration test for the runbook. Uses the exact same configuration files and bootstrap scripts as the runbook.
  * This test also doubles as the pre-flight validator test.
  */
class PreflightIntegrationTest extends CoinIntegrationTest with HasConsoleScriptRunner {

  val examplesPath: File = "apps" / "app" / "src" / "pack" / "examples"
  val validatorPath: File = examplesPath / "validator"

  val resourcesPath: File = "apps" / "app" / "src" / "test" / "resources"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .fromFiles(
        this.getClass.getSimpleName,
        validatorPath / "validator.conf",
        validatorPath / "validator-participant.conf",
        resourcesPath / "preflight-extras.conf",
      )
      .clearConfigTransforms()
      .addConfigTransforms((_, conf) => CoinConfigTransforms.addDamlNameSuffix("preflight")(conf))
      .addConfigTransforms((_, conf) => CoinConfigTransforms.ensureNovelDamlNames()(conf))
      .addConfigTransforms((_, conf) => CoinConfigTransforms.bumpCantonPortsBy(1000)(conf))
      // Disable autostart, because our apps require the participant to be connected to a domain
      // when the app starts. The apps are started manually in `validator-participant.canton` below.
      .addConfigTransforms((_, conf) => conf.focus(_.parameters.manualStart).replace(true))

  // when running locally, these tests may fail if the CC DAR deployed to DevNet
  // differs from the latest one on your branch

  "run through runbook against cluster SVC" taggedAs LiveDevNetTest in { implicit env =>
    runScript(validatorPath / "validator-participant.canton")(env.environment)
    runScript(validatorPath / "validator.canton")(env.environment)
    runScript(validatorPath / "tap-transfer-demo.canton")(env.environment)
  }

  "run through runbook against cluster validator1" taggedAs LiveDevNetTest in { implicit env =>
    runScript(resourcesPath / "tap-transfer-validator1.canton")(env.environment)
  }

  "test a directory entry allocation against cluster SVC" taggedAs LiveDevNetTest in {
    implicit env =>
      runScript(resourcesPath / "allocate-directory-entry.canton")(env.environment)
  }

  "work with auth0" taggedAs LiveDevNetTest in { _ =>
    val clientId = System.getProperty("AUTH0_MANAGEMENT_API_CLIENT_ID");
    val clientSecret = System.getProperty("AUTH0_MANAGEMENT_API_CLIENT_SECRET");

    if (clientId == null || clientId.isEmpty()) {
      fail(
        "No clientId given, please supply auth0 clientId through system property AUTH0_MANAGEMENT_API_CLIENT_ID"
      )
    }

    if (clientSecret == null || clientSecret.isEmpty()) {
      fail(
        "No clientSecret given, please supply auth0 clientSecret through system property AUTH0_MANAGEMENT_API_CLIENT_SECRET"
      )
    }

    val auth0 = new Auth0Util(
      "https://canton-network-dev.us.auth0.com",
      clientId,
      clientSecret,
    )

    Using(auth0.createUser()) { user =>
      logger.debug(s"Created user ${user.email} with password ${user.password} (id: ${user.id})")
    }
  }
}
