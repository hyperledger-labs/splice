# canton-coin

## Setup

1. Install [direnv](https://direnv.net/#basic-installation).
2. Install Nix by running: `bash <(curl -sSfL https://nixos.org/nix/install)`
3. After switching to the CC repo you should see a line like
```
direnv: error /home/moritz/daml-projects/canton-coin/.envrc is blocked. Run `direnv allow` to approve its content
```
4. Run `direnv allow`. You should see a bunch of output including `direnv: using nix`.

**Important:** start your IDE and other development tools from a console that
has this `direnv` loaded; and thus has the proper version of all the
project dependencies on its `PATH`.

If you encounter issues, try exiting & reentering the directory to reactivate direnv.

## Directory layout

See the `README.md` files in the top-level directories for details.

Most top-level directories logically correspond to independent repositories,
which are currently maintained within this one repository for increased
delivery velocity using head-based development.

We expect to start splitting off repositories as part of the work to deliver
M3 - TestNet Launch.

## IntelliJ setup

* Clone the repository first, if you haven't yet
* Run `which java` and `which sbt` within `the-real-canton-coin` directory to respectively find the JRE/SDK 
    and sbt versions used by nix. The outputs should roughly look as follows: 
    ```
    the-real-canton-coin$ which java
    /nix/store/rqii97havwmrzan6wk1lbh5nc48w821y-openjdk-11.0.15+10/bin/java
    the-real-canton-coin$ which sbt
    /nix/store/9q28hwzz8yy75l317k2v2mdq485hgja0-sbt-1.6.2/bin/sbt
    ```
* Add the Java SDK from nix per the instructions [in the Daml repo.](https://github.com/digital-asset/daml/blob/main/BAZEL.md#configuring-the-jdk-in-intellij)
 In the example above, the JDK would be at `/nix/store/rqii97havwmrzan6wk1lbh5nc48w821y-openjdk-11.0.15+10/lib/openjdk`.
* Open the repository via 'File -> New -> Project from existing sources' 
* 'Import project from external model' and select sbt
* Point IntelliJ to the JRE home and sbt-launch jar. From the example above, these should respectively be
        `/nix/store/rqii97havwmrzan6wk1lbh5nc48w821y-openjdk-11.0.15+10/` and 
        `/nix/store/9q28hwzz8yy75l317k2v2mdq485hgja0-sbt-1.6.2/share/sbt/bin/sbt-launch.jar`.
* Also configure it with the JDK you added above. If the JDK doesn't show up as one of the options, you may need to 
    choose any other SDK and set up the correct SDK for the project after following the rest of the steps. 
    In that case, you will need to verify that all usages of the JDK in 'Settings' and 'Project Structure' use the correct
    SDK.
* Otherwise choose [these settings in the dialogue](https://i.imgur.com/B3yWCZ9.png) (see sbt explanations [here](https://www.jetbrains.com/help/idea/sbt.html))

You should then see a 'sbt shell' window in IntelliJ that allows you to build and test the Scala code while using the 
same package references as nix. If IntelliJ asks you at the end if you want to overwrite any previous `.idea/*` files, say yes.  