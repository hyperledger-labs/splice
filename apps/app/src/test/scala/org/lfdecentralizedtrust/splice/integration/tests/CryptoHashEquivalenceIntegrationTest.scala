package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.javaapi.data.{
  CreatedEvent,
  CreateCommand,
  DamlList,
  DamlRecord,
  ExerciseCommand,
  ExercisedEvent,
  Identifier,
  Party,
  Text,
  Value,
}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.daml.lf.data.Ref
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.{DarUtil, WalletTestUtil}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import java.io.File
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*

/** Three-way integration test: Daml (real participant) == SQL (scan Postgres) == Scala oracle.
  *
  * This complements the store-level CryptoHashEquivalenceTest (which uses
  * Canton's in-memory TestEngine) by validating against a real ledger and
  * the actual scan database.
  *
  * Uses a shared environment so the dar is uploaded and proxy created once,
  * then each hash function gets its own test.
  */
@org.lfdecentralizedtrust.splice.util.scalatesttags.NoDamlCompatibilityCheck
class CryptoHashEquivalenceIntegrationTest extends IntegrationTest with WalletTestUtil {

  import CryptoHashEquivalenceIntegrationTest.*

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withAdditionalSetup { implicit env =>
        sv1Backend.participantClient.upload_dar_unless_exists(darPath)
      }
      .withManualStart

  "Three-way CryptoHash equivalence (Daml, SQL, Scala)" should {
    "set up CryptoHashProxy" in { implicit env =>
      startAllSync(
        sv1Backend,
        sv1ScanBackend,
      )
      svParty = sv1Backend.getDsoInfo().svParty

      clue("Create CryptoHashProxy") {
        val createArgs = new DamlRecord(
          java.util.List.of(
            new DamlRecord.Field("owner", new Party(svParty.toProtoPrimitive))
          )
        )
        val createTx =
          sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
            .submitJava(
              actAs = Seq(svParty),
              commands = Seq(new CreateCommand(templateId, createArgs)),
            )
        proxyContractId = createTx.getEvents.asScala
          .collectFirst { case ce: CreatedEvent => ce.getContractId }
          .getOrElse(fail("No CreatedEvent from CryptoHashProxy create"))
      }
      scanDb = sv1ScanBackend.appState.storage match {
        case db: DbStorage => db
        case s => fail(s"non-DB storage configured: ${s.getClass}")
      }
    }

    allTestCases.foreach { tc =>
      tc.description in { implicit env =>
        val scalaHash = toScalaExpected(tc.op)

        clue(s"Daml vs Scala for ${tc.description}") {
          val (choiceName, choiceArg) = toDamlChoiceAndArg(tc.op)
          val cmd =
            new ExerciseCommand(templateId, proxyContractId, choiceName, choiceArg)
          val tx = sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
            .submitJava(
              actAs = Seq(svParty),
              commands = Seq(cmd),
            )
          val damlHash = tx.getEventsById.values.asScala
            .collectFirst { case ex: ExercisedEvent =>
              ex.getExerciseResult match {
                case t: Text => t.getValue
                case other =>
                  fail(s"Expected Text result but got ${other.getClass.getSimpleName}")
              }
            }
            .getOrElse(fail(s"No ExercisedEvent in transaction"))

          damlHash shouldBe scalaHash
        }

        clue(s"SQL vs Scala for ${tc.description}") {
          val sqlHash = scanDb
            .query(
              sql"""select #${toSqlExpr(tc.op)}""".as[String].head,
              s"test.sqlHash.${tc.description}",
            )
            .futureValueUS

          sqlHash shouldBe scalaHash
        }
      }
    }
  }

  private var svParty: com.digitalasset.canton.topology.PartyId = _
  private var proxyContractId: String = _
  private var scanDb: DbStorage = _
}

object CryptoHashEquivalenceIntegrationTest {

  // -- Package discovery ------------------------------------------------------

  private val darPath = "daml/splice-amulet-test/.daml/dist/splice-amulet-test-current.dar"
  private val moduleName = "Splice.Testing.CryptoHashProxy"

  private val packageId: String = {
    val dar = DarUtil.readDar(new File(darPath))
    val modName = Ref.ModuleName.assertFromString(moduleName)
    dar.all
      .collectFirst { case (pkgId, pkg) if pkg.modules.contains(modName) => pkgId }
      .getOrElse(sys.error(s"Could not find package containing $moduleName"))
      .toString
  }

  private val templateId = new Identifier(packageId, moduleName, "CryptoHashProxy")

  // -- Scala oracle (mirrors Daml CryptoHash module) --------------------------

