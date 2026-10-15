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
import org.scalatest.Assertion
import org.scalatest.wordspec.AsyncWordSpec
import slick.jdbc.{GetResult, JdbcProfile, PostgresProfile, SetParameter}

import scala.concurrent.Future

class AcsJdbcTypesTest
    extends AsyncWordSpec
    with AcsJdbcTypes
    with BaseTest
    with SplicePostgresTest {

  override val profile: JdbcProfile = PostgresProfile

  import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
  import storage.api.jdbcProfile.api.*

  private def writeAndRead[T](value: T, dbType: String)(implicit
      get: GetResult[T],
      set: SetParameter[T],
  ): Future[Assertion] = {
    for {
      _ <- storage.underlying.update(
        sqlu"""
        create table jdbc_types_test_table (
          val #$dbType
        )
        """,
        "create test table",
      )
      _ <- storage.underlying.update(
        sqlu" insert into jdbc_types_test_table values (${value})",
        "insert",
      )
      fetched <- storage.underlying
        .querySingle(
          sql"select val from jdbc_types_test_table".as[T].headOption,
          "fetch",
        )
        .value
        .map(_.value)
      fetchedLiteral <- storage.underlying
        .querySingle(
          sql"select $value".as[T].headOption,
          "fetch literal",
        )
        .value
        .map(_.value)
    } yield {
      value match {
        case valueArray: Array[?] =>
          fetched.asInstanceOf[Array[?]] should contain theSameElementsAs valueArray
          fetchedLiteral.asInstanceOf[Array[?]] should contain theSameElementsAs valueArray
        case _ =>
          fetched should be(value)
          fetchedLiteral should be(value)
      }
    }
  }

  private def readNull[T](dbType: String)(implicit
      get: GetResult[Option[T]]
  ): Future[Assertion] = {
    for {
      _ <- storage.underlying.update(
        sqlu"""
        create table jdbc_types_test_table (
          val #$dbType
        )
        """,
        "create test table",
      )
      _ <- storage.underlying.update(
        sqlu" insert into jdbc_types_test_table values (null)",
        "insert",
      )
      fetched <- storage.underlying
        .querySingle(
          sql"select val from jdbc_types_test_table".as[Option[T]].headOption,
          "fetch",
        )
        .value
        .map(_.value)
      fetchedLiteral <- storage.underlying
        .querySingle(
          sql"select null".as[Option[T]].headOption,
          "fetch literal",
        )
        .value
        .map(_.value)
    } yield {
      fetched shouldBe None
      fetchedLiteral shouldBe None
    }
  }

  "AcsJdbcTypes" should {
    "String" in {
      val value = "abc"
      writeAndRead(value, "text not null")
    }

    "Option.empty[String]" in {
      val value = Option.empty[String]
      writeAndRead(value, "text")
    }

    "Timestamp" in {
      val value = Timestamp.now()
      writeAndRead(value, "bigint not null")
    }

    "ContractId" in {
      val value = new ContractId[Any](
        "003daad4665efc696dfb505d8ca794034a18f264cda4ebd3f0549f03c0f1f4ef42ca011220df05a9d3d5a4180cec940aeed16e1f080e6f81ee3867ff433e4a37ce2a9769c1"
      )
      writeAndRead(value, "text not null")
    }

    "SynchronizerId" in {
      val value = SynchronizerId.tryFromString(
        "domain_007c100515333195029920502::122083332c56ac1568312ccdccc7ebae45cb93005da7c4ff58c333588403efee5901"
      )
      writeAndRead(value, "text not null")
    }

    "Offset" in {
      val value = Offset.tryFromLong(64)
      writeAndRead(value, "text not null")
    }

    "QualifiedName" in {
      val value = QualifiedName(
        "Splice.Directory",
        "DirectoryEntry",
      )
      writeAndRead(value, "text not null")
    }

    "PartyId" in {
      val value = PartyId.tryFromProtoPrimitive("aaaa::bbbb")
      writeAndRead(value, "text not null")
    }

    "Json.obj" in {
      val value = Json.obj(
        "a" -> Json.fromInt(1),
        "b" -> Json.arr(Json.fromString("c"), Json.fromString("d")),
      )
      writeAndRead(value, "json")
    }

    "Array[ContractId]" in {
      val value = Array("a", "b", "c").map(new ContractId[Any](_))
      writeAndRead(value, "text[] not null")
    }

    "Seq[String]" in {
      val value = Seq[String]("a", "b", "c")
      writeAndRead(value, "text[] not null")
    }

    "Seq.empty[String]" in {
      val value = Seq.empty[String]
      writeAndRead(value, "text[] not null")
    }

    "Array[String]" in {
      val value = Array[String]("a", "b", "c")
      writeAndRead(value, "text[] not null")
    }

    "Option[Array[String]]" in {
      readNull[Array[String]]("text[]")
    }

    "Seq[Long]" in {
      val value = Seq[Long](1, 2, 3)
      writeAndRead(value, "bigint[] not null")
    }

    "Seq.empty[Long]" in {
      val value = Seq.empty[Long]
      writeAndRead(value, "bigint[] not null")
    }

    "Array[Long]" in {
      val value = Array[Long](1, 2, 3)
      writeAndRead(value, "bigint[] not null")
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
      intArray: Seq[Int],
      stringArray: Seq[String],
  )

  override def beforeEach(): Unit = {
    super.beforeEach()
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
