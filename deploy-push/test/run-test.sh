#!/usr/bin/env bash
# End-to-end test of deploy-push.sh against a throwaway server: a docker:dind container with sshd.
# Builds the real KINA image (a few minutes on a cold build cache). Removes the container, the test image,
# the test key and every temp file on exit. Needs Docker (privileged containers) and ssh-keygen.
set -euo pipefail

test_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
deploy_dir=$(cd -- "$test_dir/.." && pwd)
repo_root=$(cd -- "$deploy_dir/.." && pwd)
readonly IMAGE_REPO=alacrity-education/kina
readonly SERVER_IMAGE=kina-deploy-test-server:local
readonly HOST_ALIAS=kina-deploy-test
readonly REMOTE_DIR=/home/deploy/apps/kina
container=kina-deploy-test-$$-$RANDOM

work=$(mktemp -d "${TMPDIR:-/tmp}/kina-deploy-test.XXXXXX")
mkdir -p "$work/tmp"
started=$SECONDS
phase_start=$SECONDS
passed=0
summary=()

# Remember the local KINA tags so the test leaves them as it found them.
tag=$(git -C "$repo_root" rev-parse --short HEAD)
[[ -z $(git -C "$repo_root" status --porcelain) ]] || tag+=-dirty
tag_existed=$(docker image inspect -f '{{.Id}}' "$IMAGE_REPO:$tag" 2>/dev/null || true)
latest_before=$(docker image inspect -f '{{.Id}}' "$IMAGE_REPO:latest" 2>/dev/null || true)

