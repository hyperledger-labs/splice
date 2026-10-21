package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.javaapi.data.Identifier
import com.digitalasset.canton.logging.SuppressionRule
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense.{ValidatorLicense}
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorunpermission.ValidatorUnpermission
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.{
  MergeValidatorLicenseContractsTrigger,
  MergeValidatorUnpermissionContractsTrigger,
}
import org.lfdecentralizedtrust.splice.util.TriggerTestUtil
import org.lfdecentralizedtrust.splice.util.TriggerTestUtil.{
  pauseAllDsoDelegateTriggers,
  resumeAllDsoDelegateTriggers,
}
import org.slf4j.event.Level

import scala.jdk.CollectionConverters.*

class SvMergeDuplicatedValidatorLicenseAndValidatorUnpermissionIntegrationTest
    extends SvIntegrationTestBase
    with TriggerTestUtil {

  override def environmentDefinition
      : org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
          _.withPausedTrigger[MergeValidatorLicenseContractsTrigger]
            .withPausedTrigger[MergeValidatorUnpermissionContractsTrigger]
        )(config)
      )

  override protected lazy val sanityChecksIgnoredRootCreates: Seq[Identifier] = Seq(
    ValidatorLicense.TEMPLATE_ID_WITH_PACKAGE_ID,
    ValidatorUnpermission.TEMPLATE_ID_WITH_PACKAGE_ID,
  )

  "Duplicated validator licenses for the same validator get merged" in { implicit env =>
    val dso = sv1Backend.getDsoInfo().dsoParty

    val aliceValidator = aliceValidatorBackend.getValidatorPartyId()

    def getValidatorLicenses() =
      sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
        .filterJava(ValidatorLicense.COMPANION)(
          dso,
          _ => true,
        )
        .filter(_.data.validator == aliceValidator.toProtoPrimitive)

    val validatorLicenses = getValidatorLicenses()
    validatorLicenses should have size 1 withClue "alice ValidatorLicenses"

    val validatorLicense = inside(validatorLicenses) { case Seq(validatorLicense) =>
      validatorLicense
    }
    actAndCheck(
      "Create a duplicate Validator License Contract",
      sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands.submitJava(
        Seq(dso),
        commands = validatorLicense.data.create().commands.asScala.toSeq,
      ),
    )(
      "A second validator license gets created",
      _ => {
        val newValidatorLicenses = getValidatorLicenses()
        newValidatorLicenses should have size 2 withClue "alice ValidatorLicenses"
      },
    )
    // The trigger can process both validator licenses in parallel so we might get multiple log messages.
    loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
      {
        resumeAllDsoDelegateTriggers[MergeValidatorLicenseContractsTrigger]
        clue("Trigger merges the duplicated validator licenses contracts") {
          eventually() {
            val newValidatorLicenses = getValidatorLicenses()
            newValidatorLicenses should have size 1 withClue "alice ValidatorLicenses"
          }
        }
        // Pause to make sure we don't get more log messages.
        pauseAllDsoDelegateTriggers[MergeValidatorLicenseContractsTrigger]
      },
      forAll(_)(
        _.warningMessage should include(
          "has 2 Validator License contracts."
        )
      ),
    )
  }

  "Duplicated ValidatorUnpermissions for the same participant get merged" in { implicit env =>
    def getValidatorUnpermissions() =
      sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
        .filterJava(ValidatorUnpermission.COMPANION)(
          sv1Backend.getDsoInfo().dsoParty,
          _ => true,
        )

    val dso = sv1Backend.getDsoInfo().dsoParty.toProtoPrimitive

    val create1 = new ValidatorUnpermission(
      dso,
      "participant1",
      java.util.Optional.empty(),
      java.lang.Boolean.FALSE,
    ).create()
    val create2 = new ValidatorUnpermission(
      dso,
      "participant1",
      java.util.Optional.empty(),
      java.lang.Boolean.TRUE,
    ).create()
    val create3 = new ValidatorUnpermission(
      dso,
      "participant2",
      java.util.Optional.empty(),
      java.lang.Boolean.FALSE,
    ).create()

    actAndCheck(
      "Create 3 ValidatorUnpermission contracts",
      sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands.submitJava(
        Seq(sv1Backend.getDsoInfo().dsoParty),
        commands = Seq(create1, create2, create3).flatMap(_.commands.asScala),
      ),
    )(
      "3 validator unpermissions get created",
      _ => {
        val unpermissions = getValidatorUnpermissions()
        unpermissions should have size 3 withClue "has 3 ValidatorUnpermissions"
      },
    )

    loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
      {
        resumeAllDsoDelegateTriggers[MergeValidatorUnpermissionContractsTrigger]
        clue("Trigger merges the duplicated validator unpermission contracts") {
          eventually() {
            val unpermissions = getValidatorUnpermissions()
            unpermissions should have size 2 withClue "has 2 ValidatorUnpermissions"
          }
        }
        pauseAllDsoDelegateTriggers[MergeValidatorUnpermissionContractsTrigger]
      },
      forAll(_)(
        _.warningMessage should include(
          s"has 2 ValidatorUnpermission contracts"
        )
      ),
    )
  }
}
