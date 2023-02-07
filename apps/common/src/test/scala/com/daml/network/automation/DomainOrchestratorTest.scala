package com.daml.network.automation

import com.digitalasset.canton.BaseTestWordSpec
import org.scalatestplus.scalacheck.{ScalaCheckDrivenPropertyChecks as SCP}

class DomainOrchestratorTest extends BaseTestWordSpec {
  import SCP.generatorDrivenConfig

  "mapOrAbort" should {
    import DomainOrchestrator.mapOrAbort

    type Input = Seq[Byte]

    "identity" in SCP.forAll { in: Input =>
      mapOrAbort(in)(Right(_)) shouldBe ((in, None))
    }

    "stop on left" in SCP.forAll { in: Input =>
      val zero = in.indexOf(0: Byte)
      val mapped = mapOrAbort(in)(i => if (i == 0) Left(0) else Right(i))
      mapped shouldBe (if (zero < 0) (in, None) else (in take zero, Some(0)))
    }

    "invoke one time" in SCP.forAll { in: Input =>
      mapOrAbort(in map { a =>
        var done = false
        () =>
          if (done) fail("double invocation")
          else {
            done = true
            a
          }
      })(f => Right(f()))
    }
  }
}
