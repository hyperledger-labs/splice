package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn
import com.daml.network.integration.tests.CoinTests.CoinIntegrationTest

import scala.jdk.CollectionConverters.*

class SvIntegrationTest extends CoinIntegrationTest {

  "restart cleanly" in { implicit env =>
    sv1.stop()
    sv1.startSync()
  }

  "An SvcRules contract exists and has exactly all four sv parties as members" in { implicit env =>
    val svcRules = clue("There is exactly one SvcRules contract") {
      val foundSvcRules = svc.remoteParticipantWithAdminToken.ledger_api.acs
        .filterJava(cn.svcrules.SvcRules.COMPANION)(svcParty)
      foundSvcRules should have length 1
      foundSvcRules.head
    }
    val svParties = clue("We have four sv parties and their apps are online") {
      Seq(sv1, sv2, sv3, sv4).map(_.getDebugInfo().svParty.toProtoPrimitive)
    }
    clue("The four sv apps are all svc members and there are no other svc members") {
      svcRules.data.members.keySet should equal(svParties.toSet.asJava)
    }
  }
}
