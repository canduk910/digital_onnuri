# NCP 인프라 (Terraform)

Server(VM) + VPC/Subnet + ACG + 공인IP 를 코드로 프로비저닝한다. DB는 서버 내 Postgres 컨테이너(compose)로 운영하므로 여기서 만들지 않는다.

> ⚠️ **검증 한계**: 이 구성은 실제 NCP 계정 없이 작성돼 `apply` 검증을 하지 못했다.
> 제품 코드·리소스 속성은 provider 버전/리전에 따라 다르므로 **반드시 `terraform plan`으로 확인 후** 조정한다.
> 유료 리소스를 생성하므로 `apply`는 비용 승인 행위다 — plan 출력을 검토하고 직접 실행할 것.

## 사용

```bash
cd backend/deploy/terraform
cp terraform.tfvars.example terraform.tfvars   # 값 입력(커밋 금지)

terraform init
terraform plan     # ← 제품코드/이미지가 계정에서 유효한지 확인, 필요 시 main.tf 조정
terraform apply    # 비용 발생. 승인 후 진행.

terraform output server_public_ip     # → 서브도메인 A레코드에 등록
terraform output -raw ssh_private_key > onnuri.pem && chmod 600 onnuri.pem
```

## apply 이후

1. `server_public_ip` 를 보유 도메인의 서브도메인 `api.koscomlabor.cloud` **A레코드**로 등록.
2. 서버 SSH 접속 후 `../bootstrap.sh` 실행(도커 설치·소스·기동·DB 컨테이너·데이터 적재 자동). 실행 전 `.env`에 DB 비밀번호·도메인·이메일 입력.
3. `merchants.html`의 `API_BASE`는 이미 `https://api.koscomlabor.cloud/api` — 검증 후 main 반영으로 라이브 전환.

## 조정 포인트 (plan에서 확인)

- `data.ncloud_server_image_numbers` 필터 → 원하는 Ubuntu 22.04 이미지로. 스펙은 `server_spec_code` 변수로 지정(`terraform console`의 `data.ncloud_server_specs`로 후보 확인).
- `zone`·`region` → 계정에서 사용 가능한 값.
