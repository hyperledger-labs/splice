package org.lfdecentralizedtrust.splice.config

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.topology.ParticipantId
import com.digitalasset.canton.topology.transaction.{HostingParticipant, ParticipantPermission}
import org.scalatest.wordspec.AnyWordSpecLike

class ThresholdsTest extends AnyWordSpecLike with BaseTest {

  "thresholds" should {
    "return expected DSO thresholds" in {
      forAll(Table(("SVs", "threshold"), (1, 1), (2, 2), (3, 2), (4, 3))) {
        (svs: Int, threshold: Int) =>
          Thresholds.decentralizedNamespaceThreshold(svs) shouldBe PositiveInt
            .tryCreate(
              threshold
            )
      }
    }

    "return expected f based DSO thresholds" in {
      forAll(Table(("SVs", "threshold"), (1, 1), (2, 1), (3, 1), (4, 2))) {
        (svs: Int, threshold: Int) =>
          Thresholds.sequencerConnectionsSizeThreshold(svs) shouldBe PositiveInt
            .tryCreate(
              threshold
            )
      }
    }

    "return expected f based minimum DSO thresholds" in {
      forAll(
        Table(
          ("mapping specific size", "threshold"),
          (1, 1),
          (2, 1),
          (3, 1),
          (4, 2),
        )
      ) { (mappingSpecificSize: Int, threshold: Int) =>
        Thresholds.mediatorDomainStateThreshold(
          mappingSpecificSize
        ) shouldBe PositiveInt
          .tryCreate(threshold)
      }
      forAll(
        Table(
          ("mapping specific size", "threshold"),
          (1, 1),
          (2, 2),
          (3, 2),
          (4, 3),
          (5, 4),
          (6, 4),
          (7, 5),
        )
      ) { (mappingSpecificSize: Int, threshold: Int) =>
        Thresholds.partyToParticipantThreshold(
          mkHostingParticipantsOfSize(mappingSpecificSize)
        ) shouldBe PositiveInt
          .tryCreate(threshold)
      }
    }
  }

  private def mkHostingParticipantsOfSize(size: Int): Seq[HostingParticipant] = {
    (1 to size).map(i =>
      HostingParticipant(
        ParticipantId.tryFromProtoPrimitive(s"PAR::participant$i::dummy"),
        ParticipantPermission.Submission,
      )
    )
  }
}
