#!/bin/bash
# Builds one ABI of the F-Droid recipe (docs/fdroid) inside the F-Droid buildserver image,
# replaying the `fdroid build` job of fdroiddata's .gitlab-ci.yml (same fdroidserver, JDK and
# /home/vagrant/build layout). The resulting unsigned APK is byte-identical to the one F-Droid
# builds, which is what lets F-Droid publish our signed copy (Binaries + AllowedAPKSigningKeys).
#
# Runs as root in registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie.
# Inputs: /recipe.yml, env SOURCE_COMMIT, VERSION_NAME, VERSION_CODE, BLOCK (1-4, recipe order).
# Output: /out/unsigned.apk
set -euxo pipefail

appid=com.nemu.clashfest.clash.alpha
build="$appid:$VERSION_CODE"

source /etc/profile.d/bsenv.sh
export CI_PROJECT_DIR=/builds/fdroiddata
mkdir -p "$CI_PROJECT_DIR/metadata" "$CI_PROJECT_DIR/build" "$CI_PROJECT_DIR/srclibs"
cd "$CI_PROJECT_DIR"
curl -Lsfo config.yml https://gitlab.com/fdroid/fdroiddata/-/raw/master/config.yml

# The recipe's last four blocks are one per ABI; checkupdates derives every new version from
# them the same way, so take the matching one and point it at the requested commit.
python3 - <<'EOF'
import os
import yaml

with open('/recipe.yml') as f:
    app = yaml.safe_load(f)
build = dict(app['Builds'][-4:][int(os.environ['BLOCK']) - 1])
build['versionName'] = os.environ['VERSION_NAME']
build['versionCode'] = int(os.environ['VERSION_CODE'])
build['commit'] = os.environ['SOURCE_COMMIT']
app['Builds'] = [build]
app.pop('Binaries', None)
app['CurrentVersion'] = build['versionName']
app['CurrentVersionCode'] = build['versionCode']
with open('metadata/com.nemu.clashfest.clash.alpha.yml', 'w') as f:
    yaml.safe_dump(app, f, sort_keys=False, allow_unicode=True)
with open('srclibs.txt', 'w') as f:
    f.writelines(s.split('@')[0] + '\n' for s in build.get('srclibs', []))
EOF

# Srclib definitions (e.g. the Go toolchain) come from fdroiddata, as in F-Droid's own CI.
while read -r lib; do
    curl -Lsfo "srclibs/$lib.yml" "https://gitlab.com/fdroid/fdroiddata/-/raw/master/srclibs/$lib.yml"
done < srclibs.txt

apt-get update
apt-get dist-upgrade -y

rm -rf "$fdroidserver"
git clone --shallow-since=2026-07-13 https://gitlab.com/fdroid/fdroidserver.git "$fdroidserver"
git -C "$fdroidserver" checkout -B master a35fdfddd9c66823987a410566a6101186e39c84
git -C "$fdroidserver" pull origin master --ff-only

for d in logs tmp unsigned $home_vagrant/.android $home_vagrant/.gradle $home_vagrant/metadata; do
    test -d "$d" || mkdir "$d"
    chown -R vagrant "$d"
done
ln -sfn "$CI_PROJECT_DIR/tmp" "$home_vagrant/tmp"
ln -sfn "$CI_PROJECT_DIR/srclibs" "$home_vagrant/srclibs"
sysctl fs.inotify.max_user_watches=524288 || true
export GRADLE_USER_HOME=$home_vagrant/.gradle

fdroid="sudo --preserve-env --user vagrant
    env PATH=$fdroidserver:$PATH
    env PYTHONPATH=$fdroidserver:$fdroidserver/examples
    env PYTHONUNBUFFERED=true
    env TERM=dumb
    env HOME=$home_vagrant
    fdroid"

apt-get install -y sudo openjdk-21-jdk-headless
update-alternatives --set java /usr/lib/jvm/java-21-openjdk-amd64/bin/java
cp -R "$CI_PROJECT_DIR/build" "$home_vagrant/build"
cp "metadata/$appid.yml" "$home_vagrant/metadata"
chown -R vagrant "$home_vagrant" "$CI_PROJECT_DIR"
pushd "$home_vagrant"
ln -s "$CI_PROJECT_DIR" "$home_vagrant/fdroiddata"
$fdroid fetchsrclibs "$build" --verbose
rm "$home_vagrant/fdroiddata"
(unset CI; $fdroid build --verbose --test --refresh-scanner --on-server --no-tarball "$build")
popd

mkdir -p /out
cp "$CI_PROJECT_DIR/tmp/${appid}_${VERSION_CODE}.apk" /out/unsigned.apk
