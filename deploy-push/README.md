# deploy-push

`deploy-push.sh` builds the KINA image, loads it into the Docker daemon of a server that you reach over SSH, and puts a `compose.yaml` and a `.env` next to it. It does not start anything. You start KINA yourself after you have filled in `.env`.

```bash
deploy-push/deploy-push.sh [options] <sshhost>:<path>
deploy-push/deploy-push.sh kina-prod:apps/kina
```

## Prerequisites

On your machine:

- Bash 4 or newer, `docker`, `git`, `ssh`, `gzip` (and `scp` for `--via-tmp`).
- An SSH host alias for the server in `~/.ssh/config` (or in the file given with `--ssh-config`). The script runs ssh with `BatchMode=yes`, so the key must work without a prompt: load it into `ssh-agent` or use a key without a passphrase.

On the server:

- A running Docker daemon and the Docker Compose plugin (`docker compose version`). Without Compose the script warns and continues, but you need it to start KINA.
- The SSH user can run `docker` without `sudo`, that is, it is in the `docker` group. The script never uses `sudo`.
- A POSIX login shell (`sh`, `bash`, `dash`, `ash`) and `gunzip` (for the default streaming mode).
- Disk space: the image is about 960 MB in Docker (about 350 MB compressed while it is transferred). It includes the ranking model (138 MB), so the server needs no access to Hugging Face. On the first start KINA downloads the JLCPCB database (5.3 GB, about twice that at peak during a refresh) into the `kina-data` volume; that is the only download. See [docs/OPERATIONS.md](../docs/OPERATIONS.md) for sizing.

## Options

| Option | Effect |
|---|---|
| `--tag <tag>` | Image tag. Default: the git short commit, plus `-dirty` when the working tree has changes. The image is always tagged `latest` as well. |
| `--no-build` | Do not build. Push the local image `alacrity-education/kina:<tag>` that already exists. |
| `--via-tmp` | Write a compressed archive, copy it with `scp` to `/tmp/kina-deploy-<random>/` on the server, run `docker load -i` there and remove the directory. Use it when a long-running pipe over SSH is unreliable. |
| `--ssh-config <file>` | Use this ssh config file (`ssh -F`, `scp -F`) instead of your normal one. The test uses it. |
| `--dry-run` | Run the local checks and print the plan. Builds nothing and changes nothing. |
| `-h`, `--help` | Show the usage. |

`<path>` is a directory relative to the remote user's home directory, for example `apps/kina`. It is created when it is missing. Absolute paths, `.` and `..` components and characters other than letters, digits, `.`, `_`, `-` and `/` are refused.

## What it does

1. Checks the arguments and the local tools, and runs `check-template.sh` (see below).
2. Prints the plan: the hostname the alias resolves to (`ssh -G`), the image tag and the remote directory.
3. Checks the server: SSH login with `BatchMode=yes`, `docker info` as that user, `docker compose version` (warning only) and `gunzip`.
4. Builds the image from the repository root: `docker build -t alacrity-education/kina:<tag> -t alacrity-education/kina:latest .` The root is found from the script's location, so you can run it from any directory. The build downloads the ranking model from Hugging Face and verifies it (default build arguments; for others, such as `CROSS_ENCODER_VARIANTS=int8`, build `alacrity-education/kina:<tag>` yourself and use `--no-build`). It prints the image size.
5. Transfers the image (both tags):
   - Default: `docker save ... | gzip -1 | ssh <host> 'gunzip -c | docker load'`. No file is written on either side.
   - `--via-tmp`: the archive goes to a local temporary directory, then to `/tmp/kina-deploy-<random>/` on the server. Both are removed after `docker load`, and also when the script fails or is interrupted.
6. Checks with `docker image inspect` on the server that the tag is there.
7. Writes into `~/<path>`:
   - `compose.yaml`, generated from `compose.template.yaml` with the pushed tag in the `kina` image line and `pull_policy: never`. It is overwritten on every run.
   - `.env.example`, a copy of the repository's `.env.example`. It is overwritten on every run.
   - `.env`, a copy of `.env.example`, only when there is no `.env` yet. Its mode is set to 600.
8. Prints the next steps.

All SSH commands share one connection (OpenSSH `ControlMaster`). Its socket lives in a short temporary directory under the local `/tmp` and is removed at the end.

## What it does not do

