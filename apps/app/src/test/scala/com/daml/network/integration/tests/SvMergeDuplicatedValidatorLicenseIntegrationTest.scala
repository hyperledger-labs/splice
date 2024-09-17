package com.daml.network.integration.tests

import com.daml.ledger.javaapi.data.Identifier
import com.daml.network.codegen.java.splice.validatorlicense.ValidatorLicense
import com.daml.network.integration.EnvironmentDefinition
import com.daml.network.sv.automation.delegatebased.MergeValidatorLicenseContractsTrigger
import com.daml.network.util.TriggerTestUtil

import scala.jdk.CollectionConverters.*

class SvMergeDuplicatedValidatorLicenseIntegrationTest
    extends SvIntegrationTestBase
    with TriggerTestUtil {

  override def environmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)

  override protected lazy val updateHistoryIgnoredRootCreates: Seq[Identifier] = Seq(
    ValidatorLicense.TEMPLATE_ID
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
      loggerFactory.assertLogs(
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
        },
        _.warningMessage should include(
          "has 2 Validator License contracts."
        ),
      )
    }
  }
}
