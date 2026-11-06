#!/usr/bin/env bash
# Teste ponta a ponta sobre a stack do compose (ADR-0003, D12 e fase A3): cadastro no auth-server, login da CLI pelo
# fluxo de dispositivo, criação de link no app, redirecionamento e estatística.
#
# Uso, com a stack no ar (docker compose --profile app up -d --build):
#   scripts/ponta-a-ponta.sh
# Endereços: AUTH_URL (padrão http://localhost:8090) e API_URL (padrão http://localhost:8080).
# Cada execução cria uma conta nova, com e-mail e senha aleatórios, que só servem para o teste.
set -euo pipefail

AUTH_URL=${AUTH_URL:-http://localhost:8090}
API_URL=${API_URL:-http://localhost:8080}
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
COOKIES="$WORK/cookies"

step() { printf '\n==> %s\n' "$*"; }
fail() { printf 'FALHOU: %s\n' "$*" >&2; exit 1; }
field() { python3 -c 'import json, sys; print(json.load(sys.stdin)[sys.argv[1]])' "$1"; }
# Campos de um formulário HTML (inputs com nome e valor) em formato de corpo x-www-form-urlencoded.
form_fields() {
    python3 -c '
import html, re, sys, urllib.parse
fields = []
for tag in re.findall(r"<input\b[^>]*>", sys.stdin.read()):
    name = re.search(r"\bname=\"([^\"]*)\"", tag)
    value = re.search(r"\bvalue=\"([^\"]*)\"", tag)
    if name and value:
        fields.append((name.group(1), html.unescape(value.group(1))))
print(urllib.parse.urlencode(fields))'
}
csrf() { form_fields | tr '&' '\n' | sed -n 's/^_csrf=//p' | head -1; }
browser() { curl -sS -b "$COOKIES" -c "$COOKIES" "$@"; }

wait_ready() {
    for _ in $(seq 1 90); do
        curl -fsS -o /dev/null "$1/readyz" 2>/dev/null && return 0
        sleep 2
    done
    fail "$1 não ficou pronto"
}

step "Esperando o auth-server e o app"
wait_ready "$AUTH_URL"
wait_ready "$API_URL"

step "CLI pede um código (fluxo de dispositivo)"
device=$(curl -fsS -X POST "$AUTH_URL/oauth2/device_authorization" -d client_id=encurtador-cli -d scope=email)
device_code=$(field device_code <<<"$device")
user_code=$(field user_code <<<"$device")
echo "user_code: $user_code"

step "Usuário se cadastra no navegador"
email="ponta-a-ponta-$(date +%s)-$RANDOM@exemplo.com"
password=$(openssl rand -hex 12)
token=$(browser "$AUTH_URL/register" | csrf)
status=$(browser -o /dev/null -w '%{http_code}' -X POST "$AUTH_URL/register" \
    --data-urlencode "email=$email" --data-urlencode "password=$password" --data-urlencode "_csrf=$token")
[[ $status == 302 ]] || fail "cadastro respondeu $status"

step "Usuário confirma o código e consente"
token=$(browser "$AUTH_URL/activate?user_code=$user_code" | csrf)
consent=$(browser -X POST "$AUTH_URL/oauth2/device_verification" \
    --data-urlencode "user_code=$user_code" --data-urlencode "_csrf=$token")
grep -q 'name="state"' <<<"$consent" || fail "página de consentimento sem state"
status=$(browser -o /dev/null -w '%{http_code}' -X POST "$AUTH_URL/oauth2/device_verification" \
    --data "$(form_fields <<<"$consent")")
[[ $status == 302 ]] || fail "consentimento respondeu $status"

step "CLI troca o código pelo access token"
tokens=$(curl -fsS -X POST "$AUTH_URL/oauth2/token" -d client_id=encurtador-cli \
    -d grant_type=urn:ietf:params:oauth:grant-type:device_code -d "device_code=$device_code")
access_token=$(field access_token <<<"$tokens")

step "Cria um link no app"
link=$(curl -fsS -X POST "$API_URL/api/v1/links" -H "Authorization: Bearer $access_token" \
    -H 'Content-Type: application/json' -d '{"url":"https://exemplo.com/ponta-a-ponta"}')
code=$(field code <<<"$link")
echo "code: $code"

step "Redireciona"
status=$(curl -sS -o /dev/null -w '%{http_code}' -H 'Referer: https://t.co/ponta-a-ponta' "$API_URL/$code")
[[ $status == 302 ]] || fail "redirecionamento respondeu $status"

step "Estatística do link e do usuário (o clique passa pelo Kafka)"
for _ in $(seq 1 30); do
    clicks=$(curl -fsS -H "Authorization: Bearer $access_token" "$API_URL/api/v1/links/$code/stats" | field totalClicks)
    [[ $clicks == 1 ]] && break
    sleep 1
done
[[ $clicks == 1 ]] || fail "o clique não apareceu nas estatísticas do link"
total=$(curl -fsS -H "Authorization: Bearer $access_token" "$API_URL/api/v1/stats" | field totalClicks)
[[ $total == 1 ]] || fail "as estatísticas do usuário mostram $total cliques"

printf '\nOK: login pela CLI → link → redirecionamento → estatística\n'
