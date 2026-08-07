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
}

locals {
  zones = toset(<{ zones-hcl|safe }>)
}

# Unlike Cloudflare, where every zone must already exist in the account, the
# public zones are created here: Yandex serves every public zone from the same
# nameservers (ns1.yandexcloud.net, ns2.yandexcloud.net), so delegation is a
# one-time NS change at the registrar and the zone itself carries no further
# configuration. The "zone-" prefix keeps the resource name valid for domains
# that start with a digit.
resource "yandex_dns_zone" "domains" {
  for_each = local.zones

  name   = "zone-${replace(each.value, ".", "-")}"
  zone   = "${each.value}."
  public = true
}

# The A records live in apps.tf.json: one per application host, generated from
# the desired-state application list. smtp.tf.json holds each sending domain's
# records. Both select the matching yandex_dns_zone.domains entry.
