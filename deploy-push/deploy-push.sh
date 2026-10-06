#!/usr/bin/env bash
# deploy-push.sh: build the KINA image and push it, with compose.yaml and .env, to a Docker host over SSH.
# Starts nothing on the server. See deploy-push/README.md.
set -euo pipefail

readonly IMAGE_REPO=alacrity-education/kina
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "$script_dir/.." && pwd)
readonly script_dir repo_root

usage() {
  cat <<USAGE
Usage: deploy-push.sh [options] <sshhost>:<path>

Builds the KINA image ($IMAGE_REPO), loads it into the Docker daemon of <sshhost> over SSH and puts
compose.yaml, .env.example and (first time only) .env into ~/<path> on that host. Nothing is started.

  <sshhost>   SSH host alias (from ~/.ssh/config or --ssh-config)
  <path>      directory relative to the remote user's home; created if missing

Options:
  --tag <tag>          image tag (default: git short sha, plus -dirty when the tree has changes);
                       the image is always tagged latest as well
  --no-build           push an image that is already built locally (<repo>:<tag>)
  --via-tmp            copy a compressed archive to /tmp/kina-deploy-<random>/ on the server with scp and
                       docker load -i it (the directory is removed afterwards); default is streaming over ssh
  --ssh-config <file>  ssh/scp config file (ssh -F); default is your normal ssh configuration
  --dry-run            run the local checks and print the plan; no build, no remote changes
  -h, --help           show this help
USAGE
}

die()  { echo "deploy-push: error: $*" >&2; exit 1; }
warn() { echo "deploy-push: warning: $*" >&2; }
step() { printf '\n==> %s\n' "$*"; }

# ---- arguments ----
tag="" no_build=false via_tmp=false ssh_config="" dry_run=false target=""
while (($#)); do
  case $1 in
    --tag)        (($# >= 2)) || die "--tag needs a value"; tag=$2; shift 2 ;;
    --tag=*)      tag=${1#*=}; shift ;;
    --no-build)   no_build=true; shift ;;
    --via-tmp)    via_tmp=true; shift ;;
    --ssh-config) (($# >= 2)) || die "--ssh-config needs a value"; ssh_config=$2; shift 2 ;;
    --ssh-config=*) ssh_config=${1#*=}; shift ;;
    --dry-run)    dry_run=true; shift ;;
    -h|--help)    usage; exit 0 ;;
    --)           shift; break ;;
    -*)           usage >&2; die "unknown option: $1" ;;
    *)            [[ -z $target ]] || die "only one <sshhost>:<path> is allowed"; target=$1; shift ;;
  esac
