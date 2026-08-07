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

<% if yandex-image-id|not-empty %># The image is pinned in desired state, so the server runs a known image and
# an upstream release cannot move it. image_id forces replacement on the boot
# disk, so moving this pin deliberately plans one.
<% else %># Resolved from the family, which is whatever Yandex has published most
# recently. Convenient for a first create; see the lifecycle block below for
# why the resolved id is then left alone.
data "yandex_compute_image" "ubuntu" {
  family = "<{ yandex-image-family }>"
}
<% endif %>
resource "yandex_vpc_network" "network" {
  name = "<{ yandex-name }>"
}

resource "yandex_vpc_subnet" "subnet" {
  name           = "<{ yandex-name }>"
  zone           = "<{ yandex-zone }>"
  network_id     = yandex_vpc_network.network.id
  v4_cidr_blocks = ["<{ yandex-subnet-cidr }>"]
}

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
<% if yandex-image-id|not-empty %>      image_id = "<{ yandex-image-id }>"
<% else %>      image_id = data.yandex_compute_image.ubuntu.id
<% endif %>      size     = <{ yandex-disk-size-gb }>
    }
  }

  network_interface {
    subnet_id = yandex_vpc_subnet.subnet.id
    nat       = true
  }

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
<% if yandex-image-id|not-empty %>    # No ignore_changes here on purpose: with the image pinned, changing
    # yandex-image-id *should* plan a replacement, and prevent_destroy makes
    # that a deliberate decision rather than a surprise.
<% else %>    # A boot disk's image is immutable, so tracking a family plans a
    # replacement of the whole server every time Yandex publishes a release —
    # and with prevent_destroy set the plan fails rather than warns, blocking
    # unrelated deploys. Keep the disk; update the OS in place. Pin
    # yandex-image-id to state which image the server runs.
    ignore_changes = [boot_disk[0].initialize_params[0].image_id]
<% endif %>  }
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
