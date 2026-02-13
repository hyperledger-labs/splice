package org.lfdecentralizedtrust.splice.util

import org.scalatest.Tag

object Tags {
  // Don't run this test when testing against older Daml versions.
  object NoDamlCompatibilityCheck
      extends Tag("org.lfdecentralizedtrust.splice.util.scalatesttags.NoDamlCompatibilityCheck")
  // Don't run this test when testing against splice-amulet < 0.1.9
  object SpliceAmulet_0_1_9
      extends Tag("org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_9")
  // Don't run this test when testing against splice-amulet < 0.1.14
  object SpliceAmulet_0_1_14
      extends Tag("org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_14")
}
