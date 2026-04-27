// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.http

import com.digitalasset.canton.BaseTest
import org.scalatest.wordspec.AnyWordSpec

class NonProxyHostsTest extends AnyWordSpec with BaseTest {

  "NonProxyHosts.parse" should {
    "treat an empty string as no patterns" in {
      NonProxyHosts.parse("").isEmpty shouldBe true
    }
    "treat whitespace-only tokens as no patterns" in {
      NonProxyHosts.parse(" | |  ").isEmpty shouldBe true
    }
    "treat null as no patterns" in {
      NonProxyHosts.parse(null).isEmpty shouldBe true
    }
  }

  "NonProxyHosts.matches" should {

    "match exact hostnames (case-insensitive)" in {
      val n = NonProxyHosts.parse("localhost|example.com")
      n.matches("localhost") shouldBe true
      n.matches("LOCALHOST") shouldBe true
      n.matches("example.com") shouldBe true
      n.matches("other.com") shouldBe false
    }

    "not match partial hostnames without wildcards" in {
      val n = NonProxyHosts.parse("example.com")
      n.matches("foo.example.com") shouldBe false
      n.matches("example.com.bad.com") shouldBe false
    }

    "match suffix wildcards like *.foo.com" in {
      val n = NonProxyHosts.parse("*.foo.com")
      n.matches("a.foo.com") shouldBe true
      n.matches("very.deep.foo.com") shouldBe true
      // Java convention: `*.foo.com` does NOT match bare `foo.com`. Our grammar
      // treats `*` as "any sequence of characters" including empty, so a leaf
      // `*` followed by `.foo.com` only matches if something precedes the dot.
      n.matches("foo.com") shouldBe false
      n.matches("barfoo.com") shouldBe false
    }

    "match prefix wildcards like foo.*" in {
      val n = NonProxyHosts.parse("foo.*")
      n.matches("foo.com") shouldBe true
      n.matches("foo.bar.baz") shouldBe true
      n.matches("foo") shouldBe false
      n.matches("other.foo.com") shouldBe false
    }

    "match contains wildcards like *foo*" in {
      val n = NonProxyHosts.parse("*foo*")
      n.matches("foo") shouldBe true
      n.matches("barfoo") shouldBe true
      n.matches("foobaz") shouldBe true
      n.matches("barfoobaz") shouldBe true
      n.matches("bar") shouldBe false
    }

    "match IP address literals exactly (no DNS, no CIDR)" in {
      val n = NonProxyHosts.parse("127.0.0.1|10.*")
      n.matches("127.0.0.1") shouldBe true
      n.matches("127.0.0.2") shouldBe false
      n.matches("10.0.0.1") shouldBe true
      n.matches("192.168.0.1") shouldBe false
    }

    "match pipe-separated lists with mixed patterns" in {
      val n = NonProxyHosts.parse("localhost|*.internal|10.*|example.com")
      n.matches("localhost") shouldBe true
      n.matches("svc.internal") shouldBe true
      n.matches("10.0.0.1") shouldBe true
      n.matches("example.com") shouldBe true
      n.matches("google.com") shouldBe false
    }

    "treat patterns with embedded wildcards as regex-like matches" in {
      // `a*b*c` should match strings with `a`, then anything, then `b`, then
      // anything, then `c`.
      val n = NonProxyHosts.parse("a*b*c")
      n.matches("abc") shouldBe true
      n.matches("aXbYc") shouldBe true
      n.matches("acb") shouldBe false
    }

    "ignore whitespace around tokens" in {
      val n = NonProxyHosts.parse(" localhost | *.internal ")
      n.matches("localhost") shouldBe true
      n.matches("svc.internal") shouldBe true
    }

    "return false when empty, for any host" in {
      NonProxyHosts.empty.matches("anything") shouldBe false
      NonProxyHosts.parse("").matches("localhost") shouldBe false
    }
  }
}
