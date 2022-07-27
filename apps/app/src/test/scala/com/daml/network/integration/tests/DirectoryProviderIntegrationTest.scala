package com.daml.network.integration.tests

import com.daml.ledger.api.refinements.ApiTypes
import com.daml.network.console.{LocalValidatorAppReference, LocalWalletAppReference}
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
  IsolatedCoinEnvironments,
}
import com.daml.network.util.{CoinCreation, CommonCoinAppInstanceReferences}
import com.daml.network.wallet.ExpiringQuantity
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.network.CN.{Directory => codegen}

class DirectoryProviderIntegrationTest
    extends CoinIntegrationTest
    with IsolatedCoinEnvironments
    with CoinCreation
    with CommonCoinAppInstanceReferences {
  override val defaultParticipant: String = "participant1"
  // same as damlUser in config
  private val damlUser = "provider"
  private val quantity = 100d
  private val directoryDarPath = "apps/directory-provider/daml/.daml/dist/directory-service.dar"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition.simpleTopology.withSetup(env => {
      import env._
      participants.all.map(_.dars.upload(directoryDarPath))
      participants.all.foreach(_.domains.connect_local(da))
    })

  "A directory provider" should {
    "list and accept install requests" in { implicit env =>
      import env._

      svc.initialize()
      val svcParty =
        svc.remoteParticipant.parties.list(filterParty = "svc").headOption.value.party

      val pId = directoryProvider.remoteParticipant.parties.enable(damlUser, Some(damlUser))
      directoryProvider.remoteParticipant.ledger_api.users
        .create(damlUser, Set(pId.toLf), Some(pId.toLf))

      directoryProvider.listInstallRequests() shouldBe Seq()

      directoryProvider.remoteParticipant.ledger_api.commands.submit_flat(
        actAs = Seq(pId),
        commands = Seq(
          codegen
            .DirectoryInstallRequest(
              pId.toPrim,
              pId.toPrim,
            )
            .create
            .command
        ),
        // See https://github.com/DACH-NY/the-real-canton-coin/issues/315
        optTimeout = None,
      )
      directoryProvider.remoteParticipant.ledger_api.acs.await(pId, codegen.DirectoryInstallRequest)

      val requests = directoryProvider.listInstallRequests()

      inside(requests) { case Seq(request) =>
        request.user shouldBe pId
      }

      val installsBefore = directoryProvider.remoteParticipant.ledger_api.acs
        .of_party(pId, filterTemplates = Seq(codegen.DirectoryInstall.id))
      installsBefore shouldBe empty

      requests.foreach { case request =>
        directoryProvider.acceptInstallRequest(request.contractId, svcParty)
      }

      val installsAfter = directoryProvider.remoteParticipant.ledger_api.acs
        .of_party(pId, filterTemplates = Seq(codegen.DirectoryInstall.id))
      installsAfter should have length (1)

      directoryProvider.listInstallRequests() shouldBe empty

    }
  }
}
