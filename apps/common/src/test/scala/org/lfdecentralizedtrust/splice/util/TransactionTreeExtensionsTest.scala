package org.lfdecentralizedtrust.splice.util

import com.daml.ledger.javaapi.data as j
import com.daml.ledger.javaapi.data.codegen as jcg
import com.digitalasset.canton.BaseTest
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks.{
  PropertyCheckConfiguration,
  forAll as scForAll,
}

import scala.jdk.CollectionConverters.*

class TransactionTreeExtensionsTest extends AnyWordSpec with BaseTest {
  import TransactionTreeExtensions.*
  import TransactionTreeExtensionsTest.*

  implicit val propConfig: PropertyCheckConfiguration =
    org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks.generatorDrivenConfig
      .copy(minSuccessful = 100)

  "preorderDescendants" should {
    "respond for all members of the tree with other members" in scForAll { (t: j.Transaction) =>
      val events = t.getEventsById.asScala.values.toSeq
      forEvery(Table("event", events*)) { e =>
        val result = t.preorderDescendants(e).toSeq
        events should contain allElementsOf result
        result shouldNot contain(e)
      }
    }
  }

  "findCreation" should {
    "find a created event when it's there" in scForAll(txTreeWithGuaranteedCreate) { t =>
      val c = t.getEventsById.asScala.valuesIterator.collectFirst { case c: j.CreatedEvent =>
        c
      }.value
      case class FakeTpl() extends jcg.DamlRecord[FakeTpl] {
        override def toValue() = c.getArguments
        override def jsonEncoder(): Nothing = fail("jsonEncoder should not be used")
      }
      type FakeId = jcg.ContractId[FakeTpl]
      lazy val fakeCompanion: jcg.ContractCompanion.WithoutKey[jcg.Contract[
        FakeId,
        FakeTpl,
      ], FakeId, FakeTpl] =
        new jcg.ContractCompanion.WithoutKey[jcg.Contract[
          FakeId,
          FakeTpl,
        ], FakeId, FakeTpl](
          new jcg.ContractTypeCompanion.Package(
            c.getTemplateId.getPackageId,
            c.getPackageName,
            j.PackageVersion.unsafeFromString("0.1.0"),
          ),
          "irrelevant",
          c.getTemplateId,
          new jcg.ContractId(_),
          { v =>
            v shouldBe c.getArguments
            FakeTpl()
          },
          _ => FakeTpl(),
          (i, d, s, o) =>
            new jcg.Contract[FakeId, FakeTpl](i, d, s, o) {
              override def getCompanion()
                  : com.daml.ledger.javaapi.data.codegen.ContractCompanion.WithoutKey[
                    com.daml.ledger.javaapi.data.codegen.Contract[FakeId, FakeTpl],
                    FakeId,
                    FakeTpl,
                  ] = fakeCompanion
            },
          Seq.empty.asJava,
        )

      val expectedContract = Contract(
        fakeCompanion.getTemplateIdWithPackageId,
        new jcg.ContractId(c.getContractId),
        FakeTpl(),
        c.getCreatedEventBlob,
        c.getCreatedAt,
      )
      t.findCreation(fakeCompanion, new jcg.ContractId(c.getContractId)) shouldBe
        Some(expectedContract)
    }
  }
}

object TransactionTreeExtensionsTest {
  import org.scalacheck.Arbitrary

  implicit val `arb TransactionTree`: Arbitrary[j.Transaction] = Arbitrary(
    Generators.transactionGen.map(j.Transaction.fromProto)
  )

  val txTreeWithGuaranteedCreate = Generators.transactionGen
    .filter(_.getEventsList.asScala.exists(_.hasCreated))
    .map(j.Transaction.fromProto)
}
