package com.daml.network.config

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import org.scalatest.wordspec.AnyWordSpecLike

class CNThresholdsTest extends AnyWordSpecLike with BaseTest {

  "thresholds" should {
    "return expected svc consortium thresholds" in {
      forAll(Table(("consortium members", "threshold"), (1, 1), (2, 2), (3, 2), (4, 3))) {
        (consortiumMembers: Int, threshold: Int) =>
          CNThresholds.unionspaceThreshold(consortiumMembers) shouldBe PositiveInt.tryCreate(
            threshold
          )
      }
    }

    "return expected f based svc consortium thresholds" in {
      forAll(Table(("consortium members", "threshold"), (1, 1), (2, 1), (3, 1), (4, 2))) {
        (consortiumMembers: Int, threshold: Int) =>
          CNThresholds.sequencerConnectionsSizeThreshold(consortiumMembers) shouldBe PositiveInt
            .tryCreate(
              threshold
            )
      }
    }

    "return expected f based minimum svc consortium thresholds" in {
      forAll(
        Table(
          ("svc size", "mapping specific size", "threshold"),
          (1, 1, 1),
          (1, 2, 1),
          (1, 4, 1),
          (4, 1, 1),
          (2, 2, 1),
          (3, 3, 2),
          (3, 4, 2),
          (4, 4, 2),
        )
      ) { (svcSize: Int, mappingSpecificSize: Int, threshold: Int) =>
        CNThresholds.mediatorDomainStateThresholdWithNewMember(
          svcSize,
          mappingSpecificSize,
        ) shouldBe PositiveInt
          .tryCreate(threshold)
        CNThresholds.partyToParticipantThresholdWithNewMember(
          svcSize,
          mappingSpecificSize,
        ) shouldBe PositiveInt
          .tryCreate(threshold)
      }
    }
  }

}
