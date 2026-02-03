package org.lfdecentralizedtrust.splice.unit.store.db

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.digitalasset.daml.lf.data.Time.Timestamp
import org.lfdecentralizedtrust.splice.store.db.{AcsJdbcTypes, SplicePostgresTest}
import org.lfdecentralizedtrust.splice.util.QualifiedName
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.data.Offset
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import io.circe.Json
import org.scalatest.wordspec.AsyncWordSpec
import slick.jdbc.{JdbcProfile, PostgresProfile}

import scala.concurrent.duration.DurationInt
import scala.concurrent.Await

class AcsJdbcTypesTest
    extends AsyncWordSpec
    with AcsJdbcTypes
    with BaseTest
    with SplicePostgresTest {

  override val profile: JdbcProfile = PostgresProfile

  import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
  import storage.api.jdbcProfile.api.*

  val TestTable = new TableQuery(tag => new TestTableDef(tag))

  "AcsJdbcTypes" should {
    "be symmetric write-read" in {
      val row = TestRow(
        Timestamp.now(),
        new ContractId[Any](
          "003daad4665efc696dfb505d8ca794034a18f264cda4ebd3f0549f03c0f1f4ef42ca011220df05a9d3d5a4180cec940aeed16e1f080e6f81ee3867ff433e4a37ce2a9769c1"
        ),
        Offset.tryFromLong(64),
        "bf196cb0db2637fd30850500c50984c3b2dc23c2f89b42d9a673c5dcad3649a2",
        QualifiedName(
          "Splice.Directory",
          "DirectoryEntry",
        ),
        SynchronizerId.tryFromString(
          "domain_007c100515333195029920502::122083332c56ac1568312ccdccc7ebae45cb93005da7c4ff58c333588403efee5901"
        ),
        PartyId.tryFromProtoPrimitive("aaaa::bbbb"),
        Json.obj(
          "a" -> Json.fromInt(1),
          "b" -> Json.arr(Json.fromString("c"), Json.fromString("d")),
        ),
      )

      (for {
        _ <- storage.update(TestTable += row, "insert")
        fetched <- storage.query(TestTable.result, "fetch")
      } yield fetched.head).futureValue should be(row)
    }

    "set and get contract id arrays" in {
      val value = Array("a", "b", "c").map(new ContractId[Any](_))
      for {
        result <- storage.querySingle(
          sql"select $value".as[Array[ContractId[Any]]].headOption,
          "array",
        )
      } yield result should contain theSameElementsAs value
    }

    "set and get string arrays" in {
      val value = Array("a", "b", "c")
      for {
        result <- storage.querySingle(
          sql"select ${value.map(lengthLimited)}".as[Array[String]].headOption,
          "array",
        )
      } yield result should contain theSameElementsAs value
    }

    "set and get optional string arrays" in {
      val value = Array("a", "b", "c")
      for {
        resultSome <- storage.querySingle(
          sql"select 1, ${value.map(lengthLimited)}".as[(Int, Option[Array[String]])].headOption,
          "optional array",
        )
        resultNone <- storage.querySingle(
          sql"select 1, null".as[(Int, Option[Array[String]])].headOption,
          "optional array",
        )
      } yield {
        resultSome._2.value should contain theSameElementsAs value
        resultNone._2 shouldBe None
      }
    }

    "set and get string sequences" in {
      val value = Seq("a", "b", "c")
      for {
        result <- storage.querySingle(
          sql"select ${value.map(lengthLimited)}".as[Seq[String]].headOption,
          "array",
        )
      } yield result should contain theSameElementsAs value
    }

    "set and get int arrays" in {
      val value = Seq(1, 2, 3)
      for {
        result <- storage.querySingle(
          sql"select ${value}".as[Array[Int]].headOption,
          "int array",
        )
      } yield result.toSeq should contain theSameElementsAs value
    }

    "set and get long arrays" in {
      val value = Seq(1L, 2L, 3L, Long.MaxValue, Long.MinValue)
      for {
        result <- storage.querySingle(
          sql"select ${value}".as[Array[Long]].headOption,
          "long array",
        )
      } yield result.toSeq should contain theSameElementsAs value
    }

    "set and get empty long arrays" in {
      val value = Seq.empty[Long]
      for {
        result <- storage.querySingle(
          sql"select ${value}".as[Array[Long]].headOption,
          "empty long array",
        )
      } yield result.toSeq shouldBe empty
    }
  }

  case class TestRow(
      timestamp: Timestamp,
      contractId: ContractId[Any],
      offset: Offset,
      templateIdPackageId: String,
      templateIdQualifiedName: QualifiedName,
      synchronizerId: SynchronizerId,
      partyId: PartyId,
      json: Json,
  )

  class TestTableDef(_tableTag: Tag)
      extends storage.api.jdbcProfile.Table[TestRow](_tableTag, "jdbc_types_test_table") {
    val timestamp: Rep[Timestamp] = column[Timestamp]("timestamp")
    val contractId: Rep[ContractId[Any]] = column[ContractId[Any]]("contract_id")
    val offset: Rep[Offset] = column[Offset]("offset")
    val templateIdPackageId: Rep[String] = column[String]("template_id_package_id")
    val templateIdQualifiedName: Rep[QualifiedName] =
      column[QualifiedName]("template_id_qualified_name")
    val synchronizerId: Rep[SynchronizerId] = column[SynchronizerId]("domain_id")
    val partyId: Rep[PartyId] = column[PartyId]("party_id")
    val json: Rep[Json] = column[Json]("jayson")
    def * = (
      timestamp,
      contractId,
      offset,
      templateIdPackageId,
      templateIdQualifiedName,
      synchronizerId,
      partyId,
      json,
    ).<>(
      TestRow.tupled,
      TestRow.unapply,
    )
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    val ddl = TestTable.schema.createIfNotExists.statements
    Await.result(
      storage.update(DBIO.sequence(ddl.map(s => sqlu"#$s")), "create test table"),
      10.seconds,
    )
  }

  override def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[Unit] = {
    storage.update(
      DBIO.seq(
        sqlu"drop table if exists jdbc_types_test_table;"
      ),
      operationName = s"${this.getClass}: Drop test table",
    )
  }
}
