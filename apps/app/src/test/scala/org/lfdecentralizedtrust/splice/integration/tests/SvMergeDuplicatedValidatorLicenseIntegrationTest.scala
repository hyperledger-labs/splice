package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.javaapi.data.Identifier
import com.digitalasset.canton.logging.SuppressionRule
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense.ValidatorLicense
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.MergeValidatorLicenseContractsTrigger
import org.lfdecentralizedtrust.splice.util.TriggerTestUtil
import org.slf4j.event.Level

import scala.jdk.CollectionConverters.*

class SvMergeDuplicatedValidatorLicenseIntegrationTest
    extends SvIntegrationTestBase
    with TriggerTestUtil {

  override def environmentDefinition
      : org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)

  override protected lazy val updateHistoryIgnoredRootCreates: Seq[Identifier] = Seq(
    ValidatorLicense.TEMPLATE_ID_WITH_PACKAGE_ID
  )

  "Duplicated validator licenses for the same validator get merged" in { implicit env =>
    val dso = sv1Backend.getDsoInfo().dsoParty

    def getValidatorLicenses() =
      sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
        .filterJava(ValidatorLicense.COMPANION)(
          dso,
          _ => true,
        )
        .filter(_.data.validator.contains("digital-asset-2"))

    val validatorLicenses = getValidatorLicenses()
    validatorLicenses should have size 1

    val validatorLicense = inside(validatorLicenses) { case Seq(validatorLicense) =>
      validatorLicense
    }
    setTriggersWithin(
      triggersToPauseAtStart =
        Seq(sv1Backend.dsoDelegateBasedAutomation.trigger[MergeValidatorLicenseContractsTrigger]),
      triggersToResumeAtStart = Seq.empty,
    ) {
      actAndCheck(
        "Create a duplicate Validator License Contract",
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands.submitJava(
          Seq(dso),
          optTimeout = None,
          commands = validatorLicense.data.create().commands.asScala.toSeq,
        ),
      )(
        "A second validator license gets created",
        _ => {
          val newValidatorLicenses = getValidatorLicenses()
          newValidatorLicenses should have size 2
        },
      )
      // The trigger can process both validator licenses in parallel so we might get multiple log messages.
      loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
        {
          sv1Backend.dsoDelegateBasedAutomation
            .trigger[MergeValidatorLicenseContractsTrigger]
            .resume()
          clue("Trigger merges the duplicated validator licenses contracts") {
            eventually() {
              val newValidatorLicenses = getValidatorLicenses()
              newValidatorLicenses should have size 1
            }
          }
          // Pause to make sure we don't get more log messages.
          sv1Backend.dsoDelegateBasedAutomation
            .trigger[MergeValidatorLicenseContractsTrigger]
            .pause()
            .futureValue
        },
        forAll(_)(
          _.warningMessage should include(
            "has 2 Validator License contracts."
          )
        ),
      )
    }
  }
}
