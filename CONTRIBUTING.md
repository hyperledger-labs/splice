# Table of Contents

- [Contributing to the Splice repository](#contributing-to-the-splice-repository)
  - [Testing](#testing)
  - [Branch Naming](#branch-naming)
  - [TODO Comments](#todo-comments)
  - [DB Migrations](#db-migrations)
  - [Daml Changes](#daml-changes)
    - [Approval for Daml Changes](#approval-for-daml-changes)
    - [Daml Lock Files](#daml-lock-files)
    - [Backwards-compatible Daml changes](#backwards-compatible-daml-changes)
    - [Daml Numerics](#daml-numerics)
  - [Message Definitions](#message-definitions)
  - [Config Parameters](#config-parameters)
  - [Code Layout](#code-layout)
  - [Domain Specific Naming](#domain-specific-naming)
  - [Frontend Code](#frontend-code)
    - [Background](#background)
    - [New Packages](#new-packages)
    - [Common Libs](#common-libs)
  - [Conversions between Java & Scala types](#conversions-between-java--scala-types)
  - [Porting between branches](#porting-between-branches)
  - [Dev Docs](#dev-docs)


# Contributing to the Splice repository

In order to setup your development environment, please see the [Development README](DEVELOPMENT.md).

TBD: futher contribution guidelines, s.a. [Canton's Contributing Guide](https://github.com/DACH-NY/canton/blob/main/contributing/README.md)

## Testing

Every contribution must be tested in an automated test! For further details see the [Testing README](TESTING.md).

## Branch Naming

If you are a Splice Contributor and therefore have write permissions to the Splice repo directly,
please prefix branch names by your name, followed by a slash and a descriptive name:

`<yourname>/<descriptivename>`

For example, if Bob is working on issue 4242 to "fix FooTest", he could name his branch:

`bob/fix-footest/4242`.

## TODO Comments

TBD

## DB Migrations

Refer to [the main README on migrations](apps/common/src/main/resources/db/migration/README.md).

## Daml Changes

### Approval for Daml Changes

Adopting Daml changes on a prod system requires a vote from a majority of SVs. Therefore, before
proposing any Daml changes, please reach out to the [Splice Maintainers](MAINTAINERS.md) to discuss
your proposal and the best way to secure support for your changes first.

### Daml Lock Files

To prevent accidental changes to dar files, we commit their current package IDs with the repo,
in daml/dars.lock. CI verifies that those package IDs are correct. If you intentionally make
changes in daml code, please run `sbt damlDarsLockFileUpdate` and commit the updated `dars.lock`
file along with your dar changes.

### Backwards-compatible Daml changes

We require all Daml changes to be backwards-compatible. See the [Upgrading and Extending Daml Applications
section of the Canton docs](https://docs.digitalasset.com/build/3.3/sdlc-howtos/smart-contracts/upgrade/).

In the early days of Daml 3.0 upgrading of variants and enums was not supported, which is why there
are variant constructors with names like `ExtFoo` in our codebase. They used to be a workaround for
this lack of upgradeability. You can ignore them, and just add new enum and variant constructors directly.

Care still must be taken to not accidentally change an enum into a variant: enums are `data` type declarations
that only consist of nullary constructors. They are compiled to Daml-LF enums, which is nice as that
ensures that the codegens like the Java codegen define these as Jave enums as well. **Make sure to only add
further nullary constructors to types that only have nullary constructors.**


### Daml Numerics

To represent Daml `Numeric`s for any user facing APIs (console commands), we use `scala.math.BigDecimal`s.
We use Scala BigDecimals instead of Java BigDecimals (that are used in the Daml repo) because
integers, floats etc. are automatically converted
to Scala BigDecimals by the Scala compiler unlike Java BigDecimals
(`wallet.tap(10)` vs `wallet.tap(new java.math.BigDecimal(10))`).

To represent Daml Numerics in Protobuf we use `string`s (there is no Protobuf BigDecimal type). Conversions to
and from `string`s should occur via `org.lfdecentralizedtrust.splice.util.Proto.encode/tryDecode`.

When interacting with the Ledger API, we convert the Scala BigDecimals to Java BigDecimals.

Overall, please refer to the `wallet.tap` command implementation for the canonical handling of Daml Numerics.

## Message Definitions

* All Protobuf definitions should be using [`proto3`](https://developers.google.com/protocol-buffers/docs/proto3)
* Avoid wrapping primitive types in a message structure unless future extensibility will likely be required
* Use a plural name for `repeated` fields
* Use `string` fields with a suffix `contract_id` to store contract ids
* Use `string` fields with a suffix `party_id` to store party ids

## Config Parameters

* name flags as `enableXXX` instead of `disableXXX` to avoid a double negation

## Code Layout

* Place `.proto` files in `src/main/protobuf`
* Prefer having a single `.proto` definition per service.
* Refer to generated Protobuf classes with a package prefix, e.g., `v0.MyMessage` instead of `MyMessage`.
  This avoids name conflicts with our hand-written classes.

## Domain Specific Naming

* Use `listXXX`, `acceptXXX`, `rejectXXX`, `withdrawXXX` for managing proposals, requests etc.
* [Beware of the differences](https://www.bkacontent.com/gs-commonly-confused-words-amount-number-and-quantity)
  between `amount`, `quantity` and `number`: To keep things simple across the repository,
  we consistently use only the term **`amount`**
* Between `sender`/`receiver` and `payer`/`payee`: please use **`sender`/`receiver`**

## Frontend Code

### Background

This section discusses how to contribute, or add new frontend code for an app. To understand how to run frontends locally, see [Building and Running the Wallet and Splitwell Apps](#building-and-running-the-wallet-and-splitwell-apps).

Frontend code projects are managed via [`npm workspaces`](https://docs.npmjs.com/cli/v8/using-npm/workspaces). This gives us a way to manage multiple distinct NPM packages all co-located in the same monorepo, and confers several benefits:

- One local monorepo package can be installed as a dependency of another, enabling "easy" code-sharing.
- With `npm install`, all dependencies of all workspace projects are installed in the root `node_modules` folders, giving us de-deduplication.
- If all workspace projects share common scripts, you can easily run that script across all workspaces in one command.
- All required `npm` commands are issued from `sbt compile`, so there should not be a need to run e.g. `npm install` directly.

### New Packages

In this section only, the term "root-level directory" will describe the workspace root, which is inside `apps/` (**not** the _repo_ root directory).

If you want to add a new package to the workspace, first register its directory in the root-level `apps/package.json` workspaces key. The directory referenced here must contain a `package.json` of its own defining the workspace package itself -- name, dependencies, etc.

Then add the new package to `build.sbt` following the examples from the existing frontend packages.

Running `sbt compile` (or manually `npm install` from the root) installs the dependencies of all registered workspace packages.

Make sure your package contains at least the scripts `build`, `fix`, `check`, and `start`. This enables the use of (e.g.) `npm run build --workspaces` to run the build script for all packages in the workspace at once, as well as proper integration with `sbt`.

Your new package will need its own `tsconfig.json` file that inherits from the root tsconfig. See any existing workspace package for an example.

### Common libs

In `apps/common/frontend` we have an NPM package containing common code. This package (named `common-frontend`) can be installed with `npm install @lfdecentralizedtrust/splice-common-frontend -w my-workspace-pkg`. You can import anything from it with `import { ... } from '@lfdecentralizedtrust/splice-common-frontend'` in your package's source code.

You're also free to add more things in `common-frontend` to use across multiple frontend apps. This can really include anything: utility functions, reusable React components, shared config, etc. Just ensure whatever you add is exposed via the lib's entrypoint, `index.ts` (we use the [barreling](https://basarat.gitbook.io/typescript/main-1/barrel) technique to expose all modules from the root of the library).

## Conversions between Java & Scala types

Because we use the Java bindings and codegen, we need to convert
between Java and Scala types, e.g., `Seq` and `java.util.List`.  We
try to use Scala types whereever possible so we delay the conversion
to Java types until the last possible point and convert from Scala to
Java as early as possible.

To convert, import `scala.jdk.CollectionConverters.*`. You can then use `asScala` and `asJava` methods.

## Porting between branches

Note that we often maintain multiple branches in parallel, e.g. ones for the release-lines
that are deployed on Prod clusters, or ones for previous or coming major releases. It is
therefore quite common that we need to port commits between the different branches.

This can of course be done manually using `git` commands and github UI, but we also have
automation to support it:

Automatically on PRs:
- On every PR that satisfies a set of conditions, you will automatically get a reminder
  with a list of suggested branches you may wish to port this PR to. Check the boxes of
  those that fit (you can also edit the comment and add other branches if needed).
- Once the PR is merged, automation (in GitHub Actions) will pick up that reminder comment
  and attempt to port this commit to the selected branches. If successful, you will be
  asked to review the port PRs. Upon failure, a comment will be added to the original PR.
- Note that on any unmerged PR, you can add a comment yourself that includes the string
  `[backport]`, and any checked check box in that comment will be assumed to be a branch
  to which you wish to port this PR, e.g. a comment:

  [backport]

  \- [x] my-branch

  will cause your PR to be ported to the "my-branch" branch once merged.

Manually:
- There is also a manually triggered workflow for porting PRs that have already been merged.
  To use that, navigate to the ["Backport a commit or PR across branches" workflow in the repo's Actions page](https://github.com/DACH-NY/canton-network-node/actions/workflows/backport.yml),
  and press "Run workflow". You will be asked for a merged PR or a Git commit hash to port from,
  the branch to port to, and the reviewer to request the review from. Run the workflow to create
  a PR to port your contributions.

## Dev Docs

We publish docs from each commit from `main` to
https://digital-asset.github.io/decentralized-canton-sync/. This can
often be useful to answer support requests with a docs link even if
those docs are still very recent.
