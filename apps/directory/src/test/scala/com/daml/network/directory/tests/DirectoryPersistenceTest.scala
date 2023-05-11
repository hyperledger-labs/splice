package com.daml.network.directory.tests

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.store.db.PostgresTest
import org.scalatest.wordspec.AsyncWordSpec
import slick.jdbc.PostgresProfile.api.*

import scala.concurrent.Future

// This is just a placeholder until Directory has, and uses, persistent storage
class DirectoryPersistenceTest extends AsyncWordSpec with BaseTest with PostgresTest {

  "Directory" should {

    "use storage" in {
      storage.query(sql"SELECT 1;".as[Int], "select1").futureValue should be(Seq(1))
    }

  }

  override protected def cleanDb(x: DbStorage): Future[?] = Future.unit
}
