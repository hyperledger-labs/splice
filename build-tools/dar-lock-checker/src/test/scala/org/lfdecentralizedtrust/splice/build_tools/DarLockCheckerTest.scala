// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.build_tools

import com.digitalasset.daml.lf.data.Ref.{PackageName, PackageVersion}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class DarLockCheckerTest extends AnyWordSpec with Matchers {

  private def pkg(name: String): PackageName = PackageName.assertFromString(name)
  private def ver(v: String): PackageVersion = PackageVersion.assertFromString(v)
  private def key(name: String, v: String) = (pkg(name), ver(v))

  "detectBumps" should {
    "return empty when branch and compare base match exactly" in {
      val branch = Map(key("splice-amulet", "0.1.18") -> "hashA")
      val compare = Map(key("splice-amulet", "0.1.18") -> "hashA")
      DarLockChecker.detectBumps(branch, compare) shouldBe empty
    }

    "detect a hash mismatch and bump to compare base's max + patch" in {
      val branch = Map(key("splice-amulet", "0.1.18") -> "branchHash")
      val compare = Map(
        key("splice-amulet", "0.1.17") -> "compareHash17",
        key("splice-amulet", "0.1.18") -> "compareHash18",
      )
      DarLockChecker.detectBumps(branch, compare) shouldBe Seq(
        DarLockChecker.BumpTarget(pkg("splice-amulet"), ver("0.1.18"), ver("0.1.19"))
      )
    }

    "ignore branch-only versions not present in compare base" in {
      val branch = Map(key("splice-amulet", "0.1.20") -> "branchHash")
      val compare = Map(key("splice-amulet", "0.1.18") -> "compareHash18")
      DarLockChecker.detectBumps(branch, compare) shouldBe empty
    }

    "pick the maximum version in compare base even if branch is behind it" in {
      val branch = Map(key("splice-amulet", "0.1.15") -> "branchHash")
      val compare = Map(
        key("splice-amulet", "0.1.15") -> "compareHashOther",
        key("splice-amulet", "0.1.19") -> "compareHash19",
      )
      DarLockChecker.detectBumps(branch, compare) shouldBe Seq(
        DarLockChecker.BumpTarget(pkg("splice-amulet"), ver("0.1.15"), ver("0.1.20"))
      )
    }

    "handle multiple bumped packages deterministically sorted by name" in {
      val branch = Map(
        key("splice-wallet", "0.1.18") -> "branchWallet",
        key("splice-amulet", "0.1.18") -> "branchAmulet",
      )
      val compare = Map(
        key("splice-wallet", "0.1.18") -> "compareWallet",
        key("splice-amulet", "0.1.18") -> "compareAmulet",
      )
      DarLockChecker.detectBumps(branch, compare).map(_.name.toString) shouldBe
        Seq("splice-amulet", "splice-wallet")
    }
  }

  "rewriteDamlYamlVersion" should {
    "replace the top-level version line" in {
      val yaml =
        """sdk-version: 3.4.11
          |name: splice-amulet
          |source: daml
          |version: 0.1.18
          |dependencies:
          |  - daml-prim
          |""".stripMargin
      val expected =
        """sdk-version: 3.4.11
          |name: splice-amulet
          |source: daml
          |version: 0.1.19
          |dependencies:
          |  - daml-prim
          |""".stripMargin
      DarLockChecker.rewriteDamlYamlVersion(yaml, ver("0.1.19")) shouldBe expected
    }

    "leave version-like strings inside paths untouched" in {
      val yaml =
        """name: splice-amulet
          |version: 0.1.18
          |data-dependencies:
          |  - ../dars/splice-api-token-metadata-v1-1.0.0.dar
          |""".stripMargin
      val result = DarLockChecker.rewriteDamlYamlVersion(yaml, ver("0.1.19"))
      result should include("version: 0.1.19")
      result should include("../dars/splice-api-token-metadata-v1-1.0.0.dar")
    }
  }

  "rewritePackageJson" should {
    "bump the version suffix on a @daml.js/ entry without touching others" in {
      val json =
        """{
          |  "dependencies": {
          |    "@daml.js/splice-amulet": "file:common/frontend/daml.js/splice-amulet-0.1.18",
          |    "@daml.js/splice-wallet": "file:common/frontend/daml.js/splice-wallet-0.1.19"
          |  }
          |}""".stripMargin
      val expected =
        """{
          |  "dependencies": {
          |    "@daml.js/splice-amulet": "file:common/frontend/daml.js/splice-amulet-0.1.19",
          |    "@daml.js/splice-wallet": "file:common/frontend/daml.js/splice-wallet-0.1.19"
          |  }
          |}""".stripMargin
      DarLockChecker.rewritePackageJson(json, pkg("splice-amulet"), ver("0.1.19")) shouldBe expected
    }

    "leave the file unchanged when the package is not referenced" in {
      val json =
        """{
          |  "dependencies": {
          |    "@daml.js/splice-wallet": "file:common/frontend/daml.js/splice-wallet-0.1.18"
          |  }
          |}""".stripMargin
      DarLockChecker.rewritePackageJson(json, pkg("splice-amulet"), ver("0.1.19")) shouldBe json
    }

    "match entries whose alias differs from the package name" in {
      val json =
        """{
          |  "dependencies": {
          |    "@daml.js/ans": "file:common/frontend/daml.js/splice-amulet-name-service-0.1.19"
          |  }
          |}""".stripMargin
      val expected =
        """{
          |  "dependencies": {
          |    "@daml.js/ans": "file:common/frontend/daml.js/splice-amulet-name-service-0.1.20"
          |  }
          |}""".stripMargin
      DarLockChecker.rewritePackageJson(
        json,
        pkg("splice-amulet-name-service"),
        ver("0.1.20"),
      ) shouldBe expected
    }
  }
}
