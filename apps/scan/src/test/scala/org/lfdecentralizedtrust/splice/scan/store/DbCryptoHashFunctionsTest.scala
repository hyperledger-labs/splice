package org.lfdecentralizedtrust.splice.scan.store

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import org.lfdecentralizedtrust.splice.store.StoreTestBase
import org.lfdecentralizedtrust.splice.store.db.SplicePostgresTest
import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import java.security.MessageDigest
import scala.concurrent.Future

/** Tests that the plpgsql hash functions produce output identical to
  * the Daml CryptoHash module (Splice.Amulet.CryptoHash).
  */
class DbCryptoHashFunctionsTest
    extends StoreTestBase
    with HasExecutionContext
    with SplicePostgresTest {

  import DbCryptoHashFunctionsTest.DamlCryptoHash

  private case class HashTestCase(
      description: String,
      sqlExpr: String,
      expected: String,
  )

  // -- Primitive function tests -----------------------------------------------

  private val primitiveTestCases = {
    val hAlice = DamlCryptoHash.hashText("alice::provider")
    val h10 = DamlCryptoHash.hashText("10.0")
    val hOnly = DamlCryptoHash.hashText("only")

    Seq(
      HashTestCase(
        "crypto_hash_text('hello')",
        "crypto_hash_text('hello')",
        DamlCryptoHash.hashText("hello"),
      ),
      HashTestCase(
        "crypto_hash_text on empty string",
        "crypto_hash_text('')",
        DamlCryptoHash.hashText(""),
      ),
      HashTestCase(
        "crypto_hash_list with two elements",
        s"""crypto_hash_list(ARRAY[
            crypto_hash_text('alice::provider'),
            crypto_hash_text('10.0')
          ]::text[])""",
        DamlCryptoHash.hashList(Seq(hAlice, h10)),
      ),
      HashTestCase(
        "crypto_hash_list on empty array",
        "crypto_hash_list(ARRAY[]::text[])",
        DamlCryptoHash.hashList(Seq.empty),
      ),
      HashTestCase(
        "crypto_hash_list on single element",
        "crypto_hash_list(ARRAY[crypto_hash_text('only')]::text[])",
        DamlCryptoHash.hashList(Seq(hOnly)),
      ),
      HashTestCase(
        "crypto_hash_variant with tag and one field",
        s"""crypto_hash_variant('TestTag', ARRAY[
            crypto_hash_text('alice::provider')
          ]::text[])""",
        DamlCryptoHash.hashVariant("TestTag", Seq(hAlice)),
      ),
    )
  }

  // -- Golden values from Daml CryptoHash unit tests --------------------------
  // Source: daml/splice-amulet-test/daml/Splice/Scripts/UnitTests/Amulet/CryptoHash.daml
  // These are hardcoded expected hashes from the Daml test suite, not computed
  // by our Scala oracle. They anchor the hash scheme to the canonical Daml output.

  private val goldenTestCases = {
    // Daml: RecV1{a=1, b="x"} => hashRecord [hash 1, hash "x"]
    // hash Int = hashText . show, hash Text = hashText
    val h1 = DamlCryptoHash.hashText("1")
    val hx = DamlCryptoHash.hashText("x")

    Seq(
      HashTestCase(
        "golden: hashRecord [hash 1, hash 'x'] (Daml RecV1)",
        s"crypto_hash_list(ARRAY['$h1', '$hx']::text[])",
        "e2878cf11d8a10aa17f359e1f61f711756fdbbc256bf541baec14c21b6888f6e",
      ),
      HashTestCase(
        "golden: hashVariant 'V1' [hash 1, hash 'x'] (Daml VarV1.V1)",
        s"crypto_hash_variant('V1', ARRAY['$h1', '$hx']::text[])",
        "ca314e0bdf0fc327940a89334ff6df58f234b395d36328af1a0cce2339227e5c",
      ),
    )
  }

  // -- Domain-specific function tests -----------------------------------------

  private val domainTestCases = {
    val maAlice = DamlCryptoHash.hashMintingAllowance("alice::provider", "10.0000000000")
    val maBob = DamlCryptoHash.hashMintingAllowance("bob::provider", "0")

    val leaf1 = DamlCryptoHash.hashBatchOfMintingAllowances(
      Seq(DamlCryptoHash.hashMintingAllowance("alice::provider", "5.0"))
    )
    val leaf2 = DamlCryptoHash.hashBatchOfMintingAllowances(
      Seq(DamlCryptoHash.hashMintingAllowance("bob::provider", "3.0"))
    )

    Seq(
      HashTestCase(
        "hash_minting_allowance(alice, 10.0)",
        "hash_minting_allowance('alice::provider', '10.0000000000')",
        maAlice,
      ),
      HashTestCase(
        "hash_minting_allowance(bob, 0)",
        "hash_minting_allowance('bob::provider', '0')",
        maBob,
      ),
      HashTestCase(
        "hash_batch_of_minting_allowances with two allowances",
        s"""hash_batch_of_minting_allowances(ARRAY[
            hash_minting_allowance('alice::provider', '10.0000000000'),
            hash_minting_allowance('bob::provider', '0')
          ]::text[])""",
        DamlCryptoHash.hashBatchOfMintingAllowances(Seq(maAlice, maBob)),
      ),
      HashTestCase(
        "hash_batch_of_batches with two leaf batches",
        s"""hash_batch_of_batches(ARRAY[
            hash_batch_of_minting_allowances(ARRAY[
              hash_minting_allowance('alice::provider', '5.0')
            ]::text[]),
            hash_batch_of_minting_allowances(ARRAY[
              hash_minting_allowance('bob::provider', '3.0')
            ]::text[])
          ]::text[])""",
        DamlCryptoHash.hashBatchOfBatches(Seq(leaf1, leaf2)),
      ),
    )
  }

  "plpgsql crypto hash functions" should {
    (primitiveTestCases ++ goldenTestCases ++ domainTestCases).foreach { tc =>
      tc.description in {
        for {
          result <- runSqlText(
            sql"""select #${tc.sqlExpr}""".as[String].head
          )
        } yield {
          result shouldBe tc.expected
        }
      }
    }
  }

  private def runSqlText(
      action: slick.dbio.DBIOAction[String, slick.dbio.NoStream, slick.dbio.Effect.Read]
  ): Future[String] =
    futureUnlessShutdownToFuture(
      storage.underlying.query(action, "test.runSqlText")
    )

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] =
    resetAllAppTables(storage)
}