done
if (($#)); then
  [[ -z $target && $# -eq 1 ]] || die "only one <sshhost>:<path> is allowed"
  target=$1
fi
[[ -n $target ]] || { usage >&2; exit 2; }

[[ $target == *:* ]] || die "target must be <sshhost>:<path>, got '$target'"
ssh_host=${target%%:*}
remote_path=${target#*:}
remote_path=${remote_path%/}
[[ -n $ssh_host ]] || die "empty <sshhost> in '$target'"
[[ $ssh_host =~ ^[A-Za-z0-9._@-]+$ && $ssh_host != -* ]] || die "invalid <sshhost> '$ssh_host'"
[[ -n $remote_path ]] || die "empty <path>: give a directory relative to the remote home, for example apps/kina"
[[ $remote_path != /* ]] || die "<path> must be relative to the remote home directory, not absolute: '$remote_path'"
[[ $remote_path =~ ^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$ ]] \
  || die "<path> may only contain letters, digits, '.', '_', '-' and '/': '$remote_path'"
IFS=/ read -ra path_parts <<<"$remote_path"
for part in "${path_parts[@]}"; do
  [[ $part != "." && $part != ".." ]] || die "<path> must not contain '.' or '..' components: '$remote_path'"
done
if [[ -n $ssh_config ]]; then
  [[ -r $ssh_config ]] || die "cannot read ssh config '$ssh_config'"
  ssh_config=$(cd -- "$(dirname -- "$ssh_config")" && pwd)/$(basename -- "$ssh_config")
fi

# ---- local checks ----
command -v docker >/dev/null || die "docker is not installed locally"
command -v git >/dev/null || die "git is not installed locally"
command -v ssh >/dev/null || die "ssh is not installed locally"
command -v gzip >/dev/null || die "gzip is not installed locally"
$via_tmp && { command -v scp >/dev/null || die "scp is not installed locally"; }

if [[ -z $tag ]]; then
  tag=$(git -C "$repo_root" rev-parse --short HEAD) || die "cannot read the git commit of $repo_root"
  [[ -z $(git -C "$repo_root" status --porcelain) ]] || tag+=-dirty
fi
[[ $tag =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ ]] || die "invalid image tag '$tag'"
image=$IMAGE_REPO:$tag
image_latest=$IMAGE_REPO:latest

"$script_dir/check-template.sh" >/dev/null \
  || die "deploy-push/compose.template.yaml has drifted from compose.yaml (see the diff above); fix the template first"

ssh_opts=()
[[ -z $ssh_config ]] || ssh_opts+=(-F "$ssh_config")
resolved_host=$(ssh "${ssh_opts[@]}" -G "$ssh_host" 2>/dev/null | awk '$1 == "hostname" { print $2; exit }') || true
[[ -n $resolved_host ]] || die "ssh -G $ssh_host did not resolve a hostname; check your ssh config"
resolved_user=$(ssh "${ssh_opts[@]}" -G "$ssh_host" 2>/dev/null | awk '$1 == "user" { print $2; exit }') || true
resolved_port=$(ssh "${ssh_opts[@]}" -G "$ssh_host" 2>/dev/null | awk '$1 == "port" { print $2; exit }') || true
[[ $resolved_host != "$ssh_host" ]] || warn "'$ssh_host' is not an alias in your ssh config; using it as the hostname"

build_desc="docker build in $repo_root"
$no_build && build_desc="no, use the local image"
transfer_desc="docker save | gzip -1 | ssh 'gunzip -c | docker load' (no files)"
$via_tmp && transfer_desc="scp to /tmp/kina-deploy-<random>/ on the server, docker load -i, remove"

cat <<PLAN
deploy-push plan
  ssh host:          $ssh_host -> ${resolved_user:-?}@$resolved_host:${resolved_port:-22}${ssh_config:+ (config $ssh_config)}
  image:             $image (also tagged latest)
  build:             $build_desc
  transfer:          $transfer_desc
  remote directory:  ~/$remote_path (compose.yaml, .env.example; .env only if missing)
  starts anything:   no
PLAN
if $dry_run; then
  echo
  echo "Dry run: nothing was built, copied or changed."
  exit 0
fi

# ---- cleanup ----
local_tmp=$(mktemp -d "${TMPDIR:-/tmp}/kina-deploy-push.XXXXXX")
# One SSH connection for every command (ControlMaster). Unix socket paths are limited to about 100 bytes,
# so the socket gets its own short directory in /tmp.
ctl_dir=$(mktemp -d /tmp/kdp.XXXXXX)
remote_tmp=""
ssh_opts+=(-o BatchMode=yes -o ControlMaster=auto -o "ControlPath=$ctl_dir/s" -o ControlPersist=120)

cleanup() {
  local rc=$?
  trap - EXIT INT TERM
  if [[ -n $remote_tmp ]]; then
    ssh "${ssh_opts[@]}" "$ssh_host" "rm -rf -- '$remote_tmp'" \
      || warn "could not remove $remote_tmp on $ssh_host; remove it by hand"
  fi
  ssh "${ssh_opts[@]}" -O exit "$ssh_host" >/dev/null 2>&1 || true
  rm -rf -- "$local_tmp" "$ctl_dir"
  exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

remote() { ssh "${ssh_opts[@]}" "$ssh_host" "$@"; }

# ---- remote preflight ----
step "Checking $ssh_host"
remote true || die "cannot log in to $ssh_host with ssh -o BatchMode=yes (key not loaded, or host unreachable)"
server_version=$(remote "docker info --format '{{.ServerVersion}}'") \
  || die "docker info failed on $ssh_host: is Docker running and is the user allowed to use it (docker group)?"
echo "Docker $server_version on $resolved_host"
if compose_version=$(remote "docker compose version --short" 2>/dev/null); then
  echo "Docker Compose $compose_version"
else
  warn "docker compose is not available on $ssh_host; install the compose plugin before 'docker compose up -d'"
fi
if ! $via_tmp; then
  remote "command -v gunzip >/dev/null" \
    || die "gunzip is missing on $ssh_host; install gzip there or use --via-tmp (docker load reads gzip itself)"
fi

# ---- build ----
if $no_build; then
  step "Using the local image $image"
  docker image inspect "$image" >/dev/null 2>&1 || die "local image $image not found; build it first or drop --no-build"
  docker tag "$image" "$image_latest"
else
  step "Building $image"
  docker build -t "$image" -t "$image_latest" "$repo_root"
fi
size_bytes=$(docker image inspect -f '{{.Size}}' "$image")
echo "Image size: $((size_bytes / 1000000)) MB uncompressed"

# ---- transfer ----
if $via_tmp; then
  step "Copying the image to /tmp on $ssh_host"
  archive=$local_tmp/kina-image.tar.gz
  docker save "$image" "$image_latest" | gzip -1 >"$archive"
  echo "Archive: $(($(wc -c <"$archive") / 1000000)) MB compressed"
  remote_tmp=$(remote "mktemp -d /tmp/kina-deploy-XXXXXXXX") || die "cannot create a directory in /tmp on $ssh_host"
  [[ $remote_tmp =~ ^/tmp/kina-deploy-[A-Za-z0-9]+$ ]] || die "unexpected remote temp directory '$remote_tmp'"
  scp "${ssh_opts[@]}" -q "$archive" "$ssh_host:$remote_tmp/kina-image.tar.gz"
  rm -f -- "$archive"
  remote "docker load -i '$remote_tmp/kina-image.tar.gz'"
  remote "rm -rf -- '$remote_tmp'"
  remote_tmp=""
else
  step "Streaming the image to $ssh_host"
  docker save "$image" "$image_latest" | gzip -1 | remote "gunzip -c | docker load"
fi
remote "docker image inspect --format '{{.Id}}' '$image'" >/dev/null \
  || die "$image is not present on $ssh_host after the transfer"
echo "Verified: $image is loaded on $ssh_host"

# ---- files ----
step "Writing ~/$remote_path on $ssh_host"
compose_file=$local_tmp/compose.yaml
sed "s|__KINA_IMAGE__|$image|" "$script_dir/compose.template.yaml" >"$compose_file"
grep -q "^    image: $image\$" "$compose_file" || die "generating compose.yaml failed"
! grep -q '__KINA_IMAGE__' "$compose_file" || die "generating compose.yaml failed: placeholder left"

remote "mkdir -p \"\$HOME/$remote_path\""
remote "cat > \"\$HOME/$remote_path/compose.yaml\"" <"$compose_file"
remote "cat > \"\$HOME/$remote_path/.env.example\"" <"$repo_root/.env.example"
echo "Wrote compose.yaml (image $image) and .env.example"

env_state=$(remote "cd \"\$HOME/$remote_path\" && umask 077 && if [ -e .env ]; then echo kept; \
  else cp .env.example .env && echo created; fi && chmod 600 .env")
if [[ $env_state == created ]]; then
  echo "Created .env from .env.example (mode 600)"
else
  echo "Kept the existing .env (mode set to 600)."
  missing=$(remote "cd \"\$HOME/$remote_path\" && sed -n 's/^\\([A-Z_][A-Z0-9_]*\\)=.*/\\1/p' .env.example | \
    while read -r v; do grep -q \"^\$v=\" .env || echo \"\$v\"; done") || missing=""
  if [[ -n $missing ]]; then
    echo "Note: these variables of .env.example are not set in .env; merge them if you need them:"
    while read -r v; do echo "  $v"; done <<<"$missing"
  fi
  echo "Compare with: ssh $ssh_host 'cd ~/$remote_path && diff .env.example .env'"
fi

cat <<NEXT

Done. Nothing was started on $ssh_host.

Next steps:
  ssh $ssh_host
  cd ~/$remote_path
  \$EDITOR .env     # for production set at least:
                   #   KINA_MODE=prod
                   #   OIDC_ISSUER_URI, OIDC_CLIENT_ID, OIDC_CLIENT_SECRET
                   #   OIDC_REQUIRED_GROUPS, OIDC_ALLOWED_EMAIL_DOMAINS
                   #   KINA_TOKEN_ENCRYPTION_KEY (openssl rand -base64 32)
                   #   KINA_PUBLIC_BASE_URL (the public https origin)
                   #   MOUSER_API_KEY, TME_TOKEN, TME_APPLICATION_SECRET
  docker compose up -d
NEXT
