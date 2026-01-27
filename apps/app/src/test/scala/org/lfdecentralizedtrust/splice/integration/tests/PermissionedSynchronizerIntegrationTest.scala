package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.{PostgresAroundEach, ProcessTestUtil, WalletTestUtil}

class PermissionedSynchronizerIntegrationTest
    extends IntegrationTest
    with ProcessTestUtil
    with PostgresAroundEach
    with WalletTestUtil {

  // It will fail to find the validator of alice_validator_wallet_user
  override protected def runTokenStandardCliSanityCheck: Boolean = false

  override def usesDbs: Seq[String] = Seq(
    "alice_participant_new"
  )

  // Runs against a temporary Canton instance.
  //  override lazy val resetRequiredTopologyState = false

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withPreSetup(_ => ())
      .withAllocatedUsers(extraIgnoredValidatorPrefixes = Seq("aliceValidator"))
      .addConfigTransforms(
        (_, config) =>
          ConfigTransforms.bumpSomeValidatorAppPortsBy(22_000, Seq("aliceValidator"))(config),
        (_, config) =>
          ConfigTransforms.bumpSomeWalletClientPortsBy(22_000, Seq("aliceWallet"))(config),
      )
      .withManualStart

  "start validator in permissioned mode" in { implicit env =>
    initDsoWithSv1Only()
    withCanton(
      Seq(
        testResourcesPath / "standalone-participant-extra.conf"
      ),
      Seq(
      ),
      "aliceValidatorExtra",
      "EXTRA_PARTICIPANT_ADMIN_USER" -> aliceValidatorBackend.config.ledgerApiUser,
      "EXTRA_PARTICIPANT_DB" -> "alice_participant_new",
    ) {
      aliceValidatorBackend.startSync()
    }
  }
}
