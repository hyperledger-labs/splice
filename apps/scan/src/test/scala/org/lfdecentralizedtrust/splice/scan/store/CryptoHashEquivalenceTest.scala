package org.lfdecentralizedtrust.splice.scan.store

import com.daml.ledger.javaapi.data.{
  CreateAndExerciseCommand,
  DamlList,
  DamlRecord,
  Identifier,
  Party,
  Text,
  Value,
}
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.TestEngine
import com.digitalasset.daml.lf.data.Ref
import com.digitalasset.daml.lf.transaction.Node
import com.digitalasset.daml.lf.value.{Value as LfValue}
import org.lfdecentralizedtrust.splice.store.StoreTestBase
import org.lfdecentralizedtrust.splice.store.db.SplicePostgresTest
import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import java.security.MessageDigest
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

/** Three-way equivalence test: Daml == SQL == Scala oracle.
  *
  * Each test case is a `HashOp` describing what to hash. The SQL expression,
  * Daml choice arguments, and Scala expected value are all derived from it —
  * no duplication, no chance of mismatch between the test inputs.
  *
  * All three must agree.
  */
class CryptoHashEquivalenceTest
    extends StoreTestBase
    with HasExecutionContext
    with SplicePostgresTest {

  import CryptoHashEquivalenceTest.*

  "crypto hash three-way equivalence" should {
    allTestCases.foreach { tc =>
      tc.description in {
        val damlResult = exerciseDaml(tc.op)
        for {
          sqlResult <- runSqlText(sql"""select #${toSqlExpr(tc.op)}""".as[String].head)
        } yield {
          val scalaResult = toScalaExpected(tc.op)
          withClue(s"Daml vs Scala for ${tc.description}:") {
            damlResult shouldBe scalaResult
          }
          withClue(s"SQL vs Scala for ${tc.description}:") {
            sqlResult shouldBe scalaResult
          }
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

object CryptoHashEquivalenceTest {

  // -- Test cases ---------------------------------------------------------------

  case class TestCase(description: String, op: HashOp)

  private val hAlice = ScalaOracle.hashText("alice::provider")
  private val h10 = ScalaOracle.hashText("10.0")
  private val hOnly = ScalaOracle.hashText("only")
  private val h1 = ScalaOracle.hashText("1")
  private val hx = ScalaOracle.hashText("x")

  val allTestCases: Seq[TestCase] = {
    val maAlice = ScalaOracle.hashMintingAllowance("alice::provider", "10.0000000000")
    val maBob = ScalaOracle.hashMintingAllowance("bob::provider", "0")
    val leaf1 = ScalaOracle.hashBatchOfMintingAllowances(
      Seq(ScalaOracle.hashMintingAllowance("alice::provider", "5.0"))
    )
    val leaf2 = ScalaOracle.hashBatchOfMintingAllowances(
      Seq(ScalaOracle.hashMintingAllowance("bob::provider", "3.0"))
    )

    Seq(
      TestCase("hashText('hello')", HashText("hello")),
      TestCase("hashText on empty string", HashText("")),
      TestCase("hashList with two elements", HashList(Seq(hAlice, h10))),
      TestCase("hashList on empty array", HashList(Seq.empty)),
      TestCase("hashList on single element", HashList(Seq(hOnly))),
      TestCase("hashVariant with tag and one field", HashVariant("TestTag", Seq(hAlice))),
      TestCase(
        "hash_minting_allowance(alice, 10.0)",
        HashMintingAllowance("alice::provider", "10.0000000000"),
      ),
      TestCase("hash_minting_allowance(bob, 0)", HashMintingAllowance("bob::provider", "0")),
      TestCase(
        "hash_batch_of_minting_allowances with two",
        HashBatchOfMintingAllowances(Seq(maAlice, maBob)),
      ),
      TestCase("hash_batch_of_batches with two leaves", HashBatchOfBatches(Seq(leaf1, leaf2))),
      // These inputs match the Daml CryptoHash unit test suite (RecV1, VarV1.V1)
      TestCase("hashRecord [hash 1, hash 'x']", HashList(Seq(h1, hx))),
      TestCase("hashVariant 'V1' [hash 1, hash 'x']", HashVariant("V1", Seq(h1, hx))),
    )
  }

  // -- HashOp: structured description of what to hash ---------------------------

  sealed trait HashOp
  case class HashText(value: String) extends HashOp
  case class HashList(elems: Seq[String]) extends HashOp
  case class HashVariant(tag: String, fields: Seq[String]) extends HashOp
  case class HashMintingAllowance(provider: String, amount: String) extends HashOp
  case class HashBatchOfMintingAllowances(allowanceHashes: Seq[String]) extends HashOp
  case class HashBatchOfBatches(childHashes: Seq[String]) extends HashOp

  // -- Derive SQL expression from HashOp ----------------------------------------

  def toSqlExpr(op: HashOp): String = op match {
    case HashText(v) =>
      s"daml_crypto_hash_text('${escapeSql(v)}')"
    case HashList(elems) =>
      val arr = elems.map(e => s"'$e'").mkString(", ")
      s"daml_crypto_hash_list(ARRAY[$arr]::text[])"
    case HashVariant(tag, fields) =>
      val arr = fields.map(f => s"'$f'").mkString(", ")
      s"daml_crypto_hash_variant('${escapeSql(tag)}', ARRAY[$arr]::text[])"
    case HashMintingAllowance(provider, amount) =>
      s"hash_minting_allowance('${escapeSql(provider)}', '${escapeSql(amount)}')"
    case HashBatchOfMintingAllowances(hashes) =>
      val arr = hashes.map(h => s"'$h'").mkString(", ")
      s"hash_batch_of_minting_allowances(ARRAY[$arr]::text[])"
    case HashBatchOfBatches(hashes) =>
      val arr = hashes.map(h => s"'$h'").mkString(", ")
      s"hash_batch_of_batches(ARRAY[$arr]::text[])"
  }

  private def escapeSql(s: String): String = s.replace("'", "''")

  // -- Derive Daml choice + arg from HashOp -------------------------------------

  private def toDamlChoiceAndArg(op: HashOp): (String, Value) = op match {
    case HashText(v) =>
      ("CryptoHashProxy_HashText", record("input" -> text(v)))
    case HashList(elems) =>
      ("CryptoHashProxy_HashList", record("elems" -> textList(elems)))
    case HashVariant(tag, fields) =>
      ("CryptoHashProxy_HashVariant", record("tag" -> text(tag), "fields" -> textList(fields)))
    case HashMintingAllowance(provider, amount) =>
      (
        "CryptoHashProxy_HashMintingAllowance",
        record("provider" -> text(provider), "amount" -> text(amount)),
      )
    case HashBatchOfMintingAllowances(hashes) =>
      (
        "CryptoHashProxy_HashBatchOfMintingAllowances",
        record("allowanceHashes" -> textList(hashes)),
      )
    case HashBatchOfBatches(hashes) =>
      ("CryptoHashProxy_HashBatchOfBatches", record("childHashes" -> textList(hashes)))
  }

  private def text(s: String): Value = new Text(s)
  private def textList(ss: Seq[String]): Value = DamlList.of(ss.map(s => new Text(s): Value).asJava)
  private def record(fields: (String, Value)*): Value =
    new DamlRecord(fields.map { case (k, v) => new DamlRecord.Field(k, v) }.asJava)

  // -- Derive Scala expected from HashOp ----------------------------------------

  def toScalaExpected(op: HashOp): String = op match {
    case HashText(v) => ScalaOracle.hashText(v)
    case HashList(elems) => ScalaOracle.hashList(elems)
    case HashVariant(tag, fields) => ScalaOracle.hashVariant(tag, fields)
    case HashMintingAllowance(p, a) => ScalaOracle.hashMintingAllowance(p, a)
    case HashBatchOfMintingAllowances(h) => ScalaOracle.hashBatchOfMintingAllowances(h)
    case HashBatchOfBatches(h) => ScalaOracle.hashBatchOfBatches(h)
  }

  // -- Scala oracle -------------------------------------------------------------

  private[store] object ScalaOracle {
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

  // -- Daml TestEngine ----------------------------------------------------------

  private val darPath = "daml/splice-amulet-test/.daml/dist/splice-amulet-test-current.dar"
  private val testEngine = new TestEngine(packagePaths = Seq(darPath))

  private val packageId: String = {
    val moduleName = Ref.ModuleName.assertFromString("Splice.Testing.CryptoHashProxy")
    testEngine.packageStore.packages
      .collectFirst {
        case (pkgId, (_, pkg)) if pkg.modules.contains(moduleName) => pkgId
      }
      .getOrElse(sys.error(s"Could not find package containing $moduleName"))
      .toString
  }

  private val templateId = new Identifier(
    packageId,
    "Splice.Testing.CryptoHashProxy",
    "CryptoHashProxy",
  )

  private val party = "alice"
  private val createArgs = new DamlRecord(
    java.util.List.of(new DamlRecord.Field("owner", new Party(party)))
  )

  private def exerciseDaml(op: HashOp): String = {
    val (choiceName, choiceArg) = toDamlChoiceAndArg(op)
    val cmd = new CreateAndExerciseCommand(templateId, createArgs, choiceName, choiceArg)
    val (tx, _) = testEngine.submitAndConsume(cmd, party)

    tx.roots.toSeq
      .map(nid => tx.nodes(nid))
      .collectFirst { case ex: Node.Exercise => ex }
      .getOrElse(sys.error("No exercise node found in transaction"))
      .exerciseResult
      .collect { case LfValue.ValueText(text) => text }
      .getOrElse(sys.error("Exercise result is not Text"))
  }
}
