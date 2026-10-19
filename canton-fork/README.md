# Canton Hard Forks

Files in `../canton/` are tracked with merged changes, as explained by [maintenance docs][].
This directory contains hard forks from that tree where we no longer wish to track upstream changes.

[maintenance docs]: ../MAINTENANCE.md#bumping-our-canton-fork

## Modules

`app-base`::
  Splice additions to `canton/community/app-base`, such as access classes for console and
  admin API client internals, and the `ImportAcsBytes` admin command.
`base`::
  Splice additions to `canton/community/base`, such as access classes for config, crypto and
  protocol internals, and extension methods for `CantonTimestamp` and `ErrorUtil`.
`common`::
  Splice additions to `canton/community/common`, such as access classes for topology admin
  internals.
`testing`::
  Splice additions to `canton/community/testing`: `BaseTestExtensions`, which Splice's
  `TestCommon` mixes in.

Additions that only use Canton's public API go in the `org.lfdecentralizedtrust.splice`
package with the same suffix as the Canton package they extend
(e.g. `com.digitalasset.canton.data` becomes `org.lfdecentralizedtrust.splice.data`).
Only access classes need to be in Canton's own packages.

## Access classes

When Splice needs a Canton member that is not visible outside Canton (`private[canton]`,
`protected[console]` and so on), don't widen its visibility in `../canton/`. Add an access
class instead:

```scala
package com.digitalasset.canton.crypto

object SigningPublicKeyAccess {
  implicit class `SigningPublicKey access`(private val self: SigningPublicKey) extends AnyVal {
    def key: ByteString = self.key
  }
}
```

* Put it in the module above that matches the Canton project defining the member, in the
  member's package, so that it can see package-qualified members.
* Name the object `XAccess` and the class `` `X access` ``, where `X` is the type that owns
  the member. For members of a companion object, wrap `X.type`
  (e.g. `` `CantonConfig access`(self: CantonConfig.type) ``).
* Callers import `XAccess.*` and otherwise stay unchanged. Scala only tries implicit
  conversions for a member that is not accessible, so `publicKey.key` resolves to the access
  class in Splice and to the original member within Canton.
* If the member is fully `private`, the class can't call it; implement the same behavior with
  public API instead (e.g. `SigningKeyUsageAccess`, `CantonConfigAccess`).
* A member that only mirrors a parent's widening (e.g. an override) uses the parent's class.
  Delete access classes that are no longer used.

This doesn't work for members that Splice overrides, for constructors, or for protected
members; those changes stay in `../canton/` and are listed in `../CANTON_CODE_CHANGES.md`.
