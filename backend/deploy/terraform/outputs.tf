output "server_public_ip" {
  description = "서버 공인 IP — 이 값을 서브도메인 A레코드에 등록"
  value       = ncloud_public_ip.app.public_ip
}

output "ssh_private_key" {
  description = "서버 접속용 SSH 개인키(안전 보관)"
  value       = ncloud_login_key.key.private_key
  sensitive   = true
}
