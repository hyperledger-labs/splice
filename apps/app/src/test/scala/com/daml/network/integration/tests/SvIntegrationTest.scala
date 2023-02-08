package com.daml.network.integration.tests

import com.daml.network.codegen.java.{cc, cn}
import com.daml.network.console.LedgerApiUtils
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
}
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import monocle.macros.syntax.lens.*

import scala.jdk.CollectionConverters.*

class SvIntegrationTest extends CoinIntegrationTest {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .addConfigTransforms((_, conf) => conf.focus(_.parameters.manualStart).replace(true))
      // We manually start apps so we disable the default setup
      // that blocks on all apps being initialized.
      .withNoSetup()

  def initSvc()(implicit env: CoinTestConsoleEnvironment) = {
    env.appsHostedBySvc.local.foreach(_.start())
    env.appsHostedBySvc.local.foreach(_.waitForInitialization())
  }

  "start and restart cleanly" in { implicit env =>
    initSvc()
    sv1.stop()
    sv1.startSync()
  }

  "The SVC is bootstrapped correctly" in { implicit env =>
    initSvc()
    val svcRules = clue("An SvcRules contract exists") { getSvcRules() }
    val svParties = clue("We have four sv parties and their apps are online") {
      svs.map(_.getDebugInfo().svParty.toProtoPrimitive)
    }
    clue("The four sv apps are all svc members and there are no other svc members") {
      svcRules.data.members.keySet should equal(svParties.toSet.asJava)
    }
    clue("The founding SV app (sv1) is the first leader") {
      getSvcRules().data.leader should equal(sv1.getDebugInfo().svParty.toProtoPrimitive)
    }
  }

  "SV parties can't act as the SVC party and can read as both themselves and the SVC party" in {
    implicit env =>
      initSvc()
      svs.foreach(sv => {
        val rights = sv.remoteParticipant.ledger_api.users.rights.list(sv.config.ledgerApiUser)
        rights.actAs should not contain (svcParty.toLf)
        rights.readAs should contain(svcParty.toLf)
      })
      actAndCheck(
        "creating a `ValidatorOnboarding` contract readable only by sv3", {
          val sv = sv3 // it doesn't really matter which sv we pick
          val svParty = sv.getDebugInfo().svParty
          sv.getDebugInfo().ongoingValidatorOnboardings shouldBe 0
          LedgerApiUtils.submitWithResult(
            sv.remoteParticipant,
            sv.config.ledgerApiUser,
            actAs = Seq(svParty),
            readAs = Seq.empty,
            update = new cn.validatoronboarding.ValidatorOnboarding(
              svParty.toProtoPrimitive,
              "test",
              env.environment.clock.now.toInstant.plusSeconds(3600),
            ).create,
          )
        },
      )(
        "sv3's store ingests the contract",
        _ => sv3.getDebugInfo().ongoingValidatorOnboardings shouldBe 1,
      )
  }

  "Non-leader SVs can onboard new validators" in { implicit env =>
    initSvc()
    val sv = sv2 // not a leader
    clue("create an onboarding contract") {
      // TODO(#2657) use an api call for this
      val svParty = sv.getDebugInfo().svParty
      LedgerApiUtils.submitWithResult(
        sv.remoteParticipant,
        sv.config.ledgerApiUser,
        actAs = Seq(svParty),
        readAs = Seq.empty,
        update = new cn.validatoronboarding.ValidatorOnboarding(
          svParty.toProtoPrimitive,
          "dummysecret",
          env.environment.clock.now.toInstant.plusSeconds(3600),
        ).create,
      )
    }
    val candidate = clue("create a dummy party") {
      bobValidator.remoteParticipantWithAdminToken.parties.enable(
        "dummy" + env.environment.config.name.getOrElse("")
      )
    }
    clue("try to onboard with a wrong secret, which should fail") {
      assertThrows[CommandFailure](
        loggerFactory.assertLogs(
          sv.onboardValidator(candidate, "wrongsecret")
        )
      )
    }
    actAndCheck(
      "request to onboard the candidate",
      sv.onboardValidator(candidate, "dummysecret"),
    )(
      "the candidate is now an observer to the CoinRules",
      Unit => getCoinRules().observers should contain(candidate.toProtoPrimitive),
    )
    clue("try to reuse the same secret for a second onboarding, which should fail") {
      assertThrows[CommandFailure](
        loggerFactory.assertLogs(
          sv.onboardValidator(candidate, "dummysecret")
        )
      )
    }
  }

  "SVs expect onboardings when asked to" in { implicit env =>
    initSvc()
    clue("SV2 has created many ValidatorOnboarding contracts as it's configured to.") {
      sv2.getDebugInfo().ongoingValidatorOnboardings shouldBe 1
    }
    clue("SV2 doesn't recreate ValidatorOnboarding contracts on restart...") {
      sv2.stop()
      sv2.startSync()
      sv2.getDebugInfo().ongoingValidatorOnboardings shouldBe 1
    }
    // TODO(#2733) finish/activate this test
    clue("...even if an onboarding was completed in the meantime...") {
      bobValidator.startSync()
      sv2.getDebugInfo().ongoingValidatorOnboardings shouldBe 0
      sv2.stop()
      sv2.startSync()
      // sv2.getDebugInfo().ongoingValidatorOnboardings shouldBe 1
    }
  }

  def getSvcRules()(implicit env: CoinTestConsoleEnvironment) =
    clue("There is exactly one SvcRules contract") {
      val foundSvcRules = svc.remoteParticipantWithAdminToken.ledger_api.acs
        .filterJava(cn.svcrules.SvcRules.COMPANION)(svcParty)
      foundSvcRules should have length 1
      foundSvcRules.head
    }

  def getCoinRules()(implicit env: CoinTestConsoleEnvironment) =
    clue("There is exactly one CoinRules contract") {
      val foundCoinRules = svc.remoteParticipantWithAdminToken.ledger_api.acs
        .filterJava(cc.coin.CoinRules.COMPANION)(svcParty)
      foundCoinRules should have length 1
      foundCoinRules.head
    }
}
