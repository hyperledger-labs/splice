package com.daml.network.unit.directory

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.lf.data.Time.Timestamp
import com.daml.network.directory.store.db.DirectoryTables
import com.daml.network.store.db.{AcsJdbcTypes, CNPostgresTest}
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.admin.api.client.data.TemplateId
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.PartyId
import io.circe.Json
import org.scalatest.wordspec.AsyncWordSpec
import slick.jdbc.JdbcProfile

import java.time.Duration
import scala.concurrent.Future

class DirectoryPersistenceTest
    extends AsyncWordSpec
    with AcsJdbcTypes
    with BaseTest
    with CNPostgresTest {

  override lazy val profile: JdbcProfile = storage.api.jdbcProfile
  import storage.api.jdbcProfile.api.*

  val AcsTable = DirectoryTables.DirectoryAcsStore
  val StoreDescriptorsTable = DirectoryTables.StoreDescriptors

  "Directory" should {

    "use storage" in {
      val descRow = DirectoryTables.StoreDescriptorsRow(
        id = 0,
        descriptor = Json.obj(
          "name" -> Json.fromString("DirectoryPersistenceTest")
        ),
        lastIngestedOffset = None,
      )
      for {
        _ <- storage.queryAndUpdate(
          insertRowIfNotExists(StoreDescriptorsTable)(
            e => e.descriptor === descRow.descriptor,
            descRow,
          ),
          "insert1",
        )
        fetchedDescRow <- storage.query(StoreDescriptorsTable.result, "fetch1").map(_.loneElement)

        acsRow = DirectoryTables.DirectoryAcsStoreRow(
          storeId = fetchedDescRow.id,
          eventNumber = 0,
          contractId = new ContractId(
            "003daad4665efc696dfb505d8ca794034a18f264cda4ebd3f0549f03c0f1f4ef42ca011220df05a9d3d5a4180cec940aeed16e1f080e6f81ee3867ff433e4a37ce2a9769c1"
          ),
          templateId = TemplateId(
            "bf196cb0db2637fd30850500c50984c3b2dc23c2f89b42d9a673c5dcad3649a2",
            "CN.Directory",
            "DirectoryEntry",
          ),
          createArguments = Json.obj(
            "a" -> Json.fromInt(1),
            "b" -> Json.arr(Json.fromString("c"), Json.fromString("d")),
          ),
          contractMetadataCreatedAt = Timestamp.now(),
          contractMetadataContractKeyHash = Some("0123456789"),
          contractMetadataDriverInternal = Array(255.toByte, 0.toByte, 255.toByte, 0.toByte),
          contractExpiresAt = Some(Timestamp.Epoch.add(Duration.ofDays(2))),
          directoryInstallUser = Some(PartyId.tryFromProtoPrimitive("alice::test")),
          directoryEntryName = Some("alice.cns"),
          directoryEntryOwner = Some(PartyId.tryFromProtoPrimitive("alice_owner::test")),
          subscriptionContextContractId = None,
          subscriptionNextPaymentDueAt = Some(Timestamp.Epoch.add(Duration.ofDays(3))),
        )
        _ <- storage.queryAndUpdate(
          insertRowIfNotExists(AcsTable)(
            e => e.contractId === acsRow.contractId,
            acsRow,
          ),
          "insert2",
        )
        fetchedAcsRow <- storage.query(AcsTable.result, "fetch2").map(_.loneElement)
      } yield {
        fetchedDescRow should be(descRow.copy(id = fetchedDescRow.id))

        fetchedAcsRow should be(
          acsRow.copy(
            eventNumber = fetchedAcsRow.eventNumber,
            contractMetadataDriverInternal = fetchedAcsRow.contractMetadataDriverInternal,
          )
        )
      }
    }

  }

  override protected def cleanDb(x: DbStorage): Future[?] = {
    for {
      _ <- resetAllCnAppTables(x)
    } yield ()
  }
}
