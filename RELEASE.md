
### Release process

The file VERSION in the repo root defines the current base version. By default, all commits are
versioned `<VERSION>-<suffix>` where `<suffix>` is a function of the current commit and/or the
local username (see `build-tools/get-snapshot-version` for details).

Commits with a message starting with `[release]` exclude the `-<suffix>` part and are hence
considered stable releases.

In light of the above, the process for cutting a new release is:

1. Create a new branch, `release-line-X.Y.Z`
    1. Ensure that the `dar` files in `daml/dars` are updated:
       1. Run `sbt cleanCnDars` to ensure a clean environment.
       2. Run `sbt damlBuild`, this will generate the dar files in `daml/dars/APP/dist/NAME-VERSION.dar`.
       3. Move all the created `dar`s to `daml/dars`. Note that the `APP-current.dar` shouldn't be moved.
          You can do so by running `find daml -name "*.dar" -not -path "*daml/dars/*" -not -path "*current.dar" -not -path "*test*.dar" -exec cp -t daml/dars {} +` in the project root.
    2. Update the file `src/main/scala/com/daml/network/environment/DarResources.scala` to include any new `dar`s in their corresponding `PackageResource.others`.
       For example, if there's a new `splice-amulet-1.2.3.dar`, you should include it:
       ```scala
       object DarResources {
         val amulet_0_1_0 = DarResource("splice-amulet-0.1.0.dar")
         val amulet_1_2_3 = DarResource("splice-amulet-1.2.3.dar")
         val amulet_current = DarResource("splice-amulet-current.dar")
         val amulet = PackageResource(
           amulet_current,
           Seq(
             amulet_0_1_0,
             amulet_1_2_3,
           ),
         )
         // [...]
       }
       ```
    3. Create a PR for that branch with whatever changes are required from main (e.g. updated to release notes).
       1. Also confirm that the version in `${REPO_ROOT}/VERSION` in that branch is the release you intend to publish.
    4. Run a preflight test on scratchnet in that PR.
    5. Note that all commits to any branch named `release-line.*` go through CI, similarly to commits to main.
       However, they do not get tested on a cluster, hence step 2 is crucial for testing cluster deployments.
    6. To publish the latest release on the release-line branch for external use by partners:
       1. Navigate to the CircleCI dashboard for the release-line branch.
       2. Click on "Trigger Pipeline"
       3. Add a parameter named `run-job`, with argument `publish-public-artifacts`.
    7. Once the snapshot release is produced and pushed, the version is ready for complete testing, e.g.
       deployment on DevNet with external partners.
    8. In order to finalize a release with a non-snapshot label, create another PR for the `release-line-X.Y.Z`
       branch with a commit message starting with the string `[release]`.
    9. Repeat step 4 to publish the official release externally.
2. Create a PR for main that bumps `${REPO_ROOT}/VERSION` to the *next* planned release
   (typically this will bump the minor version), and `${REPO_ROOT}/LATEST_RELEASE` to the
   version of the newly created release line
