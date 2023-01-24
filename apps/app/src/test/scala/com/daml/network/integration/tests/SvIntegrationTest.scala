package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
}

import scala.jdk.CollectionConverters.*

class SvIntegrationTest extends CoinIntegrationTest {

  "restart cleanly" in { implicit env =>
    sv1.stop()
    sv1.startSync()
  }

  "An SvcRules contract exists and has exactly all four sv parties as members" in { implicit env =>
    val svcRules = getSvcRules()
    val svParties = clue("We have four sv parties and their apps are online") {
      svs.map(_.getDebugInfo().svParty.toProtoPrimitive)
    }
    clue("The four sv apps are all svc members and there are no other svc members") {
      svcRules.data.members.keySet should equal(svParties.toSet.asJava)
    }
  }

  "The founding SV app (sv1) is the first leader" in { implicit env =>
    getSvcRules().data.leader should equal(sv1.getDebugInfo().svParty.toProtoPrimitive)
  }

  "After init, no single SV can act as the SVC party but all can read as it" in { implicit env =>
    svs.foreach(sv => {
      val rights = sv.remoteParticipant.ledger_api.users.rights.list(sv.config.ledgerApiUser)
      rights.actAs should not contain (svcParty.toLf)
      rights.readAs should contain(svcParty.toLf)
    })
  }

  def getSvcRules()(implicit env: CoinTestConsoleEnvironment) =
    clue("There is exactly one SvcRules contract") {
      val foundSvcRules = svc.remoteParticipantWithAdminToken.ledger_api.acs
        .filterJava(cn.svcrules.SvcRules.COMPANION)(svcParty)
      foundSvcRules should have length 1
      foundSvcRules.head
    }
}
