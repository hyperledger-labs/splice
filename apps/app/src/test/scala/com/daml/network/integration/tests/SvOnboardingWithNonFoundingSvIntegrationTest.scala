package com.daml.network.integration.tests

import com.daml.network.config.{CNNodeConfigTransforms, NetworkAppClientConfig}
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.sv.SvAppClientConfig
import com.daml.network.sv.config.SvOnboardingConfig.JoinWithKey
import com.daml.network.util.{StandaloneCanton, SvTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import org.scalatest.time.{Minute, Span}

class SvOnboardingViaNonFoundingSvIntegrationTest
    extends CNNodeIntegrationTest
    with SvTestUtil
    with StandaloneCanton {

  override def dbsSuffix: String = "non_founding_svs"

  // Runs against a temporary Canton instance.
  override lazy val resetDecentralizedNamespace = false

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(1, Minute)))

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .withPreSetup(_ => ())
      .withOnboardingParticipantPromotionDelayEnabled() // Test onboarding with participant promotion delay
      .addConfigTransformsToFront(
        (_, conf) => CNNodeConfigTransforms.bumpCantonPortsBy(22_000)(conf),
        (_, conf) => CNNodeConfigTransforms.bumpCantonDomainPortsBy(22_000)(conf),
      )
      .addConfigTransforms((_, config) =>
        CNNodeConfigTransforms.updateAllSvAppConfigs { (name, config) =>
          if (name == "sv3") {
            config.copy(
              onboarding = config.onboarding
                .getOrElse(
                  throw new IllegalStateException("Onboarding configuration not found.")
                ) match {
                case node: JoinWithKey =>
                  Some(
                    JoinWithKey(
                      node.name,
                      SvAppClientConfig(NetworkAppClientConfig("http://127.0.0.1:5214")),
                      node.publicKey,
                      node.privateKey,
                    )
                  )
                case _ => throw new IllegalStateException("JoinWithKey configuration not found.")
              }
            )
          } else config
        }(config)
      )
      .withManualStart

  "SV onboarding works without founding SVs" in { implicit env =>
    withCantonSvNodes(
      (
        Some(sv1Backend),
        Some(sv2Backend),
        Some(sv3Backend),
        None,
      ),
      logSuffix = "sv123-non-founding-svs",
      sv4 = false,
    )() {
      actAndCheck(
        "Initialize SVC founded by sv1",
        startAllSync(
          sv1Backend,
          sv2Backend,
          sv3Backend,
        ),
      )(
        "All SVs are initialized",
        _ => {
          sv2Backend
            .getSvcInfo()
            .svcRules
            .payload
            .members
            .keySet() should contain theSameElementsAs Seq(sv1Backend, sv2Backend, sv3Backend).map(
            _.getSvcInfo().svParty.toProtoPrimitive
          )
        },
      )
    }
  }
}
