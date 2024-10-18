package org.lfdecentralizedtrust.splice.util

import com.digitalasset.canton.BaseTest
import com.daml.ledger.javaapi.data as j
import j.codegen as jcg
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks.{
  forAll as scForAll,
  PropertyCheckConfiguration,
}

import scala.jdk.CollectionConverters.*

class TransactionTreeExtensionsTest extends AnyWordSpec with BaseTest {
  import TransactionTreeExtensions.*
  import TransactionTreeExtensionsTest.*

  implicit val propConfig: PropertyCheckConfiguration =
    org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks.generatorDrivenConfig
      .copy(minSuccessful = 100)

  "preorderDescendants" should {
    "respond for all members of the tree with other members" in scForAll { (t: j.TransactionTree) =>
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
        override def jsonEncoder() = fail("jsonEncoder should not be used")
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
              override def getCompanion() = fakeCompanion
            },
          Seq.empty.asJava,
        )

      val expectedContract = Contract(
        fakeCompanion.TEMPLATE_ID,
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
  import j.Generators

  implicit val `arb TransactionTree`: Arbitrary[j.TransactionTree] = Arbitrary(
    Generators.transactionTreeGen.map(j.TransactionTree.fromProto)
  )

  val txTreeWithGuaranteedCreate = Generators.transactionTreeGen
    .filter(_.getEventsByIdMap.asScala.values.exists(_.hasCreated))
    .map(j.TransactionTree.fromProto)
}
