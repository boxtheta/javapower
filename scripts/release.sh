#!/bin/sh
# Build, sign and bundle a release for Maven Central, and optionally upload it.
#
#   scripts/release.sh <version> [--upload]
#
# 1. On the build server (scripts/remote.sh, workspace "release"): gradle check, then publish every
#    module at <version> into a staging directory in Maven layout.
# 2. Here: fetch that directory and sign every artifact with the release key. The private key
#    never leaves this machine.
# 3. Zip it into build/javapower-<version>-central.zip, the bundle Central's portal accepts.
# 4. With --upload: send the bundle to the portal as a deployment that still has to be released by
#    hand at https://central.sonatype.com/publishing/deployments (publishing cannot be undone).
#    Needs a portal user token in CENTRAL_TOKEN_USER and CENTRAL_TOKEN_PASSWORD.
#
# JP_RELEASE_KEY overrides the signing key. The build server is JP_HOST, as for remote.sh.
set -eu
KEY=${JP_RELEASE_KEY:-6E501D76959FFF2632BDEE5B8BE9EC452CDD18D3}
VERSION=${1:?usage: release.sh <version> [--upload]}
UPLOAD=${2:-}
case $VERSION in
    *SNAPSHOT*) echo "Maven Central takes no snapshots: $VERSION" >&2; exit 1 ;;
esac
if [ -n "$UPLOAD" ] && [ "$UPLOAD" != --upload ]; then
    echo "unknown option: $UPLOAD" >&2; exit 1
fi
if [ "$UPLOAD" = --upload ]; then
    : "${CENTRAL_TOKEN_USER:?set CENTRAL_TOKEN_USER and CENTRAL_TOKEN_PASSWORD}"
    : "${CENTRAL_TOKEN_PASSWORD:?set CENTRAL_TOKEN_USER and CENTRAL_TOKEN_PASSWORD}"
fi
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
# The server is a local detail: JP_HOST, from the environment or the untracked local.env.
if [ -f "$ROOT/local.env" ]; then . "$ROOT/local.env"; fi
HOST=${JP_HOST:?set JP_HOST, or put JP_HOST=<build server> in local.env at the repository root}
if [ -n "$(git status --porcelain)" ]; then
    echo "working tree is not clean; a release is built from a commit" >&2; exit 1
fi
gpg --batch --list-secret-keys "$KEY" >/dev/null
# gpg asks for the key's passphrase on this terminal (run the script from one).
if [ -t 0 ]; then GPG_TTY=$(tty); export GPG_TTY; fi

STAGING=build/central-staging
BUNDLE=build/javapower-$VERSION-central.zip
scripts/remote.sh release "rm -rf $STAGING && gradle -q -PreleaseVersion=$VERSION check publishAllPublicationsToStagingRepository"
rm -rf "$STAGING" "$BUNDLE"
mkdir -p "$STAGING"
rsync -az "$HOST:javapower/work/release/$STAGING/" "$STAGING/"

# Central wants no repository metadata, and a signature next to every file but the checksums.
find "$STAGING" -name 'maven-metadata.xml*' -delete
find "$STAGING" -type f ! -name '*.md5' ! -name '*.sha1' ! -name '*.sha256' ! -name '*.sha512' ! -name '*.asc' |
while read -r f; do
    gpg --batch --yes --local-user "$KEY" --armor --detach-sign "$f"
    gpg --batch --verify "$f.asc" "$f" 2>/dev/null
done
(cd "$STAGING" && zip -q -r -X "$ROOT/$BUNDLE" .)
echo "bundle: $BUNDLE ($(find "$STAGING" -name '*.pom' | wc -l | tr -d ' ') modules, commit $(git rev-parse --short HEAD))"

if [ "$UPLOAD" = --upload ]; then
    AUTH=$(printf '%s:%s' "$CENTRAL_TOKEN_USER" "$CENTRAL_TOKEN_PASSWORD" | base64 | tr -d '\n')
    ID=$(curl -sS --fail-with-body -X POST -H "Authorization: Bearer $AUTH" -F "bundle=@$BUNDLE" \
        "https://central.sonatype.com/api/v1/publisher/upload?name=javapower-$VERSION&publishingType=USER_MANAGED")
    echo "uploaded, deployment $ID: review and release it at https://central.sonatype.com/publishing/deployments"
fi
