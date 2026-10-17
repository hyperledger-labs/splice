// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.plugins

import better.files.File
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.discard.Implicits.DiscardOps
import org.lfdecentralizedtrust.splice.config.{ConfigTransforms, SpliceConfig}

import java.nio.file.Path
import scala.collection.concurrent.TrieMap

final class OnboardingSnapshotDirectories extends SpliceEnvironmentSetupPlugin with BaseTest {

  private val created = TrieMap.empty[Path, Unit]

  override def beforeEnvironmentCreated(config: SpliceConfig): SpliceConfig =
    ConfigTransforms.updateAllSvAppConfigs { (name, sv) =>
      if (sv.onboardingSnapshots.directory.isDefined) sv
      else {
        val directory = File.newTemporaryDirectory(s"onboarding-snapshots-$name").path
        created.put(directory, ()).discard
        sv.copy(onboardingSnapshots = sv.onboardingSnapshots.copy(directory = Some(directory)))
      }
    }(config)

  override def afterEnvironmentDestroyed(config: SpliceConfig): Unit =
    config.svApps.values.flatMap(_.onboardingSnapshots.directory).foreach { directory =>
      if (created.remove(directory).isDefined)
        File(directory).delete(swallowIOExceptions = true).discard
    }
}
