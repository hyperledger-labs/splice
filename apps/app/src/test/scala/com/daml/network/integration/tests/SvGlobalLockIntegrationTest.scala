package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTestWithSharedEnvironment,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.SvTestUtil
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import monocle.macros.syntax.lens.*

// TODO(#5855) this whole test should be removed together with the actual lock

class SvGlobalLockIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with SvTestUtil {

  val globalLockTimeout = NonNegativeFiniteDuration.ofSeconds(2)

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .withManualStart
      .addConfigTransform((_, config) =>
        // for testing that input limits are respected.
        CNNodeConfigTransforms
          .updateAllSvAppFoundCollectiveConfigs_(
            _.focus(_.globalLockTimeout).replace(globalLockTimeout)
          )(config)
      )

  val dummyReason = "reason"
  val dummyTraceId = "traceid"

  "global lock locks exclusive" in { implicit env =>
    sv1Backend.startSync()

    sv1Backend.acquireGlobalLock(dummyReason, dummyTraceId, true)

    assertThrowsAndLogsCommandFailures(
      sv1Backend.acquireGlobalLock(dummyReason, dummyTraceId, true),
      _.errorMessage should include("Lock is not free"),
    )
    sv1Backend.releaseGlobalLock(dummyReason, dummyTraceId, true)
    sv1Backend.acquireGlobalLock(dummyReason, dummyTraceId, true)
    sv1Backend.releaseGlobalLock(dummyReason, dummyTraceId, true)
  }

  "global lock can be expired" in { implicit env =>
    sv1Backend.startSync()

    sv1Backend.acquireGlobalLock(dummyReason, dummyTraceId, true)
    Threading.sleep(globalLockTimeout.asJava.toMillis)

    loggerFactory.assertLogs(
      sv1Backend.acquireGlobalLock(dummyReason, dummyTraceId, true),
      _.warningMessage should include("Acquired expired lock held since"),
    )
    sv1Backend.releaseGlobalLock(dummyReason, dummyTraceId, true)
  }

  "shared lock can be acquired multiple times" in { implicit env =>
    sv1Backend.startSync()

    sv1Backend.acquireGlobalLock(dummyReason, dummyTraceId, false)
    sv1Backend.acquireGlobalLock(dummyReason, dummyTraceId, false)

    sv1Backend.releaseGlobalLock(dummyReason, dummyTraceId, false)
    sv1Backend.releaseGlobalLock(dummyReason, dummyTraceId, false)
  }
  "shared lock cannot be acquired exclusively" in { implicit env =>
    sv1Backend.startSync()

    sv1Backend.acquireGlobalLock(dummyReason, dummyTraceId, false)
    assertThrowsAndLogsCommandFailures(
      sv1Backend.acquireGlobalLock(dummyReason, dummyTraceId, true),
      _.errorMessage should include("Lock is not free"),
    )
    sv1Backend.releaseGlobalLock(dummyReason, dummyTraceId, false)
    sv1Backend.acquireGlobalLock(dummyReason, dummyTraceId, true)
    sv1Backend.releaseGlobalLock(dummyReason, dummyTraceId, true)
  }
  "exclusive lock cannot be acquired shared" in { implicit env =>
    sv1Backend.startSync()
    sv1Backend.acquireGlobalLock(dummyReason, dummyTraceId, true)
    assertThrowsAndLogsCommandFailures(
      sv1Backend.acquireGlobalLock(dummyReason, dummyTraceId, false),
      _.errorMessage should include("Lock is not free"),
    )
    sv1Backend.releaseGlobalLock(dummyReason, dummyTraceId, true)
    sv1Backend.acquireGlobalLock(dummyReason, dummyTraceId, false)
    sv1Backend.acquireGlobalLock(dummyReason, dummyTraceId, false)
  }
}
