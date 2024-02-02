package com.daml.network.integration.tests.migration

import cats.implicits.{catsSyntaxApplicativeId, catsSyntaxParallelTraverse1}
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTestWithSharedEnvironment,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.auth.PreflightAuthUtil
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.protocol.DynamicDomainParameters
import com.digitalasset.canton.util.FutureInstances.parallelFuture

import scala.concurrent.Future

class GlobalDomainUpgradeClusterPreflightIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with PreflightAuthUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition.preflightTopology(
      this.getClass.getSimpleName
    )

  "Global domain is paused and dump is created" in { implicit env =>
    import env.executionContext

    val svsWithAuth = env.svs.remote.map { sv =>
      svclWithToken(sv.name)
    }

    clue(s"Pausing global domain everywhere") {
      eventuallySucceeds() {
        svsWithAuth.parTraverse { sv =>
          sv.pauseGlobalDomain().pure[Future]
        }.futureValue
      }
    }
    // TODO(#8761) move the sleep in the app
    Threading.sleep(
      (DynamicDomainParameters.defaultMediatorReactionTimeout + DynamicDomainParameters.defaultParticipantResponseTimeout).duration.toMillis
    )
    clue(s"Trigger domain dump") {
      eventuallySucceeds() {
        svsWithAuth.parTraverse { sv =>
          sv.triggerGlobalDomainMigrationDump(1L).pure[Future]
        }.futureValue
      }
    }

  }

}
