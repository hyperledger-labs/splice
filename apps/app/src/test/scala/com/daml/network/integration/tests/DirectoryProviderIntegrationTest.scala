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
    })

  "A directory provider" should {
    "call listInstallRequests" in { implicit env =>
      import env._

      directoryProvider.remoteParticipant.domains.connect_local(da)
      val pId = directoryProvider.remoteParticipant.parties.enable(damlUser, Some(damlUser))
      directoryProvider.remoteParticipant.ledger_api.users
        .create(damlUser, Set(pId.toLf), Some(pId.toLf))

      directoryProvider.listInstallRequests() shouldBe Seq()

      directoryProvider.remoteParticipant.ledger_api.commands.submit_flat(
        actAs = Seq(pId),
        commands = Seq(
          codegen
            .DirectoryInstallRequest(
              ApiTypes.Party(pId.toProtoPrimitive),
              ApiTypes.Party(pId.toProtoPrimitive),
            )
            .create
            .command
        ),
        // TODO (MK): Without disabling this, the cross-participant synchronization fails because
        // Canton tries to call GetTransactionById with an empty list of requesting_parties.
        // I don’t quite understand what fails there.
        optTimeout = None,
      )
      directoryProvider.remoteParticipant.ledger_api.acs.await(pId, codegen.DirectoryInstallRequest)

      inside(directoryProvider.listInstallRequests()) { case Seq(request) =>
        request.user shouldBe pId
      }

    }
  }
}
