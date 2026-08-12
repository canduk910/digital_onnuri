# NCP 인프라 프로비저닝 (VPC 플랫폼).
# DB는 별도 관리형이 아니라 서버(VM) 안 Postgres 컨테이너로 운영 → 여기선 서버·네트워크만 만든다.
# ⚠️ 이 구성은 사용자 계정 없이 검증하지 못했다 — apply 전 `terraform plan`으로 반드시 확인.
#    제품 코드는 리전/시점마다 달라 하드코딩 대신 data 소스로 조회한다.
terraform {
  required_providers {
    ncloud = {
      source  = "NaverCloudPlatform/ncloud"
      version = ">= 3.3.0"
    }
  }
}

provider "ncloud" {
  access_key  = var.ncloud_access_key # 콘솔 > 마이페이지 > 계정관리 > 인증키 관리
  secret_key  = var.ncloud_secret_key
  region      = var.region # 예: KR
  support_vpc = true
}

# ── 네트워크 ──
resource "ncloud_vpc" "main" {
  name            = "${var.name_prefix}-vpc"
  ipv4_cidr_block = "10.0.0.0/16"
}

resource "ncloud_subnet" "public" {
  vpc_no         = ncloud_vpc.main.id
  name           = "${var.name_prefix}-public"
  subnet         = "10.0.1.0/24"
  zone           = var.zone # 예: KR-2
  network_acl_no = ncloud_vpc.main.default_network_acl_no
  subnet_type    = "PUBLIC"
}

# ── SSH 키 ──
resource "ncloud_login_key" "key" {
  key_name = "${var.name_prefix}-key"
}

# ── 서버 이미지 조회(하드코딩 회피). 스펙 코드는 변수(server_spec_code)로. ──
# 스펙 후보는 `terraform console`에서 data.ncloud_server_specs 로 확인하거나 콘솔에서 조회.
data "ncloud_server_image_numbers" "ubuntu" {
  # Ubuntu 22.04 계열 필터. plan 시 image_number_list 확인 후 필요하면 조정.
  filter {
    name   = "name"
    values = ["ubuntu-22.04"]
    regex  = true
  }
}

# ── 방화벽(ACG): 80/443 전체, 22는 관리 IP만 ──
resource "ncloud_access_control_group" "app" {
  name   = "${var.name_prefix}-app-acg"
  vpc_no = ncloud_vpc.main.id
}

resource "ncloud_access_control_group_rule" "app_in" {
  access_control_group_no = ncloud_access_control_group.app.id

  inbound {
    protocol   = "TCP"
    ip_block   = "0.0.0.0/0"
    port_range = "80"
  }
  inbound {
    protocol   = "TCP"
    ip_block   = "0.0.0.0/0"
    port_range = "443"
  }
  inbound {
    protocol   = "TCP"
    ip_block   = var.admin_cidr # SSH는 관리 IP만 (예: "1.2.3.4/32")
    port_range = "22"
  }
  outbound {
    protocol   = "TCP"
    ip_block   = "0.0.0.0/0"
    port_range = "1-65535"
  }
}

# ── 서버 + 공인 IP ──
resource "ncloud_server" "app" {
  subnet_no           = ncloud_subnet.public.id
  name                = "${var.name_prefix}-app"
  server_image_number = data.ncloud_server_image_numbers.ubuntu.image_number_list[0]
  server_spec_code    = var.server_spec_code
  login_key_name      = ncloud_login_key.key.key_name
  network_interface {
    network_interface_no = ncloud_network_interface.app.id
    order                = 0
  }
}

resource "ncloud_network_interface" "app" {
  name                  = "${var.name_prefix}-nic"
  subnet_no             = ncloud_subnet.public.id
  access_control_groups = [ncloud_access_control_group.app.id]
}

resource "ncloud_public_ip" "app" {
  server_instance_no = ncloud_server.app.id
}

# DB는 서버 내 Postgres 컨테이너(compose)로 운영 — 관리형 DB 리소스 없음.
# 비밀번호 등 DB 설정은 서버의 backend/deploy/.env 에서 관리한다.
