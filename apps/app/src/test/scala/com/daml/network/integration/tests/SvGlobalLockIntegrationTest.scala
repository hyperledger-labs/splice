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
      .simpleTopologyX(this.getClass.getSimpleName)
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

  "global lock locks" in { implicit env =>
    sv1.startSync();

    sv1.acquireGlobalLock(dummyReason, dummyTraceId)

    assertThrowsAndLogsCommandFailures(
      sv1.acquireGlobalLock(dummyReason, dummyTraceId),
      _.errorMessage should include("Lock is not free"),
    )
    sv1.releaseGlobalLock(dummyReason, dummyTraceId)
    sv1.acquireGlobalLock(dummyReason, dummyTraceId)
    sv1.releaseGlobalLock(dummyReason, dummyTraceId)
  }

  "global lock can be expired" in { implicit env =>
    sv1.startSync();

    sv1.acquireGlobalLock(dummyReason, dummyTraceId)
    Threading.sleep(globalLockTimeout.asJava.toMillis)

    loggerFactory.assertLogs(
      sv1.acquireGlobalLock(dummyReason, dummyTraceId),
      _.warningMessage should include("Acquired expired lock held since"),
    )
    sv1.releaseGlobalLock(dummyReason, dummyTraceId)
  }
}
