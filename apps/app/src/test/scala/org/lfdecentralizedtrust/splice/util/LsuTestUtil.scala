package org.lfdecentralizedtrust.splice.util

import com.digitalasset.canton.data.CantonTimestamp
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.LogicalSynchronizerUpgradeSchedule
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  SpliceTestConsoleEnvironment,
  TestCommon,
}

import scala.concurrent.ExecutionContextExecutor

trait LsuTestUtil extends SvTestUtil with TestCommon {
  this: CommonAppInstanceReferences =>
  protected def scheduleLsu(
      topologyFreezeTime: CantonTimestamp,
      upgradeTime: CantonTimestamp,
      serial: Long,
  )(implicit ec: ExecutionContextExecutor, env: SpliceTestConsoleEnvironment): Unit = {
    scheduleLogicalSynchronizerUpgrade(
      sv1Backend,
      Seq(sv2Backend, sv3Backend),
      new LogicalSynchronizerUpgradeSchedule(
        topologyFreezeTime.toInstant,
        upgradeTime.toInstant,
        serial,
        sv1Backend.config.localSynchronizerNodes.current.protocolVersion.toString,
      ),
    )
  }
}
