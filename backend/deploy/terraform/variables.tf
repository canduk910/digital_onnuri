variable "ncloud_access_key" {
  description = "NCP API Access Key (콘솔 인증키 관리에서 발급)"
  type        = string
  sensitive   = true
}

variable "ncloud_secret_key" {
  description = "NCP API Secret Key"
  type        = string
  sensitive   = true
}

variable "region" {
  description = "NCP 리전"
  type        = string
  default     = "KR"
}

variable "zone" {
  description = "가용 존 (예: KR-2)"
  type        = string
  default     = "KR-2"
}

variable "name_prefix" {
  description = "리소스 이름 접두사"
  type        = string
  default     = "onnuri"
}

variable "admin_cidr" {
  description = "SSH(22) 허용 관리 IP CIDR (예: 1.2.3.4/32)"
  type        = string
}

variable "server_spec_code" {
  description = "서버 스펙 코드(예: 2vCPU/4GB). `terraform console`의 data.ncloud_server_specs 또는 콘솔에서 확인해 지정."
  type        = string
}

# DB(사용자·비밀번호)는 관리형 리소스가 아니라 서버 내 컨테이너 → backend/deploy/.env 에서 관리.
