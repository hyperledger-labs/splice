package com.daml.network.store.tables

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.lf.data.Bytes
import com.daml.lf.data.Time.Timestamp
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.admin.api.client.data.TemplateId
import com.digitalasset.canton.ledger.offset.Offset
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.store.db.PostgresTest
import com.digitalasset.canton.topology.{DomainId, PartyId}
import io.circe.Json
import org.scalatest.wordspec.AsyncWordSpec
import slick.jdbc.{JdbcProfile, PostgresProfile}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class AcsJdbcTypesTest extends AsyncWordSpec with AcsJdbcTypes with BaseTest with PostgresTest {

  override val profile: JdbcProfile = PostgresProfile

  import storage.api.jdbcProfile.api.*

  val TestTable = new TableQuery(tag => new TestTableDef(tag))

  "AcsJdbcTypes" should {
    "be symmetric write-read" in {
      val row = TestRow(
        Timestamp.now(),
        new ContractId[Any](
          "003daad4665efc696dfb505d8ca794034a18f264cda4ebd3f0549f03c0f1f4ef42ca011220df05a9d3d5a4180cec940aeed16e1f080e6f81ee3867ff433e4a37ce2a9769c1"
        ),
        Offset(Bytes.assertFromString("ff00aa")),
        TemplateId(
          "bf196cb0db2637fd30850500c50984c3b2dc23c2f89b42d9a673c5dcad3649a2",
          "CN.Directory",
          "DirectoryEntry",
        ),
        DomainId.tryFromString(
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
  }

  case class TestRow(
      timestamp: Timestamp,
      contractId: ContractId[Any],
      offset: Offset,
      templateId: TemplateId,
      domainId: DomainId,
      partyId: PartyId,
      json: Json,
  )

  class TestTableDef(_tableTag: Tag)
      extends storage.api.jdbcProfile.Table[TestRow](_tableTag, "jdbc_types_test_table") {
    val timestamp: Rep[Timestamp] = column[Timestamp]("timestamp")
    val contractId: Rep[ContractId[Any]] = column[ContractId[Any]]("contract_id")
    val offset: Rep[Offset] = column[Offset]("offset")
    val templateId: Rep[TemplateId] = column[TemplateId]("template_id")
    val domainId: Rep[DomainId] = column[DomainId]("domain_id")
    val partyId: Rep[PartyId] = column[PartyId]("party_id")
    val json: Rep[Json] = column[Json]("jayson")
    def * = (timestamp, contractId, offset, templateId, domainId, partyId, json).<>(
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

  override def cleanDb(storage: DbStorage): Future[Unit] = {
    storage.update(
      DBIO.seq(
        sqlu"drop table if exists jdbc_types_test_table;"
      ),
      operationName = s"${this.getClass}: Drop test table",
    )
  }
}
