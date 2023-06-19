package com.daml.network.integration.tests

import com.daml.network.console.{
  ScanAppBackendReference,
  SvAppBackendReference,
  ValidatorAppBackendReference,
}

import scala.jdk.CollectionConverters.*

class SvInitializationIntegrationTest extends SvIntegrationTestBase {

  "start and restart cleanly" in { implicit env =>
    initSvc()
    sv1.stop()
    sv1.startSync()
  }

  "can start before its validator app" in { implicit env =>
    // we want this so we can have a clear init dependency validator app -> sv app
    clue("Starting founding SV's SV app") {
      sv1.startSync()
    }
    clue("Starting founding SV's scan app") {
      // validators need this
      sv1Scan.startSync()
    }
    clue("Starting founding SV's validator app") {
      sv1Validator.startSync()
    }
    clue("Starting joining SV's SV app") {
      sv2.startSync()
    }
    clue("Starting joining SV's validator app") {
      sv2Validator.startSync()
    }
  }

  "connect to all domains during initialization" in { implicit env =>
    initSvc()
    sv4.stop()

    val globalDomainId = inside(sv4.participantClient.domains.list_connected()) {
      case Seq(domain) =>
        domain.domainId
    }

    clue("simulate the domain was left disconnected when error occur during party migration.") {
      sv4.participantClient.domains.disconnect_all()
      sv4.participantClient.domains.list_connected() shouldBe empty
    }

    clue("sv will connect to all domains during initialization.") {
      sv4.startSync()
      inside(sv4.participantClient.domains.list_connected()) {
        case Seq(listConnectedDomainsResult) =>
          listConnectedDomainsResult.domainId shouldBe globalDomainId
      }
    }
  }

  // A test to make debugging bootstrap problems easier
  "SV apps can start one by one" in { implicit env =>
    clue("Starting SVC app and SV1 app") {
      startAllSync(sv1Scan, sv1Validator, sv1)
    }

    def startSv(
        number: Int,
        sv: SvAppBackendReference,
        validator: ValidatorAppBackendReference,
        scanApp: Option[ScanAppBackendReference] = None,
    ) =
      clue(s"Starting SV$number app") {
        validator.start()
        sv.start()
        scanApp.foreach(_.start())
        validator.waitForInitialization()
        sv.waitForInitialization()
        scanApp.foreach(_.waitForInitialization())
      }

    startSv(2, sv2, sv2Validator, Some(sv2Scan))
    startSv(3, sv3, sv3Validator)
    startSv(4, sv4, sv4Validator)
  }

  "The SVC is bootstrapped correctly" in { implicit env =>
    initSvc()
    val svcRules = clue("An SvcRules contract exists") {
      sv1.getSvcInfo()
    }
    val svParties = clue("We have four sv parties and their apps are online") {
      svs.map(_.getSvcInfo().svParty.toProtoPrimitive)
    }
    clue("The four sv apps are all svc members and there are no other svc members") {
      sv1.getSvcInfo().svcRules.payload.members.keySet() should equal(svParties.toSet.asJava)
    }
    clue("The founding SV app (sv1) is the first leader") {
      sv1.getSvcInfo().svcRules.payload.leader should equal(svcRules.svParty.toProtoPrimitive)
    }
    clue("initial open mining rounds are created") {
      eventually() {
        sv1.listOpenMiningRounds() should have size 3
        sv1Scan.getOpenAndIssuingMiningRounds()._1 should have size 3
        sv2Scan.getOpenAndIssuingMiningRounds()._1 should have size 3
      }
    }
  }

}
