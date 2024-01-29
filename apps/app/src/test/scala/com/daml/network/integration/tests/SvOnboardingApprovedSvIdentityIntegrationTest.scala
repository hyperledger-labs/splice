package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.svlocal.approvedsvidentity.ApprovedSvIdentity
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.ProcessTestUtil
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

/** Integration test that checks that an SV cannot onboard .
  */
class SvOnboardingApprovedSvIdentityIntegrationTest
    extends CNNodeIntegrationTest
    with ProcessTestUtil {

  private def sv1RestartBackend(implicit env: CNNodeTestConsoleEnvironment) = svb("restartsv1")

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      // Disable user allocation
      .withPreSetup(_ => ())
      .withAllocatedUsers(extraIgnoredSvPrefixes = Seq("restart"))
      .addConfigTransforms((_, config) => {
        config.copy(
          svApps = config.svApps + (
            InstanceName.tryCreate(s"restartsv1") ->
              config
                .svApps(InstanceName.tryCreate("sv1"))
                .copy(
                  approvedSvIdentities = config
                    .svApps(InstanceName.tryCreate("sv1"))
                    .approvedSvIdentities
                    .filterNot(_.name == "Canton-Foundation-2")
                )
          )
        )
      })
      .withManualStart

  "test that an offboarded SV cannot restart if it was permanently removed from others ApprovedSvIdentities whitelist" in {
    implicit env =>
      sv1Backend.startSync()

      clue("Check that the `ApprovedSvIdentity` contract was created for sv2") {
        inside(
          sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(ApprovedSvIdentity.COMPANION)(sv1Backend.getSvcInfo().svParty)
        ) { case approvedSvIds =>
          sv1Backend.config.approvedSvIdentities.map(_.name) should contain(
            "Canton-Foundation-2"
          )
          approvedSvIds.map(_.data.candidateName) should contain("Canton-Foundation-2")
        }
      }

      sv1Backend.stop()

      sv1RestartBackend.startSync()

      clue("Check that the `ApprovedSvIdentity` contract for sv2 no longer exists") {
        inside(
          sv1RestartBackend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(ApprovedSvIdentity.COMPANION)(sv1RestartBackend.getSvcInfo().svParty)
        ) { case approvedSvIds =>
          sv1RestartBackend.config.approvedSvIdentities.map(_.name) shouldNot contain(
            "Canton-Foundation-2"
          )
          approvedSvIds.map(_.data.candidateName) shouldNot contain("Canton-Foundation-2")
        }
      }

  }
}