  private def sha256Hex(s: String): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.digest(s.getBytes("UTF-8")).map("%02x".format(_)).mkString
  }

  private def hashText(s: String): String = sha256Hex(s)

  private def hashList(elems: Seq[String]): String = {
    val parts = elems.size.toString +: elems
    sha256Hex(parts.mkString("|"))
  }

  private def hashVariant(tag: String, fieldHashes: Seq[String]): String = {
    val parts = tag +: fieldHashes.size.toString +: fieldHashes
    sha256Hex(parts.mkString("|"))
  }

  private def hashMintingAllowance(provider: String, amount: String): String =
    hashList(Seq(hashText(provider), hashText(amount)))

  private def hashBatchOfMintingAllowances(allowanceHashes: Seq[String]): String =
    hashVariant("BatchOfMintingAllowances", Seq(hashList(allowanceHashes)))

  private def hashBatchOfBatches(childHashes: Seq[String]): String =
    hashVariant("BatchOfBatches", Seq(hashList(childHashes)))

  // -- HashOp: structured description of what to hash -------------------------

  sealed trait HashOp
  case class HashText(value: String) extends HashOp
  case class HashList(elems: Seq[String]) extends HashOp
  case class HashVariant(tag: String, fields: Seq[String]) extends HashOp
  case class HashMintingAllowance(provider: String, amount: String) extends HashOp
  case class HashBatchOfMintingAllowances(allowanceHashes: Seq[String]) extends HashOp
  case class HashBatchOfBatches(childHashes: Seq[String]) extends HashOp

  // -- Derive Daml choice + arg from HashOp ----------------------------------

  private def text(s: String): Value = new Text(s)
  private def textList(ss: Seq[String]): Value =
    DamlList.of(ss.map(s => new Text(s): Value).asJava)
  private def record(fields: (String, Value)*): Value =
    new DamlRecord(fields.map { case (k, v) => new DamlRecord.Field(k, v) }.asJava)

  def toDamlChoiceAndArg(op: HashOp): (String, Value) = op match {
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

  // -- Derive SQL expression from HashOp -------------------------------------

  private def escapeSql(s: String): String = s.replace("'", "''")
  private def sqlArray(elems: Seq[String]): String =
    s"ARRAY[${elems.map(e => s"'$e'").mkString(", ")}]::text[]"

  def toSqlExpr(op: HashOp): String = op match {
    case HashText(v) =>
      s"daml_crypto_hash_text('${escapeSql(v)}')"
    case HashList(elems) =>
      s"daml_crypto_hash_list(${sqlArray(elems)})"
    case HashVariant(tag, fields) =>
      s"daml_crypto_hash_variant('${escapeSql(tag)}', ${sqlArray(fields)})"
    case HashMintingAllowance(provider, amount) =>
      s"hash_minting_allowance('${escapeSql(provider)}', '${escapeSql(amount)}')"
    case HashBatchOfMintingAllowances(hashes) =>
      s"hash_batch_of_minting_allowances(${sqlArray(hashes)})"
    case HashBatchOfBatches(hashes) =>
      s"hash_batch_of_batches(${sqlArray(hashes)})"
  }

  // -- Derive Scala expected from HashOp -------------------------------------

  def toScalaExpected(op: HashOp): String = op match {
    case HashText(v) => hashText(v)
    case HashList(elems) => hashList(elems)
    case HashVariant(tag, fields) => hashVariant(tag, fields)
    case HashMintingAllowance(p, a) => hashMintingAllowance(p, a)
    case HashBatchOfMintingAllowances(h) => hashBatchOfMintingAllowances(h)
    case HashBatchOfBatches(h) => hashBatchOfBatches(h)
  }

  // -- Test cases ------------------------------------------------------------

  case class TestCase(description: String, op: HashOp)

  private val hAlice = hashText("alice::provider")
  private val h10 = hashText("10.0")
  private val hOnly = hashText("only")
  private val h1 = hashText("1")
  private val hx = hashText("x")

  val allTestCases: Seq[TestCase] = {
    val maAlice = hashMintingAllowance("alice::provider", "10.0000000000")
    val maBob = hashMintingAllowance("bob::provider", "0")
    val leaf1 = hashBatchOfMintingAllowances(
      Seq(hashMintingAllowance("alice::provider", "5.0"))
    )
    val leaf2 = hashBatchOfMintingAllowances(
      Seq(hashMintingAllowance("bob::provider", "3.0"))
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
      TestCase("hashRecord [hash 1, hash 'x']", HashList(Seq(h1, hx))),
      TestCase("hashVariant 'V1' [hash 1, hash 'x']", HashVariant("V1", Seq(h1, hx))),
    )
  }
}
