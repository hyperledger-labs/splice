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
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.daml.lf.data.Ref
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.{DarUtil, WalletTestUtil}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import java.io.File
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** Equivalence test: Daml (real participant) == SQL (scan Postgres).
  *
  * Validates that the plpgsql hash functions produce identical results
  * to the Daml CryptoHash module. Daml is the authority.
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
      .withManualStart

  "CryptoHash equivalence (Daml == SQL)" should {

    "set up environment" in { implicit env =>
      startAllSync(sv1Backend)
      svParty = sv1Backend.getDsoInfo().svParty
      svDb = sv1Backend.appState.storage

      clue("Upload dar and create CryptoHashProxy") {
        sv1Backend.participantClient.upload_dar_unless_exists(darPath)
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
    }

    allTestCaseDefs.foreach { tcDef =>
      tcDef.description in { implicit env =>
        val damlHash = clue("Daml") { exerciseDaml(tcDef.op) }
        intermediates(tcDef.description) = damlHash
        clue("SQL should match Daml") {
          val sqlHash = svDb
            .query(
              sql"""select #${toSqlExpr(tcDef.op, resolveRef)}""".as[String].head,
              "test.sqlHash",
            )
            .futureValueUS
          sqlHash shouldBe damlHash
        }
      }
    }
  }

  /** Exercise a Daml choice and return the Text result. */
  private def exerciseDaml(op: HashOp)(implicit env: FixtureParam): String = {
    val (choiceName, choiceArg) = toDamlChoiceAndArg(op, resolveRef)
    val cmd = new ExerciseCommand(templateId, proxyContractId, choiceName, choiceArg)
    val tx = sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
      .submitJava(
        actAs = Seq(svParty),
        commands = Seq(cmd),
      )
    tx.getEventsById.values.asScala
      .collectFirst { case ex: ExercisedEvent =>
        ex.getExerciseResult match {
          case t: Text => t.getValue
          case other =>
            throw new IllegalStateException(
              s"Expected Text result but got ${other.getClass.getSimpleName}"
            )
        }
      }
      .getOrElse(throw new IllegalStateException("No ExercisedEvent in transaction"))
  }

  private def resolveRef(ref: HashRef): String = ref match {
    case Lit(v) => v
    case IntermediateRef(name) =>
      intermediates.getOrElse(
        name,
        throw new IllegalStateException(s"Intermediate '$name' not found"),
      )
  }

  // Progressive map: each test stores its Daml result for later tests to reference
  private val intermediates: mutable.Map[String, String] = mutable.Map.empty

  private var svParty: PartyId = _
  private var proxyContractId: String = _
  private var svDb: DbStorage = _

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

  // -- HashRef: symbolic references to intermediate hash values ---------------

  sealed trait HashRef
  case class Lit(value: String) extends HashRef
  case class IntermediateRef(name: String) extends HashRef

  // -- HashOp: test case operations (may contain HashRef) ---------------------

  sealed trait HashOp
  case class HashText(value: String) extends HashOp
  case class HashList(elems: Seq[HashRef]) extends HashOp
  case class HashVariant(tag: String, fields: Seq[HashRef]) extends HashOp
  case class HashMintingAllowance(provider: String, amount: String) extends HashOp
  case class HashBatchOfMintingAllowances(hashes: Seq[HashRef]) extends HashOp
  case class HashBatchOfBatches(hashes: Seq[HashRef]) extends HashOp

  case class TestCaseDef(description: String, op: HashOp)

  // -- Derive Daml choice + arg from HashOp ----------------------------------

  private def text(s: String): Value = new Text(s)
  private def textList(ss: Seq[String]): Value =
    DamlList.of(ss.map(s => new Text(s): Value).asJava)
  private def record(fields: (String, Value)*): Value =
    new DamlRecord(fields.map { case (k, v) => new DamlRecord.Field(k, v) }.asJava)

  def toDamlChoiceAndArg(op: HashOp, r: HashRef => String): (String, Value) = op match {
    case HashText(v) =>
      ("CryptoHashProxy_HashText", record("input" -> text(v)))
    case HashList(elems) =>
      ("CryptoHashProxy_HashList", record("elems" -> textList(elems.map(r))))
    case HashVariant(tag, fields) =>
      (
        "CryptoHashProxy_HashVariant",
        record("tag" -> text(tag), "fields" -> textList(fields.map(r))),
      )
    case HashMintingAllowance(provider, amount) =>
      (
        "CryptoHashProxy_HashMintingAllowance",
        record("provider" -> text(provider), "amount" -> text(amount)),
      )
    case HashBatchOfMintingAllowances(hashes) =>
      (
        "CryptoHashProxy_HashBatchOfMintingAllowances",
        record("allowanceHashes" -> textList(hashes.map(r))),
      )
    case HashBatchOfBatches(hashes) =>
      ("CryptoHashProxy_HashBatchOfBatches", record("childHashes" -> textList(hashes.map(r))))
  }

  // -- Derive SQL expression from HashOp -------------------------------------

  private def escapeSql(s: String): String = s.replace("'", "''")
  private def sqlArray(elems: Seq[String]): String =
    s"ARRAY[${elems.map(e => s"'$e'").mkString(", ")}]::text[]"

  def toSqlExpr(op: HashOp, r: HashRef => String): String = op match {
    case HashText(v) =>
      s"daml_crypto_hash_text('${escapeSql(v)}')"
    case HashList(elems) =>
      s"daml_crypto_hash_list(${sqlArray(elems.map(r))})"
    case HashVariant(tag, fields) =>
      s"daml_crypto_hash_variant('${escapeSql(tag)}', ${sqlArray(fields.map(r))})"
    case HashMintingAllowance(provider, amount) =>
      s"hash_minting_allowance('${escapeSql(provider)}', '${escapeSql(amount)}')"
    case HashBatchOfMintingAllowances(hashes) =>
      s"hash_batch_of_minting_allowances(${sqlArray(hashes.map(r))})"
    case HashBatchOfBatches(hashes) =>
      s"hash_batch_of_batches(${sqlArray(hashes.map(r))})"
  }

  // -- Test case definitions (fully static) -----------------------------------

  // Test cases are defined statically using HashRef (Lit/IntermediateRef) to
  // reference intermediate hashes by name. Each test resolves its refs from
  // a shared map populated progressively as earlier tests run.
  //
  // Order matters: intermediates must precede composites that reference them.
  val allTestCaseDefs: Seq[TestCaseDef] = Seq(
    // Primitive text hashes — used as intermediates by composite cases
    TestCaseDef("hAlice", HashText("alice::provider")),
    TestCaseDef("h10", HashText("10.0")),
    TestCaseDef("hOnly", HashText("only")),
    TestCaseDef("h1", HashText("1")),
    TestCaseDef("hx", HashText("x")),

    // Minting allowance hashes — used as intermediates by batch cases
    TestCaseDef("maAlice", HashMintingAllowance("alice::provider", "10.0000000000")),
    TestCaseDef("maBob", HashMintingAllowance("bob::provider", "0")),
    TestCaseDef("maAlice5", HashMintingAllowance("alice::provider", "5.0")),
    TestCaseDef("maBob3", HashMintingAllowance("bob::provider", "3.0")),
    // Batch hashes — used as intermediates by batch-of-batches
    TestCaseDef("leaf1", HashBatchOfMintingAllowances(Seq(IntermediateRef("maAlice5")))),
    TestCaseDef("leaf2", HashBatchOfMintingAllowances(Seq(IntermediateRef("maBob3")))),

    // Independent cases (no intermediate references)
    TestCaseDef("hash of 'hello'", HashText("hello")),
    TestCaseDef("hash of empty string", HashText("")),
    TestCaseDef("hashList on empty array", HashList(Seq.empty)),

    // Composite cases using intermediate hashes
    TestCaseDef(
      "hashList with two elements",
      HashList(Seq(IntermediateRef("hAlice"), IntermediateRef("h10"))),
    ),
    TestCaseDef("hashList on single element", HashList(Seq(IntermediateRef("hOnly")))),
    TestCaseDef(
      "hashVariant with tag and one field",
      HashVariant("TestTag", Seq(IntermediateRef("hAlice"))),
    ),
    TestCaseDef(
      "hashRecord [hash 1, hash 'x']",
      HashList(Seq(IntermediateRef("h1"), IntermediateRef("hx"))),
    ),
    TestCaseDef(
      "hashVariant 'V1' [hash 1, hash 'x']",
      HashVariant("V1", Seq(IntermediateRef("h1"), IntermediateRef("hx"))),
    ),
    TestCaseDef(
      "hash_batch_of_minting_allowances with two",
      HashBatchOfMintingAllowances(Seq(IntermediateRef("maAlice"), IntermediateRef("maBob"))),
    ),
    TestCaseDef(
      "hash_batch_of_batches with two leaves",
      HashBatchOfBatches(Seq(IntermediateRef("leaf1"), IntermediateRef("leaf2"))),
    ),
  )
}