- It does not start, stop or restart containers, and it never runs `docker compose up` or `docker pull`.
- It does not push to a registry. The image goes straight into the server's Docker daemon.
- It does not edit an existing `.env` and does not remove old images.

## `.env` on re-deploys

The first deploy creates `.env` from `.env.example`. Later deploys keep your `.env` as it is (only its mode is set to 600) and refresh `.env.example` next to it. When `.env.example` has variables that your `.env` does not set, the script lists them. Compare the two files and merge what you need:

```bash
ssh <host> 'cd ~/<path> && diff .env.example .env'
```

For production set at least `KINA_MODE=prod`, `OIDC_ISSUER_URI`, `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET`, `OIDC_REQUIRED_GROUPS`, `OIDC_ALLOWED_EMAIL_DOMAINS`, `KINA_TOKEN_ENCRYPTION_KEY`, `KINA_PUBLIC_BASE_URL` and the distributor keys (`MOUSER_API_KEY`, `TME_TOKEN`, `TME_APPLICATION_SECRET`). Then start KINA:

```bash
ssh <host>
cd ~/<path>
docker compose up -d
```

To roll out a new version later, run the script again and then `docker compose up -d` on the server.

## Server-specific changes: use `compose.override.yaml`

`compose.yaml` is generated and overwritten on every run, so never edit it on the server. Put host-specific
settings into `compose.override.yaml` next to it; Docker Compose merges that file automatically and this script never
touches it. Typical content: bind mounts instead of the named volumes (an entry with the same container path replaces
the generated one). The host port is not an override: set `KINA_PORT` in `.env`, because `compose.yaml` maps
`${KINA_PORT:-8080}:8080`.

```yaml
services:
  kina:
    volumes:
      - /srv/kina-data:/data
  postgres:
    volumes:
      - /srv/kina-db:/var/lib/postgresql/data
```

A bind-mounted `/data` must be writable by uid 10001 (the `kina` user in the image): `chown -R 10001:10001 /srv/kina-data`.
Without that the JLCPCB database download fails with `AccessDeniedException: /data/jlcpcb` and LCSC stays unavailable.

## Rolling back

Every deploy loads a new tag, and the earlier tags stay in the server's Docker daemon until you remove them. `latest` always points to the most recent push.

```bash
ssh <host>
cd ~/<path>
docker image ls alacrity-education/kina           # pick the previous tag
sed -i 's|image: alacrity-education/kina:.*|image: alacrity-education/kina:<previous-tag>|' compose.yaml
docker compose up -d
```

Database migrations are not reversed. Take a `pg_dump` before an upgrade (see [docs/OPERATIONS.md](../docs/OPERATIONS.md)). The next run of the script overwrites `compose.yaml` again. Old tags take disk space; remove them with `docker image rm alacrity-education/kina:<tag>`.

## Template drift guard

`compose.template.yaml` is a copy of the root `compose.yaml`. The only differences allowed are comments and, in the `kina` service, the `build:`, `image:` and `pull_policy:` lines. `check-template.sh` compares the two files under that rule and fails with a diff when anything else differs. `deploy-push.sh` runs it before building and stops when it fails. When you change `compose.yaml`, make the same change in `compose.template.yaml`.

```bash
deploy-push/check-template.sh
```

## Test

`test/run-test.sh` deploys to a throwaway server: a privileged `docker:dind` container with `sshd` added (`test/Dockerfile`), a user `deploy` in the `docker` group, a test key pair in a temporary directory and SSH published on `127.0.0.1:<random port>`.

```bash
deploy-push/test/run-test.sh
```

It needs Docker with privileged containers and `ssh-keygen`. It checks the drift guard and the argument checks, then runs the script twice: first in the default streaming mode with a build, then with `--via-tmp --no-build` after changing `.env` on the server. After each run it checks that `alacrity-education/kina:<tag>` and `:latest` exist on the server, that `compose.yaml` uses that tag, has no `build:` and passes `docker compose config`, that `.env.example` matches the repository and `.env` has mode 600, that no `kina-deploy-*` is left in the server's `/tmp` and no temporary files are left locally, and that `docker ps -a` on the server is empty. It also checks that the first run created `.env` equal to `.env.example` and that the second run kept the changed `.env`. The KINA image build takes a few minutes the first time and seconds when Docker's build cache is warm. On exit the test removes the container, its image, the key and the temporary files, and restores the local `alacrity-education/kina` tags to what they were.
