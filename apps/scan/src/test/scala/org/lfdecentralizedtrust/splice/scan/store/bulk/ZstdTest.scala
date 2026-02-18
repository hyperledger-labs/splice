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
import org.lfdecentralizedtrust.splice.http.v0.definitions.UpdateHistoryItem

import java.io.ByteArrayInputStream
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
        updates.map(update =>
          ScanHttpEncodings.encodeUpdate(
            update,
            definitions.DamlValueEncoding.CompactJson,
            ScanHttpEncodings.V1,
          )
        )
      }
      def send(pub: TestPublisher.Probe[ByteString], fromIdx: Int, toIdx: Int) = {
        val encoded = encode(txs.slice(fromIdx, toIdx))
        val updatesStr = encoded.map(_.asJson.noSpacesSortKeys).mkString("\n") + "\n"
        pub.sendNext(ByteString(updatesStr.getBytes(StandardCharsets.UTF_8)))
      }

      val (pub, sub) = TestSource
        .probe[ByteString]
        .via(ZstdGroupedWeight(zstdChunkSize))
        .toMat(TestSink.probe[ByteStringWithTermination])(Keep.both)
        .run()

      sub.request(3)
      clue("Two small inputs, not enough for zstd to close an element") {
        send(pub, 0, 100)
        send(pub, 100, 200)
        sub.expectNoMessage(1.seconds)
      }
      val zstd1 = clue("More input, now first element will be closed") {
        send(pub, 200, 700)
        sub.expectNext().bytes
      }
      val zstd2 = clue("Another large input, enough to close the second element") {
        send(pub, 700, 1700)
        sub.expectNext().bytes
      }
      val zstd3 =
        clue("A small input, followed by completing the source, should emit another element") {
          send(pub, 1700, 1800)
          pub.sendComplete()
          sub.expectNext().bytes
        }

      val allEncodedTxs = encode(txs)
      def uncompressAndCompare(compressed: ByteString, fromIdx: Int, toIdx: Int) = {
        val inputStream = new ByteArrayInputStream(compressed.toArray)
        val uncompressed = ("zstd -d" #< inputStream).!!
        val decoded =
          uncompressed.split("\n").map(io.circe.parser.decode[UpdateHistoryItem](_)).map(_.value)
        decoded should contain theSameElementsInOrderAs allEncodedTxs.slice(fromIdx, toIdx)
      }

      clue("Each output element is a valid zstd object, and so is their concatenation") {
        uncompressAndCompare(zstd1, 0, 700)
        uncompressAndCompare(zstd2, 700, 1700)
        uncompressAndCompare(zstd3, 1700, 1800)
        uncompressAndCompare(zstd1 ++ zstd2 ++ zstd3, 0, 1800)
      }
    }

  }
}
