package org.lfdecentralizedtrust.splice.environment

import com.daml.ledger.api.v2.package_reference.PackageReference
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import com.digitalasset.daml.lf.data.Ref.PackageVersion
import org.lfdecentralizedtrust.splice.environment.PackageVersionSupport.FeatureSupport
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

class TopologyAwarePackageVersionSupportTest extends BaseTest with AnyWordSpecLike {

  private val synchronizerId = SynchronizerId.tryFromString("domain::id")
  private val connectionMock = mock[BaseLedgerConnection]
  private val packageVersionSupport = new TopologyAwarePackageVersionSupport(
    synchronizerId,
    connectionMock,
  )(ExecutionContext.global)

  private val party1 = PartyId.tryFromProtoPrimitive("party1::default")
  private val parties = Seq(party1)
  private val now = CantonTimestamp.Epoch

  private def mockGetSupportedPackageVersion(
      packageName: String,
      result: Option[PackageReference],
  ): Unit =
    when(
      connectionMock.getSupportedPackageVersion(
        eqTo(synchronizerId),
        eqTo(parties),
        eqTo(packageName),
        eqTo(now),
      )(anyTraceContext)
    )
      .thenReturn(Future.successful(result))

  private def testFeatureSupport(
      featureName: String,
      requiredDar: DarResource,
      featureCheck: (Seq[PartyId], CantonTimestamp) => Future[FeatureSupport],
  ): Unit = {
    val requiredPackageName = requiredDar.metadata.name
    val requiredVersion = requiredDar.metadata.version
    val reportedPackageId = UUID.randomUUID().toString

    s"support $featureName when topology reports version >= $requiredVersion" in {
      mockGetSupportedPackageVersion(
        requiredPackageName,
        Some(PackageReference(reportedPackageId, requiredPackageName, requiredVersion.toString())),
      )
      whenReady(featureCheck(parties, now)) { result =>
        result shouldBe FeatureSupport(supported = true, Seq(reportedPackageId))
      }

      mockGetSupportedPackageVersion(
        requiredPackageName,
        Some(
          PackageReference(
            reportedPackageId,
            requiredPackageName,
            PackageVersion
              .assertFromInts(
                requiredVersion.segments.map(_ + 1).toSeq
              )
              .toString(),
          )
        ),
      )
      whenReady(featureCheck(parties, now)) { result =>
        result shouldBe FeatureSupport(supported = true, Seq(reportedPackageId))
      }
    }

    s"not support $featureName when topology reports version < $requiredVersion" in {
      mockGetSupportedPackageVersion(
        requiredPackageName,
        Some(
          PackageReference(
            reportedPackageId,
            requiredPackageName, {
              val segments = requiredVersion.segments.toList

              val lastNonZeroVersionPart = segments.lastIndexWhere(_ > 0)
              PackageVersion
                .assertFromInts(
                  segments.updated(lastNonZeroVersionPart, segments(lastNonZeroVersionPart) - 1)
                )
                .toString()
            },
          )
        ),
      )
      whenReady(featureCheck(parties, now)) { result =>
        result shouldBe FeatureSupport(supported = false, Seq(reportedPackageId))
      }
    }

    s"not support $featureName when topology reports no version" in {
      mockGetSupportedPackageVersion(requiredPackageName, None)
      whenReady(featureCheck(parties, now)) { result =>
        result shouldBe FeatureSupport(supported = false, Seq.empty)
      }
    }
  }

  "TopologyAwarePackageVersionSupport" should {

    testFeatureSupport(
      "ValidatorLicenseActivity",
      DarResources.amulet_0_1_3,
      packageVersionSupport.supportsValidatorLicenseActivity,
    )

    testFeatureSupport(
      "PruneAmuletConfigSchedule",
      DarResources.dsoGovernance_0_1_5,
      packageVersionSupport.supportsPruneAmuletConfigSchedule,
    )

    testFeatureSupport(
      "MergeDuplicatedValidatorLicense",
      DarResources.dsoGovernance_0_1_8,
      packageVersionSupport.supportsMergeDuplicatedValidatorLicense,
    )

    testFeatureSupport(
      "LegacySequencerConfig",
      DarResources.dsoGovernance_0_1_7,
      packageVersionSupport.supportsLegacySequencerConfig,
    )

    testFeatureSupport(
      "ValidatorLivenessActivityRecord",
      DarResources.amulet_0_1_5,
      packageVersionSupport.supportsValidatorLivenessActivityRecord,
    )

    testFeatureSupport(
      "DsoRulesCreateExternalPartyAmuletRules",
      DarResources.dsoGovernance_0_1_9,
      packageVersionSupport.supportsDsoRulesCreateExternalPartyAmuletRules,
    )

    testFeatureSupport(
      "NewGovernanceFlow",
      DarResources.dsoGovernance_0_1_11,
      packageVersionSupport.supportsNewGovernanceFlow,
    )
  }
}
