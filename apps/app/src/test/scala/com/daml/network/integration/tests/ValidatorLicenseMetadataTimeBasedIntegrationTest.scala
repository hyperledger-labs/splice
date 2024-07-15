package com.daml.network.integration.tests

import com.daml.network.codegen.java.splice.validatorlicense.*
import com.daml.network.environment.{BuildInfo, EnvironmentImpl}
import com.daml.network.integration.EnvironmentDefinition
import com.daml.network.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import com.daml.network.util.{TimeTestUtil, TriggerTestUtil, WalletTestUtil}
import com.daml.network.validator.automation.ReceiveFaucetCouponTrigger
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import scala.jdk.OptionConverters.*

class ValidatorLicenseMetadataTimeBasedIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with TimeTestUtil
    with TriggerTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        config.copy(
          validatorApps = config.validatorApps +
            (InstanceName.tryCreate("aliceValidatorLocal") ->
              config
                .validatorApps(InstanceName.tryCreate("aliceValidator"))
                .copy(
                  contactPoint = "aliceLocal@example.com"
                ))
        )
      )
      .withManualStart

  "contact point gets updated" in { implicit env =>
    startAllSync(
      sv1Backend,
      sv1ScanBackend,
      aliceValidatorBackend,
    )

    val license =
      aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs.awaitJava(
        ValidatorLicense.COMPANION
      )(aliceValidatorBackend.getValidatorPartyId())
    license.data.metadata.toScala.value shouldBe new ValidatorLicenseMetadata(
      getLedgerTime.toInstant,
      BuildInfo.compiledVersion,
      "alice@example.com",
    )
    aliceValidatorBackend.stop()
    aliceValidatorLocalBackend.start()
    actAndCheck(
      "Advance time past minMetadataUpdateInterval",
      advanceTime(java.time.Duration.ofHours(2)),
    )(
      "metadata gets updated",
      _ => {
        val license =
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs.awaitJava(
            ValidatorLicense.COMPANION
          )(aliceValidatorBackend.getValidatorPartyId())
        license.data.metadata.toScala.value shouldBe new ValidatorLicenseMetadata(
          getLedgerTime.toInstant,
          BuildInfo.compiledVersion,
          "aliceLocal@example.com",
        )
      },
    )
  }

  "lastActiveAt gets updated" in { implicit env =>
    startAllSync(
      sv1Backend,
      sv1ScanBackend,
      aliceValidatorBackend,
    )
    // Collect the initial faucet coupons to make sure they don't run after the first license query.
    // We don't disable the trigger globally since the first test should also pass
    // with this trigger enabled.
    Seq(0, 1).foreach { _ =>
      aliceValidatorBackend.validatorAutomation
        .trigger[ReceiveFaucetCouponTrigger]
        .runOnce()
        .futureValue
    }
    aliceValidatorBackend.validatorAutomation
      .trigger[ReceiveFaucetCouponTrigger]
      .runOnce()
      .futureValue
    val license =
      aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs.awaitJava(
        ValidatorLicense.COMPANION
      )(aliceValidatorBackend.getValidatorPartyId())
    license.data.lastActiveAt.toScala.value shouldBe getLedgerTime.toInstant
    setTriggersWithin(
      triggersToResumeAtStart = Seq.empty,
      triggersToPauseAtStart = Seq(
        aliceValidatorBackend.validatorAutomation.trigger[ReceiveFaucetCouponTrigger]
      ),
    ) {
      actAndCheck(
        "Advance time past activityReportMinInterval",
        advanceTime(java.time.Duration.ofHours(2)),
      )(
        "lastActiveAt gets updated",
        _ => {
          val newLicense =
            aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
              .awaitJava(
                ValidatorLicense.COMPANION
              )(aliceValidatorBackend.getValidatorPartyId())
          newLicense.data.lastActiveAt should not be license.data.lastActiveAt
          newLicense.data.lastActiveAt.toScala.value shouldBe getLedgerTime.toInstant
          newLicense.data.faucetState shouldBe license.data.faucetState
        },
      )
      succeed
    }
  }
}
