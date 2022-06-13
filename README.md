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
