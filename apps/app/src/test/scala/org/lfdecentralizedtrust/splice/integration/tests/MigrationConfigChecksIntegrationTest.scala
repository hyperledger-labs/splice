package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName

class MigrationConfigChecksIntegrationTest extends IntegrationTest {

  override def environmentDefinition: EnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withManualStart
      // Add additional configs with migration ID 1
      .addConfigTransforms((_, config) =>
        config.copy(
          validatorApps = config.validatorApps + (
            InstanceName.tryCreate("aliceValidatorLocal") -> config
              .validatorApps(InstanceName("aliceValidator"))
              .copy(
                domainMigrationId = 1L
              )
          )
        )
      )

  "validator refuses to start if migration ID incremented but no dump specified" in {
    implicit env =>
      clue("Start SV to set up synchronizer") {
        startAllSync(sv1Backend, sv1ValidatorBackend, sv1ScanBackend)
      }
      clue("Start alice validator normally") {
        aliceValidatorBackend.startSync()
      }
      clue("Stop alice validator") {
        aliceValidatorBackend.stop()
      }
      clue("Start alice validator with wrong config") {
        loggerFactory.assertThrowsAndLogsSeq[RuntimeException](
          aliceValidatorLocalBackend.startSync(),
          entries => {
            // We should fail before even finding out that migration ID 1 does not exist.
            forAll(entries) {
              _.message should not include ("sequencer connections for migration id 1 is empty")
            }
            forAtLeast(1, entries) {
              _.errorMessage should include(
                "Migration ID was incremented (to 1) but no migration dump for restoring from was specified."
              )
            }
          },
        )
      }
  }
}
