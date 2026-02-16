# VM configuration
cpus   = 4 # Cores
memory = 8 # GiB
ipaddr = "192.168.56.10"

nix_cache_size = 20 # GiB
nix_cache_image_path = "/vagrant/vagrant-nix-cache/nix-cache.img"
nix_cache_mount_path = "/nix-cache"

shell_variables = <<~"SHELL"
  SPLICE_ROOT="/vagrant"
  NIX_CACHE_SIZE="#{nix_cache_size}"
  NIX_CACHE_IMAGE_PATH="#{nix_cache_image_path}"
  NIX_CACHE_MOUNT_PATH="#{nix_cache_mount_path}"
SHELL

shell_common = <<~'SHELL'
  set -euo pipefail
  trap exit_message EXIT
SHELL

shell_helpers = <<~'SHELL'
  exit_message () {
    if [[ $? -ne 0 ]]; then
      echo -e "The provisioning has been interrupted. Please try again:\n\n  vagrant up --provision\n " >&2
    fi
  }

  append_line_to_file () {
    local line="$1"
    local file="$2"

    grep -qxF "$line" "$file" || echo "$line" >> "$file"
  }
SHELL

Vagrant.configure("2") do |config|
  config.vm.box = "bento/ubuntu-24.04"

  config.vm.network "private_network", ip: ipaddr

  config.vm.provider "virtualbox" do |vb|
    vb.cpus = cpus
    vb.memory = memory * 1024
    vb.customize ["storagectl", :id, "--name", "SATA Controller", "--hostiocache", "on"]
  end

  config.vm.provision "shell", name: "system", upload_path: "/tmp/vagrant-shell-system", reset: true do |s|
    s.inline = shell_variables + shell_common + shell_helpers + <<~'SHELL'
      apt-get update

      apt-get install -y \
        bash-completion \
        command-not-found \
        git \
        direnv \
        nix

      # Add the vagrant user to the nix-users group
      adduser vagrant nix-users

      # Create a file to be mounted and used as a back store for the Nix cache
      # The image is used to give Nix freedom to set ownership and permissions which is not possible with synced folders directly
      [[ -d "$NIX_CACHE_MOUNT_PATH" ]] || mkdir -p "$NIX_CACHE_MOUNT_PATH"
      [[ -d "$(dirname "$NIX_CACHE_IMAGE_PATH")" ]] || mkdir -p "$(dirname "$NIX_CACHE_IMAGE_PATH")"
      [[ -f "$NIX_CACHE_IMAGE_PATH" ]] || { truncate -s "${NIX_CACHE_SIZE}G" "$NIX_CACHE_IMAGE_PATH"; mkfs.ext4 "$NIX_CACHE_IMAGE_PATH"; }
      nix_cache_image_fstab="$NIX_CACHE_IMAGE_PATH $NIX_CACHE_MOUNT_PATH ext4 loop 0 0"
      append_line_to_file "$nix_cache_image_fstab" /etc/fstab
      nix_daemon_needs_restart=false
      mountpoint "$NIX_CACHE_MOUNT_PATH" || { mount "$NIX_CACHE_MOUNT_PATH" && nix_daemon_needs_restart=true; }

      # Bind mounts for /nix/store and /nix/var/nix/db
      mkdir -p "$NIX_CACHE_MOUNT_PATH/store" "$NIX_CACHE_MOUNT_PATH/var/nix/db"
      mkdir -p /nix/store /nix/var/nix/db
      nix_store_fstab="$NIX_CACHE_MOUNT_PATH/store /nix/store none bind 0 0"
      nix_db_fstab="$NIX_CACHE_MOUNT_PATH/var/nix/db /nix/var/nix/db none bind 0 0"
      append_line_to_file "$nix_store_fstab" /etc/fstab
      append_line_to_file "$nix_db_fstab" /etc/fstab
      mount -a

      # Restart the Nix Daemon if needed
      if "$nix_daemon_needs_restart"; then systemctl restart nix-daemon.service; fi
    SHELL
  end

  config.vm.provision "shell", name: "user", upload_path: "/tmp/vagrant-shell-user", privileged: false do |s|
    s.inline = shell_variables + shell_common + shell_helpers + <<~'SHELL'
      # Configure nix
      mkdir -p ~/.config/nix/
      echo "extra-experimental-features = nix-command flakes" > ~/.config/nix/nix.conf

      # Enable direnv
      direnv_bash_hook='eval "$(direnv hook bash)"'
      append_line_to_file "$direnv_bash_hook" ~/.bashrc

      # Change to the splice directory and allow direnv
      [[ -d "$HOME/splice" ]] || ln -s "$SPLICE_ROOT" "$HOME/splice"
      cd "$SPLICE_ROOT"
      direnv allow

      # Execute direnv export to set up the environment
      eval "$(direnv export bash)"

      # Enable pre-commit hooks for Git
      pre-commit install
    SHELL
  end
end
