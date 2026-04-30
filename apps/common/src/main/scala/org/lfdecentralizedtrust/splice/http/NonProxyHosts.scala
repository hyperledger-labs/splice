// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.http

import java.util.Locale

/** Java-compatible `nonProxyHosts` matcher.
  *
  * Semantics follow the JVM convention used by `sun.net.spi.DefaultProxySelector`:
  *   - The source string is a `|`-separated list of patterns.
  *   - Each pattern matches the raw request host (no DNS resolution) case-insensitively.
  *   - `*` is a wildcard matching any sequence of characters (zero or more).
  *   - Conventionally wildcards are only at the start or end of a pattern, but
  *     any position is legal (converted to regex `.*`).
  *   - Empty / whitespace-only patterns are ignored.
  *   - An empty property value means "no bypass patterns" (not an error).
  *
  * Only the standard JDK property `http.nonProxyHosts` is read.
  *
  * Matching is performed on the raw host string from the request URI. No DNS
  * resolution is performed — this avoids surprising behavior and keeps the
  * check fast on the connection-establishment path.
  */
final class NonProxyHosts private (patterns: Seq[NonProxyHosts.Pattern]) {
  def isEmpty: Boolean = patterns.isEmpty
  def nonEmpty: Boolean = patterns.nonEmpty

  /** @return true if `host` matches any configured non-proxy pattern and
    *         should therefore bypass the proxy.
    */
  def matches(host: String): Boolean = {
    if (patterns.isEmpty) false
    else {
      val lc = host.toLowerCase(Locale.ROOT)
      patterns.exists(_.matches(lc))
    }
  }
}

object NonProxyHosts {

  val empty: NonProxyHosts = new NonProxyHosts(Seq.empty)

  /** Parse a `|`-separated list of nonProxyHosts patterns. An empty or
    * whitespace-only input yields [[empty]].
    */
  def parse(raw: String): NonProxyHosts = {
    if (raw == null) empty
    else {
      val tokens = raw.split('|').iterator.map(_.trim).filter(_.nonEmpty).toVector
      if (tokens.isEmpty) empty else new NonProxyHosts(tokens.map(Pattern.compile))
    }
  }

  /** Read the configured nonProxyHosts from the JDK-standard system property
    * `http.nonProxyHosts`. If it is unset, no bypass patterns are configured.
    * An explicitly empty value also means "no bypass patterns".
    */
  def readFromSystemProperties(): NonProxyHosts =
    parse(Option(System.getProperty("http.nonProxyHosts")).getOrElse(""))

  private final class Pattern(kind: Pattern.Kind, needle: String) {
    def matches(lcHost: String): Boolean = kind match {
      case Pattern.Exact => lcHost == needle
      case Pattern.Prefix => lcHost.startsWith(needle)
      case Pattern.Suffix => lcHost.endsWith(needle)
      case Pattern.Contains => lcHost.contains(needle)
      case Pattern.Regex => needle.r.pattern.matcher(lcHost).matches()
    }
  }

  private object Pattern {
    private sealed trait Kind
    private case object Exact extends Kind
    private case object Prefix extends Kind
    private case object Suffix extends Kind
    private case object Contains extends Kind
    private case object Regex extends Kind

    def compile(token: String): Pattern = {
      val lc = token.toLowerCase(Locale.ROOT)
      val starCount = lc.count(_ == '*')
      if (starCount == 0) {
        new Pattern(Exact, lc)
      } else if (starCount == 1) {
        if (lc.startsWith("*")) new Pattern(Suffix, lc.drop(1))
        else if (lc.endsWith("*")) new Pattern(Prefix, lc.dropRight(1))
        else new Pattern(Regex, globToRegex(lc))
      } else if (starCount == 2 && lc.startsWith("*") && lc.endsWith("*")) {
        new Pattern(Contains, lc.substring(1, lc.length - 1))
      } else {
        new Pattern(Regex, globToRegex(lc))
      }
    }

    /** Convert a glob-style nonProxyHosts pattern to an anchored regex.
      * Only `*` is treated as a wildcard; every other character is quoted.
      */
    private def globToRegex(glob: String): String =
      glob.iterator
        .map(c => if (c == '*') ".*" else java.util.regex.Pattern.quote(c.toString))
        .mkString
  }
}