object DbCryptoHashFunctionsTest {

  /** Scala reimplementation of Daml's CryptoHash module (Splice.Amulet.CryptoHash).
    * Used as the test oracle to verify plpgsql hash functions produce identical output.
    */
  private[store] object DamlCryptoHash {
    def hashText(s: String): String = sha256Hex(s)

    def hashList(elems: Seq[String]): String = {
      val parts = elems.size.toString +: elems
      sha256Hex(parts.mkString("|"))
    }

    def hashVariant(tag: String, fieldHashes: Seq[String]): String = {
      val parts = tag +: fieldHashes.size.toString +: fieldHashes
      sha256Hex(parts.mkString("|"))
    }

    def hashMintingAllowance(provider: String, amount: String): String =
      hashList(Seq(hashText(provider), hashText(amount)))

    def hashBatchOfMintingAllowances(allowanceHashes: Seq[String]): String =
      hashVariant("BatchOfMintingAllowances", Seq(hashList(allowanceHashes)))

    def hashBatchOfBatches(childHashes: Seq[String]): String =
      hashVariant("BatchOfBatches", Seq(hashList(childHashes)))

    private def sha256Hex(s: String): String = {
      val digest = MessageDigest.getInstance("SHA-256")
      digest.digest(s.getBytes("UTF-8")).map("%02x".format(_)).mkString
    }
  }
}
