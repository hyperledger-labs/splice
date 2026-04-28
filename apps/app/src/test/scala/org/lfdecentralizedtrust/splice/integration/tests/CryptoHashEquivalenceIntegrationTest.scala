package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.javaapi.data.{
  CreatedEvent,
  CreateCommand,
  DamlList,
  DamlRecord,
  ExerciseCommand,
  ExercisedEvent,
  Identifier,
  Numeric,
  Party,
  Text,
  Value,
}
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.{ParticipantId, PartyId}
import com.digitalasset.daml.lf.data.Ref
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.cryptohash.{Hash as DamlHash}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.rewardaccountingv2.{
  Batch,
  MintingAllowance,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.rewardaccountingv2.batch.{
  BatchOfBatches,
  BatchOfMintingAllowances,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.scan.store.db.{
  DbAppActivityRecordStore,
  DbScanAppRewardsStore,
}
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanAppRewardsStore.{
  AppActivityPartyTotalT,
  AppRewardPartyTotalT,
}
import org.lfdecentralizedtrust.splice.store.{HistoryMetrics, UpdateHistory}
import org.lfdecentralizedtrust.splice.store.UpdateHistory.BackfillingRequirement
import org.lfdecentralizedtrust.splice.util.{DarUtil, WalletTestUtil}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import java.io.File
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** Equivalence test: Daml (real participant) == SQL (scan Postgres).
  *
  * Validates that the Postgres SQL hash functions produce identical results
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

  "Batch hash equivalence (DB Merkle tree == Daml)" should {

    "set up rewards store and allocate parties" in { implicit env =>
      import env.executionContext
      val storage = sv1Backend.appState.storage match {
        case db: DbStorage => db
        case other => fail(s"Expected DbStorage but got ${other.getClass.getSimpleName}")
      }
      val participantId =
        ParticipantId.tryFromProtoPrimitive("PAR::batch-hash-test::dummy")
      val updateHistory = new UpdateHistory(
        storage,
        new DomainMigrationInfo(0L, None),
        "batch_hash_equiv_test",
        participantId,
        svParty,
        BackfillingRequirement.BackfillingNotRequired,
        loggerFactory,
        enableissue12777Workaround = true,
        enableImportUpdateBackfill = false,
        HistoryMetrics(NoOpMetricsFactory, 0L),
      )
      updateHistory.ingestionSink.initialize().futureValue
      val appActivityRecordStore = new DbAppActivityRecordStore(
        storage,
        updateHistory,
        loggerFactory,
      )
      rewardsStore = new DbScanAppRewardsStore(
        storage,
        updateHistory,
        appActivityRecordStore,
        loggerFactory,
      )
      rewardsHistoryId = updateHistory.historyId

      // Allocate real ledger parties for batch tests (idempotent across re-runs)
      testParties = (0 until MaxTestParties).map { i =>
        val hint = s"batch-test-$i"
        scala.util
          .Try(
            sv1Backend.participantClient.ledger_api.parties
              .allocate(hint)
              .party
          )
          .getOrElse(
            sv1Backend.participantClient.ledger_api.parties
              .list(filterParty = hint)
              .head
              .party
          )
      }
    }

    batchTestCases.foreach { tc =>
      tc.description in { implicit env =>
        val parties = tc.partyAmounts.zipWithIndex.map { case (amount, i) =>
          (i, testParties(i), amount)
        }

        clue("Insert test data") {
          rewardsStore
            .insertAppActivityPartyTotals(parties.map { case (_, party, _) =>
              AppActivityPartyTotalT(
                rewardsHistoryId,
                tc.roundNumber,
                1000L,
                party.toProtoPrimitive,
                1L,
              )
            })
            .futureValue
          rewardsStore
            .insertAppRewardPartyTotals(parties.map { case (seq, party, amount) =>
              AppRewardPartyTotalT(
                rewardsHistoryId,
                tc.roundNumber,
                seq,
                party.toProtoPrimitive,
                BigDecimal(amount),
              )
            })
            .futureValue
        }

        clue("Build Merkle tree in DB") {
          rewardsStore.computeRewardHashes(tc.roundNumber, tc.batchSize).futureValue
        }

        val dbHashes = clue("Read DB hashes") {
          val batches = rewardsStore.getAppRewardBatchHashesByRound(tc.roundNumber).futureValue
          batches.groupBy(_.batchLevel).map { case (lvl, bs) =>
            lvl -> bs.map(_.batchHash.toHex)
          }
        }

        val leafBatches = mkLeafBatches(parties, tc.batchSize)

        clue("Leaf hashes match") {
          val damlLeaves = leafBatches.map(b => exerciseDaml(HashBatch(b.toValue)))
          damlLeaves shouldBe dbHashes(0)
        }

        clue("Root hash matches") {
          val dbRoot = rewardsStore.getAppRewardRootHashByRound(tc.roundNumber).futureValue
          dbRoot shouldBe defined
          val damlRoot = exerciseDaml(HashBatch(mkRootBatch(leafBatches, tc.batchSize).toValue))
          damlRoot shouldBe dbRoot.value.rootHash.toHex
        }
      }
    }
  }

  /** Build the leaf Batch values from parties and amounts — pure data, no Daml calls. */
  private def mkLeafBatches(
      parties: Seq[(Int, PartyId, String)],
      batchSize: Int,
  ): Seq[BatchOfMintingAllowances] =
    if (parties.isEmpty) Seq(new BatchOfMintingAllowances(java.util.List.of()))
    else
      parties.sortBy(_._1).grouped(batchSize).toSeq.map { group =>
        val allowances = group.map { case (_, party, amount) =>
          new MintingAllowance(party.toProtoPrimitive, new java.math.BigDecimal(amount))
        }
        new BatchOfMintingAllowances(allowances.asJava)
      }

  /** Build the root Batch by hashing leaves and nesting into BatchOfBatches.
    * For a single leaf, the leaf itself is the root.
    */
  private def mkRootBatch(
      leaves: Seq[BatchOfMintingAllowances],
      batchSize: Int,
  )(implicit env: FixtureParam): Batch = {
    @annotation.tailrec
    def go(batches: Seq[Batch]): Batch =
      if (batches.size <= 1) batches.head
      else {
        val next: Seq[Batch] = batches.grouped(batchSize).toSeq.map { group =>
          val hashes = group.map(b => new DamlHash(exerciseDaml(HashBatch(b.toValue))))
          new BatchOfBatches(hashes.asJava): Batch
        }
        go(next)
      }

    go(leaves.map(l => l: Batch))
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
  private var rewardsStore: DbScanAppRewardsStore = _
  private var rewardsHistoryId: Long = _
  private var testParties: Seq[PartyId] = _

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
  case class HashDecimal(value: String) extends HashOp
  case class HashBatch(batch: Value) extends HashOp

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
    case HashDecimal(v) =>
      ("CryptoHashProxy_HashDecimal", record("input" -> new Numeric(new java.math.BigDecimal(v))))
    case HashBatch(batchValue) =>
      ("CryptoHashProxy_HashBatch", record("batch" -> batchValue))
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
    case HashDecimal(v) =>
      s"daml_crypto_hash_text(daml_numeric_to_text(${escapeSql(v)}::decimal(38,10)))"
    case HashBatch(_) =>
      throw new UnsupportedOperationException("HashBatch is not used in SQL expression tests")
  }

  // -- Test case definitions (fully static) -----------------------------------

  // Hand-written edge cases for hashing Decimal values. Each case verifies that
  // Daml's hash(d) equals Postgres' daml_crypto_hash_text(daml_numeric_to_text(d)),
  // which requires Daml's show and Postgres' daml_numeric_to_text to produce
  // identical text. Each entry is (value, category, description) targeting a
  // specific formatting divergence risk between the two representations.
  val decimalFormatCases: Seq[(String, String, String)] = Seq(
    ("10.0000000000", "trailing zeros", "10 trailing zeros are stripped to .0"),
    ("0.0000000000", "all-zero fraction", "result is 0.0, not 0 or 0."),
    ("5.0", "integer with .0", "trailing zeros stripped then .0 re-appended"),
    ("3.14", "fractional digits", "non-zero fractional digits pass through"),
    ("0", "bare integer", ".0 is appended when no decimal point"),
    ("100", "bare integer", ".0 is appended to larger integer"),
    ("-3.14", "negative", "sign is preserved"),
    ("-0.0", "negative zero", "Daml vs Postgres agree on -0.0"),
    ("0.0000000001", "smallest fraction", "10th decimal place survives"),
    ("99999999999.0", "large integer part", "11-digit integer part formats correctly"),
    ("1.10", "mixed trailing zeros", "single trailing zero stripped to 1.1"),
    ("9999999999999999999999999999.9999999999", "max positive", "28 integer + 10 fractional nines"),
    ("-9999999999999999999999999999.9999999999", "max negative", "negated max"),
    ("-0.0000000001", "negative fraction", "sign + smallest fraction"),
  )

  // Test cases are defined statically using HashRef (Lit/IntermediateRef) to
  // reference intermediate hashes by name. Each test resolves its refs from
  // a shared map populated progressively as earlier tests run.
  //
  // Order matters: intermediates must precede composites that reference them.
  val allTestCaseDefs: Seq[TestCaseDef] =
    decimalFormatCases.map { case (v, category, desc) =>
      TestCaseDef(s"decimal $v ($category: $desc)", HashDecimal(v))
    } ++ Seq(
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

  // -- Batch tree equivalence test data ----------------------------------------

  val MaxTestParties = 5

  case class BatchTestCase(
      description: String,
      roundNumber: Long,
      batchSize: Int,
      partyAmounts: Seq[String],
  )

  val batchTestCases: Seq[BatchTestCase] = Seq(
    BatchTestCase("single party", 9001, 2, Seq("10.0000000000")),
    BatchTestCase("two parties one batch", 9002, 2, Seq("10.0000000000", "5.0000000000")),
    BatchTestCase(
      "three parties two batches",
      9003,
      2,
      Seq("10.0000000000", "5.0000000000", "3.0000000000"),
    ),
    BatchTestCase(
      "five parties multi-level",
      9004,
      2,
      Seq("10.0000000000", "5.0000000000", "3.0000000000", "7.0000000000", "1.0000000000"),
    ),
    BatchTestCase("empty round", 9005, 2, Seq.empty),
  )
}
