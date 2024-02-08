package com.daml.network.integration.tests.migration

import com.daml.network.codegen.java.cn.svcrules.DomainUpgradeSchedule
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTestWithSharedEnvironment,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.auth.PreflightAuthUtil
import com.daml.network.util.SvTestUtil
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import java.time.Instant
import java.time.temporal.ChronoUnit

class GlobalDomainUpgradeClusterPreflightIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with PreflightAuthUtil
    with SvTestUtil {

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

    val svToCreateVoteRequest = svsWithAuth.headOption.value
    svsWithAuth.tail.size should be >= 2
    val svsToVote = svsWithAuth.tail.take(2)

    clue(s"schedule domain migration") {
      val scheduledTime = Instant.now().plus(10, ChronoUnit.SECONDS)
      scheduleDomainMigration(
        svToCreateVoteRequest,
        svsToVote,
        Some(new DomainUpgradeSchedule(scheduledTime, 1L)),
      )
    }
  }
}
