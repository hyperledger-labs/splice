// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.plugins

import better.files.File
import com.digitalasset.canton.BaseTest
import com.typesafe.config.ConfigFactory
import org.lfdecentralizedtrust.splice.config.{ConfigTransforms, SpliceConfig}
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.Files

class OnboardingSnapshotDirectoriesTest extends AnyWordSpec with BaseTest {
  private val config = SpliceConfig
    .loadAndValidate(
      ConfigFactory.parseFile(new java.io.File("apps/app/src/test/resources/simple-topology.conf"))
    )
    .value

  private def directories(config: SpliceConfig) =
    config.svApps.values.flatMap(_.onboardingSnapshots.directory).toSeq

  "OnboardingSnapshotDirectories" should {

    "give each SV its own directory and delete them when the environment is destroyed" in {
      val plugin = new OnboardingSnapshotDirectories()
      val prepared = plugin.beforeEnvironmentCreated(config)
      val created = directories(prepared)
      created should have size config.svApps.size.toLong
      created.distinct should have size created.size.toLong
      created.foreach(directory => Files.isDirectory(directory) shouldBe true)
      Files.write(created.head.resolve("snapshot"), "snapshot".getBytes)

      plugin.afterEnvironmentDestroyed(prepared)
      created.foreach(directory => Files.exists(directory) shouldBe false)
    }

    "leave directories configured by the test alone" in File.usingTemporaryDirectory() { own =>
      val plugin = new OnboardingSnapshotDirectories()
      val configured = ConfigTransforms.updateAllSvAppConfigs_(sv =>
        sv.copy(onboardingSnapshots = sv.onboardingSnapshots.copy(directory = Some(own.path)))
      )(config)
      val prepared = plugin.beforeEnvironmentCreated(configured)
      directories(prepared).toSet shouldBe Set(own.path)

      plugin.afterEnvironmentDestroyed(prepared)
      own.exists shouldBe true
    }
  }
}
