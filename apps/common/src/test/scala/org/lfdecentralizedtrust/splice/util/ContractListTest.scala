package org.lfdecentralizedtrust.splice.util

import cats.data.Chain
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.tracing.TraceContext
import com.google.protobuf.ByteString
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.{
  allocationrequestv1,
  allocationrequestv2,
  allocationv1,
  allocationv2,
  holdingv2,
  metadatav1,
}
import org.lfdecentralizedtrust.splice.http.v0.definitions as d0
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import java.util.Optional

class ContractListTest extends AnyWordSpec with BaseTest with NamedLogging {

  private implicit val tc: TraceContext = TraceContext.empty

  private val emptyMetadata = new metadatav1.Metadata(java.util.Map.of())

  private var cidCounter = 0

  private def nextCid(): String = {
    cidCounter += 1
    "00" + f"$cidCounter%064x"
  }

  private def mkV1Contract(
      createdAt: Instant,
      contractId: String = nextCid(),
  ): Contract[
    allocationrequestv1.AllocationRequest.ContractId,
    allocationrequestv1.AllocationRequestView,
  ] = {
    val date = Instant.EPOCH
    val view = new allocationrequestv1.AllocationRequestView(
      new allocationv1.SettlementInfo(
        "executor",
        new allocationv1.Reference("ref", Optional.empty()),
        date,
        date,
        date,
        emptyMetadata,
      ),
      java.util.Map.of(),
      emptyMetadata,
    )
    Contract(
      identifier = allocationrequestv1.AllocationRequest.INTERFACE_ID_WITH_PACKAGE_ID,
      contractId = new allocationrequestv1.AllocationRequest.ContractId(contractId),
      payload = view,
      createdEventBlob = ByteString.EMPTY,
      createdAt = createdAt,
    )
  }

  private def mkV2Contract(
      createdAt: Instant,
      contractId: String = nextCid(),
  ): Contract[
    allocationrequestv2.AllocationRequest.ContractId,
    allocationrequestv2.AllocationRequestView,
  ] = {
    val date = Instant.EPOCH
    val account = new holdingv2.Account("owner", Optional.empty(), "account-id")
    val instrumentId = new holdingv2.InstrumentId("admin", "Amulet")
    val view = new allocationrequestv2.AllocationRequestView(
      account,
      new allocationv2.SettlementInfo(
        java.util.List.of("executor"),
        new allocationv2.Reference("ref", Optional.empty()),
        date,
        date,
        Optional.empty(),
        emptyMetadata,
      ),
      java.util.List.of(
        new allocationv2.TransferLeg(
          "leg-1",
          account,
          account,
          java.math.BigDecimal.ONE.setScale(10),
          instrumentId,
          emptyMetadata,
        )
      ),
      java.util.Map.of(),
      emptyMetadata,
    )
    Contract(
      identifier = allocationrequestv2.AllocationRequest.INTERFACE_ID_WITH_PACKAGE_ID,
      contractId = new allocationrequestv2.AllocationRequest.ContractId(contractId),
      payload = view,
      createdEventBlob = ByteString.EMPTY,
      createdAt = createdAt,
    )
  }

  private def compare(result: Chain[d0.Contract], expected: Seq[Contract[?, ?]]) =
    result.toList.map(_.contractId) should be(expected.map(_.contractId.contractId))

  "mergeSortedContractListsToHttp" should {

    "return empty when both lists are empty" in {
      val result = ContractList.mergeSortedContractListsToHttp(Seq.empty, Seq.empty)
      result shouldBe Chain.empty
    }

    "return one list when the other is empty" in {
      val c1 = mkV1Contract(Instant.ofEpochSecond(1))
      val c2 = mkV1Contract(Instant.ofEpochSecond(2))
      val result1 = ContractList.mergeSortedContractListsToHttp(Seq(c1, c2), Seq.empty)
      compare(result1, Seq(c1, c2))
      val result2 = ContractList.mergeSortedContractListsToHttp(Seq.empty, Seq(c1, c2))
      compare(result2, Seq(c1, c2))
    }

    "merge two non-overlapping lists in createdAt order" in {
      val v1a = mkV1Contract(Instant.ofEpochSecond(1))
      val v2a = mkV2Contract(Instant.ofEpochSecond(2))
      val v1b = mkV1Contract(Instant.ofEpochSecond(3))
      val v2b = mkV2Contract(Instant.ofEpochSecond(4))

      val result = ContractList.mergeSortedContractListsToHttp(Seq(v1a, v1b), Seq(v2a, v2b))
      compare(result, Seq(v1a, v2a, v1b, v2b))
    }

    "handle duplicates" in {
      val sharedCid = nextCid()
      val v1a = mkV1Contract(Instant.ofEpochSecond(1))
      val v1Dup1 = mkV1Contract(Instant.ofEpochSecond(2), contractId = sharedCid)
      val v1b = mkV1Contract(Instant.ofEpochSecond(4))
      val v1Dup2 = mkV1Contract(Instant.ofEpochSecond(5), contractId = sharedCid)

      val v2Dup1 = mkV2Contract(Instant.ofEpochSecond(2), contractId = sharedCid)
      val v2a = mkV2Contract(Instant.ofEpochSecond(3))
      val v2Dup2 = mkV2Contract(Instant.ofEpochSecond(5), contractId = sharedCid)

      val result = ContractList.mergeSortedContractListsToHttp(
        Seq(v1a, v1Dup1, v1b, v1Dup2),
        Seq(v2Dup1, v2a, v2Dup2),
      )
      compare(result, Seq(v1a, v2Dup1, v2a, v1b, v2Dup2))
      // The duplicate entries should come from l2 (v2 template id)
      val dupEntries = result.toList.filter(_.contractId == sharedCid)
      dupEntries should have size 2
      forAll(dupEntries) { dupEntry =>
        dupEntry.templateId shouldBe
          s"${v2Dup1.identifier.getPackageId}:${v2Dup1.identifier.getModuleName}:${v2Dup1.identifier.getEntityName}"
      }
    }
  }
}