cleanup() {
  local rc=$?
  trap - EXIT INT TERM
  docker rm -fv "$container" >/dev/null 2>&1 || true
  docker image rm "$SERVER_IMAGE" >/dev/null 2>&1 || true
  [[ -n $tag_existed ]] || docker image rm "$IMAGE_REPO:$tag" >/dev/null 2>&1 || true
  if [[ -n $latest_before ]]; then
    docker tag "$latest_before" "$IMAGE_REPO:latest" >/dev/null 2>&1 || true
  else
    docker image rm "$IMAGE_REPO:latest" >/dev/null 2>&1 || true
  fi
  rm -rf -- "$work"
  if ((rc == 0)); then
    echo "run-test: cleaned up (container, test image, key, temp files)."
  else
    echo "run-test: FAILED (exit $rc); cleaned up." >&2
  fi
  exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

fail() { echo "FAIL: $*" >&2; exit 1; }
ok()   { passed=$((passed + 1)); echo "  ok: $*"; }
phase_done() {
  summary+=("$(printf '%-44s %4ds' "$1" $((SECONDS - phase_start)))")
  phase_start=$SECONDS
}
on_server() { docker exec -u deploy "$container" sh -c "$1"; }

echo "== Local checks"
"$deploy_dir/check-template.sh" >/dev/null || fail "check-template.sh fails on the committed files"
ok "check-template.sh accepts the committed template"
sed 's/condition: service_healthy/condition: service_started/' "$deploy_dir/compose.template.yaml" >"$work/drifted.yaml"
if "$deploy_dir/check-template.sh" "$work/drifted.yaml" >/dev/null 2>&1; then
  fail "check-template.sh accepted a drifted template"
fi
ok "check-template.sh rejects a drifted template"
for bad in "$HOST_ALIAS:/abs/path" "$HOST_ALIAS:apps/../kina" "$HOST_ALIAS:" "no-colon"; do
  if "$deploy_dir/deploy-push.sh" --dry-run "$bad" >/dev/null 2>&1; then
    fail "deploy-push.sh accepted '$bad'"
  fi
done
ok "deploy-push.sh rejects absolute, '..', empty and malformed targets"
phase_done "local checks"

echo "== Starting the test server"
docker build -q -t "$SERVER_IMAGE" "$test_dir" >/dev/null
docker run -d --privileged --name "$container" -p 127.0.0.1::22 "$SERVER_IMAGE" >/dev/null
port=$(docker port "$container" 22/tcp | head -n1 | sed 's/.*://')
[[ $port =~ ^[0-9]+$ ]] || fail "could not read the published ssh port"

ssh-keygen -q -t ed25519 -N '' -C kina-deploy-test -f "$work/id_ed25519"
docker exec -i "$container" sh -c 'umask 077 && mkdir -p /home/deploy/.ssh && cat > /home/deploy/.ssh/authorized_keys \
  && chown -R deploy:deploy /home/deploy/.ssh' <"$work/id_ed25519.pub"
cat >"$work/ssh_config" <<CFG
Host $HOST_ALIAS
  HostName 127.0.0.1
  Port $port
  User deploy
  IdentityFile $work/id_ed25519
  IdentitiesOnly yes
  StrictHostKeyChecking no
  UserKnownHostsFile /dev/null
  LogLevel ERROR
CFG

for _ in $(seq 1 60); do
  docker exec -u deploy "$container" docker info >/dev/null 2>&1 && break
  sleep 1
done
on_server 'docker info >/dev/null' || fail "the Docker daemon in the test server did not start"
for _ in $(seq 1 30); do
  ssh -F "$work/ssh_config" -o BatchMode=yes "$HOST_ALIAS" true 2>/dev/null && break
  sleep 1
done
ssh -F "$work/ssh_config" -o BatchMode=yes "$HOST_ALIAS" true || fail "sshd in the test server is not reachable"
ok "test server up: sshd on 127.0.0.1:$port, Docker daemon usable by user deploy"
phase_done "test server start"

assert_common() {
  on_server "docker image inspect $IMAGE_REPO:$tag >/dev/null" || fail "$IMAGE_REPO:$tag missing on the server"
  on_server "docker image inspect $IMAGE_REPO:latest >/dev/null" || fail "$IMAGE_REPO:latest missing on the server"
  [[ "$(on_server "docker image inspect -f '{{.Id}}' $IMAGE_REPO:$tag")" == \
     "$(on_server "docker image inspect -f '{{.Id}}' $IMAGE_REPO:latest")" ]] || fail ":$tag and :latest differ"
  ok "server has $IMAGE_REPO:$tag and :latest (same image)"
  on_server "grep -qx '    image: $IMAGE_REPO:$tag' $REMOTE_DIR/compose.yaml" || fail "compose.yaml does not use :$tag"
  if on_server "grep -Eq '^[[:space:]]*build:' $REMOTE_DIR/compose.yaml"; then fail "compose.yaml contains build:"; fi
  on_server "grep -qx '    pull_policy: never' $REMOTE_DIR/compose.yaml" || fail "compose.yaml lacks pull_policy: never"
  on_server "cd $REMOTE_DIR && docker compose config -q" || fail "docker compose config rejects compose.yaml"
  ok "compose.yaml uses :$tag, has no build:, has pull_policy: never, and passes docker compose config"
  on_server "cat $REMOTE_DIR/.env.example" | cmp -s - "$repo_root/.env.example" || fail ".env.example differs"
  [[ $(on_server "stat -c %a $REMOTE_DIR/.env") == 600 ]] || fail ".env is not mode 600"
  ok ".env.example equals the repository's; .env has mode 600"
  [[ -z $(on_server 'ls -d /tmp/kina-deploy-* 2>/dev/null || true') ]] || fail "kina-deploy-* left in the server's /tmp"
  ok "no kina-deploy-* left in the server's /tmp"
  [[ -z $(on_server 'docker ps -aq') ]] || fail "containers exist on the server"
  ok "docker ps -a on the server is empty (nothing started)"
  [[ -z $(ls -A "$work/tmp") ]] || fail "local temp files left: $(ls -A "$work/tmp")"
  [[ $(compgen -G '/tmp/kdp.*' | wc -l) -eq $ctl_dirs_before ]] || fail "ssh control directory left in /tmp"
  ok "no local temp files or ssh control sockets left"
}

ctl_dirs_before=$(compgen -G '/tmp/kdp.*' | wc -l || true)
echo "== Run 1: build and stream (default mode)"
TMPDIR=$work/tmp "$deploy_dir/deploy-push.sh" --ssh-config "$work/ssh_config" "$HOST_ALIAS:apps/kina" | tee "$work/run1.log"
phase_done "run 1: build + stream + files"
assert_common
on_server "cat $REMOTE_DIR/.env" | cmp -s - "$repo_root/.env.example" || fail ".env does not equal .env.example"
ok ".env was created and equals .env.example"
grep -q 'Created .env from .env.example' "$work/run1.log" || fail "run 1 did not report creating .env"

echo "== Run 2: --via-tmp --no-build with a modified .env"
on_server "cd $REMOTE_DIR && sed -i '/^COUNTRY=/d' .env && echo KINA_TEST_MARKER=kept >> .env && chmod 644 .env"
TMPDIR=$work/tmp "$deploy_dir/deploy-push.sh" --ssh-config "$work/ssh_config" --via-tmp --no-build \
  "$HOST_ALIAS:apps/kina" | tee "$work/run2.log"
phase_done "run 2: --via-tmp --no-build + files"
assert_common
on_server "grep -qx KINA_TEST_MARKER=kept $REMOTE_DIR/.env" || fail "the modified .env was overwritten"
if on_server "grep -q '^COUNTRY=' $REMOTE_DIR/.env"; then fail ".env was merged instead of kept"; fi
ok "the existing .env was kept unchanged"
grep -q 'Kept the existing .env' "$work/run2.log" || fail "run 2 did not report keeping .env"
grep -qx '  COUNTRY' "$work/run2.log" || fail "run 2 did not list COUNTRY as missing from .env"
ok "run 2 listed the variable missing from .env (COUNTRY)"

echo
echo "== Summary"
printf '  %s\n' "${summary[@]}"
printf '  %-44s %4ds\n' "total" $((SECONDS - started))
echo "  $passed checks passed (image tag $tag)"
