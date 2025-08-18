# Vagrantfile for Splice development environment

This Vagrantfile sets up a local development environment for Splice by creating
a virtual machine with Nix, direnv and a few other tools pre-installed.

## Requirements

- [Vagrant](https://www.vagrantup.com/downloads)
- [VirtualBox](https://www.virtualbox.org/wiki/Downloads)

## Setup

```shell
vagrant up
```

## Use

```shell
vagrant ssh
cd splice
```

## Clean up

```shell
vagrant destroy

# Optionally, remove the Nix cache to free up space, this will make the next
# `vagrant up` slower as it will need to re-download all Nix packages.
rm -r vagrant-nix-cache
```

## Notes

- Only Ubuntu 24.04 is tested as a host OS however other Linux distributions
  and Intel-based macOS should work as well.
- Vagrant creates the `vagrant-nix-cache` directory with `nix-cache.img` file
  which is shared with the VM. This file is used to store /nix/cache and
  /nix/var/nix/db for faster builds and installations of Nix packages. The
  current size of the image is 20GB (it can be changed in the
  [Vagrantfile](Vagrantfile)).
- IP of the VM is set to `192.168.56.10` (it can be changed in the
  [Vagrantfile](Vagrantfile)).
- The VM host can accessed using nip.io domain names:
  - 192.168.56.10.nip.io
  - 192-168-56-10.nip.io
  - myapp.192-168-56-10.nip.io
