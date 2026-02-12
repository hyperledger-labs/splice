package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.protocol.LfContractId
import org.apache.pekko.stream.scaladsl.Keep
import org.apache.pekko.stream.testkit.scaladsl.{TestSink, TestSource}
import org.apache.pekko.util.ByteString
import org.lfdecentralizedtrust.splice.environment.ledger.api.TransactionTreeUpdate
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.scan.admin.http.ScanHttpEncodings
import org.lfdecentralizedtrust.splice.store.UpdateHistory.UpdateHistoryResponse
import org.lfdecentralizedtrust.splice.store.{StoreTestBase, TreeUpdateWithMigrationId}

import java.nio.charset.StandardCharsets
import java.time.Instant
import io.circe.syntax.*
import org.apache.pekko.stream.testkit.TestPublisher

import java.io.ByteArrayInputStream
import java.nio.file.{Files, Paths}
import scala.concurrent.duration.*
import scala.sys.process.*

class ZstdTest extends StoreTestBase with HasS3Mock {
  "ZstdGroupedWeight" should {
    "just work" in {

      val zstdChunkSize = 10000L

      val alicePartyId = mkPartyId("alice")
      val bobPartyId = mkPartyId("bob")
      val charliePartyId = mkPartyId("charlie")
      val txs = (0 to 2000).map(idx => {
        val contract = amulet(
          alicePartyId,
          BigDecimal(idx),
          0L,
          BigDecimal(0.1),
          contractId = LfContractId.assertFromString("00" + f"$idx%064x").coid,
        )
        val tx = mkCreateTx(
          1, // not used in updates v2 (TODO(#3429): double-check what the actual value in the updateHistory is. The parser in read (httpToLapiTransaction) sets this to 1, so for now we use 1 here too.)
          Seq(contract),
          Instant.ofEpochSecond(idx.toLong),
          Seq(alicePartyId, bobPartyId),
          dummyDomain,
          "",
          Instant.ofEpochSecond(idx.toLong),
          Seq(charliePartyId),
          updateId = idx.toString,
        )
        new TreeUpdateWithMigrationId(
          UpdateHistoryResponse(TransactionTreeUpdate(tx), dummyDomain),
          0L,
        )
      })

      def encode(updates: Seq[TreeUpdateWithMigrationId]) = {
        val encoded = updates.map(update =>
          ScanHttpEncodings.encodeUpdate(
            update,
            definitions.DamlValueEncoding.CompactJson,
            ScanHttpEncodings.V1,
          )
        )
        val updatesStr = encoded.map(_.asJson.noSpacesSortKeys).mkString("\n") + "\n"
        ByteString(updatesStr.getBytes(StandardCharsets.UTF_8))
      }
      def send(pub: TestPublisher.Probe[ByteString], fromIdx: Int, toIdx: Int) =
        pub.sendNext(encode(txs.slice(fromIdx, toIdx)))

      val (pub, sub) = TestSource.probe[ByteString]
        .via(ZstdGroupedWeight(zstdChunkSize))
        .toMat(TestSink.probe[ByteStringWithTermination])(Keep.both)
        .run()

      sub.request(2)
      send(pub, 0, 100)
      send(pub, 100, 200)
      sub.expectNoMessage(1.seconds)
      send(pub, 200, 700)
      val zstd1 = sub.expectNext().bytes
      send(pub, 700, 1700)
      val _ = sub.expectNext().bytes

//      val combinedData = (zstd1 ++ zstd2).toArray
      val combinedData = (zstd1).toArray
      val inputStream = new ByteArrayInputStream(combinedData)
      Files.write(Paths.get("/tmp/test.zstd"), combinedData)

      val uncompressed = ("zstd -d" #< inputStream).!!


      println(uncompressed)

      succeed
    }
  }
}
