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
    sv1Backend.stop()
    sv1Backend.startSync()
  }

  "can start before its validator app" in { implicit env =>
    // we want this so we can have a clear init dependency validator app -> sv app
    clue("Starting founding SV's SV app") {
      sv1Backend.startSync()
    }
    clue("Starting founding SV's scan app") {
      // validators need this
      sv1ScanBackend.startSync()
    }
    clue("Starting founding SV's validator app") {
      sv1ValidatorBackend.startSync()
    }
    clue("Starting joining SV's SV app") {
      sv2Backend.startSync()
    }
    clue("Starting joining SV's validator app") {
      sv2ValidatorBackend.startSync()
    }
  }

  "connect to all domains during initialization" in { implicit env =>
    initSvc()
    sv4Backend.stop()

    val globalDomainId = inside(sv4Backend.participantClient.domains.list_connected()) {
      case Seq(domain) =>
        domain.domainId
    }

    clue("simulate the domain was left disconnected when error occur during party migration.") {
      sv4Backend.participantClient.domains.disconnect_all()
      sv4Backend.participantClient.domains.list_connected() shouldBe empty
    }

    clue("sv will connect to all domains during initialization.") {
      sv4Backend.startSync()
      inside(sv4Backend.participantClient.domains.list_connected()) {
        case Seq(listConnectedDomainsResult) =>
          listConnectedDomainsResult.domainId shouldBe globalDomainId
      }
    }
  }

  // A test to make debugging bootstrap problems easier
  "SV apps can start one by one" in { implicit env =>
    clue("Starting SVC app and SV1 app") {
      startAllSync(sv1ScanBackend, sv1ValidatorBackend, sv1Backend)
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

    startSv(2, sv2Backend, sv2ValidatorBackend, Some(sv2ScanBackend))
    startSv(3, sv3Backend, sv3ValidatorBackend)
    startSv(4, sv4Backend, sv4ValidatorBackend)
  }

  "The SVC is bootstrapped correctly" in { implicit env =>
    initSvc()
    val svcRules = clue("An SvcRules contract exists") {
      sv1Backend.getSvcInfo()
    }
    val svParties = clue("We have four sv parties and their apps are online") {
      svs.map(_.getSvcInfo().svParty.toProtoPrimitive)
    }
    clue("The four sv apps are all svc members and there are no other svc members") {
      sv1Backend.getSvcInfo().svcRules.payload.members.keySet() should equal(svParties.toSet.asJava)
    }
    clue("The founding SV app (sv1) is the first leader") {
      sv1Backend.getSvcInfo().svcRules.payload.leader should equal(
        svcRules.svParty.toProtoPrimitive
      )
    }
    clue("initial open mining rounds are created") {
      eventually() {
        sv1Backend.listOpenMiningRounds() should have size 3
        sv1ScanBackend.getOpenAndIssuingMiningRounds()._1 should have size 3
        sv2ScanBackend.getOpenAndIssuingMiningRounds()._1 should have size 3
      }
    }
  }

}
