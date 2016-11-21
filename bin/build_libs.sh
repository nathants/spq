#!/bin/bash
set -eux

# builds jars and pushes them to s3 to be pulled via leiningen's s3-wagons-private

# usage: bash build_libs.sh
# usage: repos=repo-foo hash=asdf123 bash build_libs.sh

# requires env vars like:
# export S3P_SNAPSHOTS_URL=s3p://$BUCKET/software/
# export S3_SNAPSHOTS_URL=s3://$BUCKET/software/snapshots

build() {
    repo=$1
    cd $(mktemp -d)
    tmp_path=$(pwd)
    git clone git@github.com:nathants/${repo}
    cd ${repo}
    name=$(echo ${repo} | cut -d- -f2)
    lein jar
    lein pom
    hash=${hash:-$(git log --format=%h|head -n1)}
    hash=$(echo $hash|head -c7)
    aws s3 cp pom.xml $S3_SNAPSHOTS_URL/${name}/master-${hash}/${name}-master-${hash}.pom
    aws s3 cp target/*.jar $S3_SNAPSHOTS_URL/${name}/master-${hash}/${name}-master-${hash}.jar
    cd /tmp
    rm -rf ${tmp_path}
}

repos=${repos:-"clj-schema clj-confs"}

for repo in $repos; do
    build $repo
done
