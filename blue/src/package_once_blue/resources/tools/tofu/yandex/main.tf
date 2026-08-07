# Tell terraform to use the provider and select a version.
terraform {
  required_providers {
    yandex = {
      source  = "yandex-cloud/yandex"
      version = ">= 0.120"
    }
  }
}

provider "yandex" {
  # token comes from YC_TOKEN in the environment
  cloud_id  = "<{ yandex-cloud-id }>"
  folder_id = "<{ yandex-folder-id }>"
  zone      = "<{ yandex-zone }>"
}

data "yandex_compute_image" "ubuntu" {
  family = "<{ yandex-image-family }>"
}

resource "yandex_vpc_network" "network" {
  name = "<{ yandex-name }>"
}

resource "yandex_vpc_subnet" "subnet" {
  name           = "<{ yandex-name }>"
  zone           = "<{ yandex-zone }>"
  network_id     = yandex_vpc_network.network.id
  v4_cidr_blocks = ["<{ yandex-subnet-cidr }>"]
}
<% if yandex-static-ip %>
# Yandex assigns an ephemeral public address when NAT is enabled, and releases
# it whenever the instance stops — a restart comes back on a different IP. A
# reserved address survives stop/start, so DNS records and certificates bound
# to the host stay valid.
resource "yandex_vpc_address" "addr" {
  name = "<{ yandex-name }>"

  external_ipv4_address {
    zone_id = "<{ yandex-zone }>"
  }
}
<% endif %>
resource "yandex_compute_instance" "node1" {
  name        = "<{ yandex-name }>"
  platform_id = "<{ yandex-platform-id }>"
  zone        = "<{ yandex-zone }>"

  resources {
    cores         = <{ yandex-cores }>
    memory        = <{ yandex-memory-gb }>
    core_fraction = <{ yandex-core-fraction }>
  }

  boot_disk {
    initialize_params {
      image_id = data.yandex_compute_image.ubuntu.id
      size     = <{ yandex-disk-size-gb }>
    }
  }

  network_interface {
    subnet_id = yandex_vpc_subnet.subnet.id
    nat       = true
<% if yandex-static-ip %>    nat_ip_address = yandex_vpc_address.addr.external_ipv4_address[0].address
<% endif %>  }
<% if yandex-allow-stopping-for-update %>
  # Yandex cannot change some attributes — the NAT address among them — while
  # the instance runs. Opt in so tofu may stop it briefly to apply such a
  # change instead of failing the apply.
  allow_stopping_for_update = true
<% endif %>
  # Yandex has no account-level SSH key registry: the user and its key are
  # created by cloud-init from instance metadata.
  metadata = {
    ssh-keys = "ubuntu:<{ compute-pubkey }>"
  }

  # Wait for ssh before starting Ansible
  connection {
    type = "ssh"
    user = "ubuntu"
    host = self.network_interface.0.nat_ip_address
  }
  provisioner "remote-exec" {
    inline = ["ls"]
  }
  lifecycle {
    prevent_destroy = <{ compute-prevent-destroy }>
  }
}

output "params" {
  value = {
    ip     = yandex_compute_instance.node1.network_interface.0.nat_ip_address
    sudoer = "ubuntu"
    uid    = "1000"
    name   = "<{ profile }>"
    user   = "ubuntu"
  }
}
