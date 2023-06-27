package com.daml.network.integration.tests

import better.files.{File, *}
import com.daml.network.config.{CNNodeConfig, CNNodeConfigTransforms}
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.ProcessTestUtil
import com.daml.network.validator.admin.api.client.commands.HttpValidatorAdminAppClient
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import monocle.macros.syntax.lens.*

import java.nio.file.Files
import scala.util.Using

class ParticipantExportImportIntegrationTest extends CNNodeIntegrationTest with ProcessTestUtil {

  val testResourcesPath: File = "apps" / "app" / "src" / "test" / "resources"
  val svNodePath: File = testResourcesPath / "local-sv-node"

  val svParticipantPath: File = svNodePath / "canton-participant"
  val svDomainPath: File = svNodePath / "canton-domain"
  val svAppsPath: File = svNodePath / "sv-apps"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] = {
    CNNodeEnvironmentDefinition
      .fromFiles(
        this.getClass.getSimpleName,
        // Config that runs against long-running Canton; sv1 defined here
        testResourcesPath / "simple-topology.conf",
        // Config that runs against Canton started from this test; sv1-local defined here
        svAppsPath / "app.conf",
      )
      // Notably, we also don't use fresh Daml names. Apart from creating users we need,
      // we only read from the long-running Canton, so this should be fine.
      // TODO(#6128) use per-run Daml names
      .clearConfigTransforms()
      .addConfigTransforms(
        (_, config) => useSelfSignedTokensForLongRunningLedgerApiAuth("test", config),
        (_, config) => useSelfSignedTokensForLongRunningLedgerApiAuth("test", config),
      )
      .withManualStart
  }

  "We can export a Canton participant identity and import it in a new participant" in {
    implicit env =>
      startAllSync(sv1, sv1Scan, sv1Validator)

      val svcInfoBefore = sv1.getSvcInfo()
      val participantIdentity = sv1Validator.exportParticipantIdentity()

      sv1Validator.stop()
      sv1Scan.stop()
      sv1.stop()

      // TODO(#6128) run whole test once we have moved away from using a bootstrap script for upload OR have bumped Canton so that init_id works
      if (false) {
        Using.resource(startStandaloneCanton(participantIdentity)) { _ =>
          sv1Local.startSync()

          val svcInfoAfter = sv1Local.getSvcInfo()

          svcInfoAfter.svParty shouldBe svcInfoBefore.svParty
          svcInfoAfter.svcParty shouldBe svcInfoBefore.svcParty

        // TODO(#6073) also spin up a new validator app and compare the exported participant identity
        }
      }
  }

  private def startStandaloneCanton(
      participantIdentity: HttpValidatorAdminAppClient.ParticipantIdentity
  ) = {
    // TODO(#6073) Remove this complicated mess once identity upload is handled by the validator and SV app init
    val txes = "Seq(" + participantIdentity.bootstrapTxes
      .map(tx =>
        s"""SignedTopologyTransactionX.fromByteArray(Array(${tx.mkString(
            ", "
          )})).getOrElse(sys.error("encoding error"))"""
      )
      .mkString(", ") + ")"
    val txesUploadCommand = s"""
    |import com.digitalasset.canton.topology.transaction.SignedTopologyTransactionX
    |svc_participant.topology.transactions.load($txes)
    """

    val keyUploadCommands = participantIdentity.keys.zipWithIndex
      .map {
        case (k, i) => {
          val keyFile: File = Files.createTempFile(s"$i", ".key")
          keyFile.appendByteArray(k.keyPair)
          s"""
          |svc_participant.keys.secret.upload("${keyFile.toString}", ${k.name.fold("None")(n =>
              s"Some(\"$n\")"
            )})
          """
        }
      }
      .mkString("\n")

    val participantInitializationCommand = s"""
    |svc_participant.topology.init_id("${participantIdentity.id.uid.id}", "${participantIdentity.id.uid.namespace.fingerprint}")
    """

    val bootstrapScript = s"""
      |println("Loading bootstrap topology transactions")
      |svc_participant.keys.secret.list()
      $txesUploadCommand
      |println(svc_participant.topology.transactions.list())
      |println("Uploading participant keys")
      $keyUploadCommands
      |println(svc_participant.keys.secret.list())
      |println("Initializing participant with uid ${participantIdentity.id.toString}")
      $participantInitializationCommand
      |println("Creating sv1 user")
      |svc_participant.ledger_api.users.create(
      |  id = "sv1",
      |  actAs = Set.empty,
      |  readAs = Set.empty,
      |  primaryParty = None,
      |  participantAdmin = true,
      |)
      |""".stripMargin

    val bootstrapFile: File = Files.createTempFile("canton-bootstrap", ".sc")
    bootstrapFile.overwrite(bootstrapScript)

    val cantonArgs = Seq(
      "-c",
      (svParticipantPath / "canton.conf").toString,
      "-c",
      (svDomainPath / "canton.conf").toString,
      "-C",
      "canton.participants-x.svc_participant.init.auto-init=false", // avoid creating new identity
      "--bootstrap",
      bootstrapFile.toString,
    )

    startCanton(cantonArgs, "participant-export-import")
  }

  // TODO(tech-debt) Consider removing this method in favor of making `useSelfSignedTokensForLedgerApiAuth` take an `ignore` parameter
  private def useSelfSignedTokensForLongRunningLedgerApiAuth(
      secret: String,
      config: CNNodeConfig,
  ): CNNodeConfig = {
    val enableAuth =
      CNNodeConfigTransforms.selfSignedTokenAuthSourceTransform(config.parameters.clock, secret)
    val transforms = Seq(
      CNNodeConfigTransforms.updateAllSvAppConfigs((name, c) =>
        if (name.endsWith("Local")) {
          c
        } else {
          c.focus(_.participantClient.ledgerApi).modify(enableAuth(c.ledgerApiUser, _))
        }
      ),
      CNNodeConfigTransforms.updateAllValidatorConfigs_(c => {
        c.focus(_.participantClient.ledgerApi).modify(enableAuth(c.ledgerApiUser, _))
      }),
      CNNodeConfigTransforms.updateAllScanAppConfigs_(c => {
        c.focus(_.participantClient.ledgerApi).modify(enableAuth(c.svUser, _))
      }),
    )
    transforms.foldLeft(config)((c, tf) => tf(c))
  }
}
