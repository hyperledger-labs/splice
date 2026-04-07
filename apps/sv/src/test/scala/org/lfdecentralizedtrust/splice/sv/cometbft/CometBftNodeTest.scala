package org.lfdecentralizedtrust.splice.sv.cometbft

import org.lfdecentralizedtrust.splice.codegen.java.splice as daml
import org.lfdecentralizedtrust.splice.codegen.java.splice.cometbft.{
  CometBftConfig,
  CometBftNodeConfig,
  GovernanceKeyConfig,
  SequencingKeyConfig,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.decentralizedsynchronizer.SynchronizerNodeConfig
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.svstate.SvNodeState
import com.digitalasset.canton.{BaseTest, drivers as proto}
import com.digitalasset.canton.drivers.cometbft.SvNodeConfigChange
import com.digitalasset.canton.topology.SynchronizerId
import monocle.Monocle.toAppliedFocusOps
import org.scalatest.wordspec.AnyWordSpec

import java.util.Optional
import scala.jdk.CollectionConverters.*

class CometBftNodeTest extends AnyWordSpec with BaseTest {

  private val owningSvNodeNr = 1
  private val owningSvNodeId = mkSvNodeId(owningSvNodeNr)
  private val chainId = "dummy-chain-id"
  private val dummyDsoSynchronizerId = SynchronizerId.tryFromString("domain1::domain")

  private val cometBftRequestSigner = CometBftRequestSigner.genesisSigner

  private def mkCometBftNodeName(svNodeNr: Int) = "cometBftNode" + svNodeNr.toString

  private def mkSvNodeId(svNodeNr: Int) = "svNode" + svNodeNr.toString

  private def mkChangeRequest(svNodeNr: Int, targetState: Option[String]) = {
    val svNodeId = mkSvNodeId(svNodeNr)
    val changeRequest = proto.cometbft.SvNodeConfigChangeRequest(
      svNodeId = svNodeId,
      currentConfigRevision = (svNodeNr % 2).toLong,
      change = Some(mkSvNodeConfigChange(svNodeNr, targetState)),
    )
    proto.cometbft.NetworkConfigChangeRequest(
      chainId = chainId,
      submitterSvNodeId = owningSvNodeId,
      submitterKeyId = cometBftRequestSigner.fingerprint,
      kind = proto.cometbft.NetworkConfigChangeRequest.Kind.NodeConfigChangeRequest(changeRequest),
    )
  }

  private def mkSvNodeConfig(svNodeNr: Int, validatorKey: String) =
    proto.cometbft.SvNodeConfig(
      Map(
        mkCometBftNodeName(svNodeNr) -> proto.cometbft.CometBftNodeConfig(
          validatorPubKey = validatorKey,
          votingPower = 1L,
        )
      )
    )

  private def mkSvNodeConfigChange(svNodeNr: Int, targetState: Option[String]) =
    proto.cometbft.SvNodeConfigChange(kind = targetState match {
      case None =>
        proto.cometbft.SvNodeConfigChange.Kind.DeleteConfig(com.google.protobuf.empty.Empty())
      case Some(validatorKey) =>
        proto.cometbft.SvNodeConfigChange.Kind.SetConfig(mkSvNodeConfig(svNodeNr, validatorKey))
    })

  private def mkNetworkConfig(
      currentConfigs: Seq[(Int, String, Seq[(Int, Option[String])])]
  ) = {
    proto.cometbft.GetNetworkConfigResponse(
      chainId = chainId,
      svNodeConfigStates = currentConfigs.map { case (svNodeNr, validatorKey, pendingChanges) =>
        val currentRevision = (svNodeNr % 2).toLong
        (
          mkSvNodeId(svNodeNr),
          proto.cometbft.SvNodeConfigState(
            currentConfigRevision = currentRevision,
            currentConfig =
              // Even numbered nodes don't yet have a current config
              Option.unless(currentRevision == 0)(mkSvNodeConfig(svNodeNr, validatorKey)),
            pendingChanges = pendingChanges.map { case (submitterId, targetState) =>
              mkSvNodeId(submitterId) -> proto.cometbft.SvNodeConfigPendingChange(change =
                Some(mkSvNodeConfigChange(svNodeNr, targetState))
              )
            }.toMap,
          ),
        )
      }.toMap,
    )
  }

  private def mkSvNodeStates(
      members: Seq[(Int, String)]
  ): Seq[SvNodeState] =
    members.map { case (svNodeNr, validatorKey) =>
      val svParty = "svNodeParty" + svNodeNr.toString
      new SvNodeState(
        "dso",
        svParty,
        mkSvNodeId(svNodeNr),
        new daml.dso.svstate.NodeState(
          Map(
            dummyDsoSynchronizerId.toProtoPrimitive -> new SynchronizerNodeConfig(
              new CometBftConfig(
                Map(
                  mkCometBftNodeName(svNodeNr) -> new CometBftNodeConfig(
                    validatorKey,
                    1L,
                  )
                ).asJava,
                Seq[GovernanceKeyConfig]().asJava,
                Seq[SequencingKeyConfig]().asJava,
              ),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
            )
          ).asJava
        ),
      )
    }

  private def testCase(
      targetConfig: Option[String],
      currentConfig: Option[String],
      pendingConfig: Option[Option[String]],
      expectChange: Boolean,
  ) = {
    val nodeNr = if (currentConfig.isDefined) 3 else 4
    val networkConfig = mkNetworkConfig(
      Seq(
        (
          nodeNr,
          currentConfig.getOrElse("dummy-key"),
          pendingConfig.map(change => (owningSvNodeNr -> change)).toList,
        ),
        (
          10,
          "dummy-key-10",
          Seq(owningSvNodeNr -> Some("key-10"), nodeNr -> None),
        ), // other not-yet-configured-nodes should not impact the changes for the node of interest
        (
          11,
          "key-11",
          Seq(owningSvNodeNr -> Some("key-11"), nodeNr -> Some("dummy-key-X")),
        ), // other configured nodes should not impact the changes for the node of interest
      )
    )
    val configDiff = CometBftNode
      .diffNetworkConfig(
        owningSvNodeId,
        cometBftRequestSigner.fingerprint,
        mkSvNodeStates(
          targetConfig.map((nodeNr, _)).toList ++ Seq(10 -> "key-10", 11 -> "key-11")
        ),
        networkConfig,
        dummyDsoSynchronizerId,
        logger,
      )
      .requests
    if (expectChange) {
      val request = mkChangeRequest(nodeNr, targetConfig)
      configDiff should have size 1
      val actualRequest = configDiff.head
      // The submittedAt field is not deterministic, so we remove it from the expected request
      actualRequest.submittedAt should be(defined)
      actualRequest.focus(_.submittedAt).replace(None) shouldBe request
    } else {
      configDiff should be(empty)
    }
  }

  "CometBftNode.diffNetworkConfig" should {

    // The test-cases iterate through all combinations of the Option values and matching or non-matching strings.
    // They are sorted in lexicographic order for (targetConfig, currentConfig, pendingConfig)

    "be idempotent wrt a not-yet configured node" in testCase(
      targetConfig = None,
      currentConfig = None,
      pendingConfig = None,
      expectChange = false,
    )

    "be idempotent wrt a matching pending delete_config request for a not-yet configured node" in testCase(
      targetConfig = None,
      currentConfig = None,
      pendingConfig = Some(None),
      expectChange = false,
    )

    "override a pending set_config request for a not-yet configured node" in testCase(
      targetConfig = None,
      currentConfig = None,
      pendingConfig = Some(Some("a")),
      expectChange = true,
    )

    "issue a delete_config request for a configured node" in testCase(
      targetConfig = None,
      currentConfig = Some("a"),
      pendingConfig = None,
      expectChange = true,
    )

    "be idempotent wrt a matching pending delete_config request for a configured node" in testCase(
      targetConfig = None,
      currentConfig = Some("a"),
      pendingConfig = Some(None),
      expectChange = false,
    )

    "override a pending set_config request for a matching configured node that should not exist" in testCase(
      targetConfig = None,
      currentConfig = Some("a"),
      pendingConfig = Some(Some("a")),
      expectChange = true,
    )

    "override a pending set_config request for a non-matching configured node that should not exist" in testCase(
      targetConfig = None,
      currentConfig = Some("a"),
      pendingConfig = Some(Some("b")),
      expectChange = true,
    )

    "create a missing node" in testCase(
      targetConfig = Some("a"),
      currentConfig = None,
      pendingConfig = None,
      expectChange = true,
    )

    "override a delete_config request when creating a missing node" in testCase(
      targetConfig = Some("a"),
      currentConfig = None,
      pendingConfig = Some(None),
      expectChange = true,
    )

    "be idempotent wrt a matching write_config request when creating a missing node" in testCase(
      targetConfig = Some("a"),
      currentConfig = None,
      pendingConfig = Some(Some("a")),
      expectChange = false,
    )

    "override a mismatching write_config request when creating a missing node" in testCase(
      targetConfig = Some("a"),
      currentConfig = None,
      pendingConfig = Some(Some("b")),
      expectChange = true,
    )

    "be idempotent if there already exists a matching node" in testCase(
      targetConfig = Some("a"),
      currentConfig = Some("a"),
      pendingConfig = None,
      expectChange = false,
    )

    "override a delete_config request even if the node already exists" in testCase(
      targetConfig = Some("a"),
      currentConfig = Some("a"),
      pendingConfig = Some(None),
      expectChange = true,
    )

    "be idempotent wrt a matching write_config request for the same node" in testCase(
      targetConfig = Some("a"),
      currentConfig = Some("a"),
      pendingConfig = Some(Some("a")),
      expectChange = false,
    )

    "override a mismatching write_config request even if the node already exists" in testCase(
      targetConfig = Some("a"),
      currentConfig = Some("a"),
      pendingConfig = Some(Some("b")),
      expectChange = true,
    )

    "issue a set_config request for an existing node" in testCase(
      targetConfig = Some("a"),
      currentConfig = Some("b"),
      pendingConfig = None,
      expectChange = true,
    )

    "override a delete_config request for an existing node that's configured differently" in testCase(
      targetConfig = Some("a"),
      currentConfig = Some("b"),
      pendingConfig = Some(None),
      expectChange = true,
    )

    "be idempotent wrt a matching write_config request for the same node that's configured differently" in testCase(
      targetConfig = Some("a"),
      currentConfig = Some("b"),
      pendingConfig = Some(Some("a")),
      expectChange = false,
    )

    "override a mismatching write_config request the same node that's configured differently" in testCase(
      targetConfig = Some("a"),
      currentConfig = Some("b"),
      pendingConfig = Some(Some("b")),
      expectChange = true,
    )

    "updates should be issues before deletes" in {
      val networkConfig = mkNetworkConfig(
        Seq(
          (
            11,
            "dummy-key-10",
            Seq.empty,
          )
        )
      )
      val configDiff = CometBftNode
        .diffNetworkConfig(
          owningSvNodeId,
          cometBftRequestSigner.fingerprint,
          mkSvNodeStates(
            Seq(10 -> "key-10")
          ),
          networkConfig,
          dummyDsoSynchronizerId,
          logger,
        )
        .requests
      configDiff.size shouldBe 2
      configDiff.map(
        _.getNodeConfigChangeRequest.getChange.kind.getClass
      ) should contain theSameElementsInOrderAs Seq(
        classOf[SvNodeConfigChange.Kind.SetConfig],
        classOf[SvNodeConfigChange.Kind.DeleteConfig],
      )
    }

  }

}
