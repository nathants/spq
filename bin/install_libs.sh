#!/bin/bash
set -eux

# install jars into local ~/.m2 repository for use with leiningen

# usage: bash install_libs.sh
# usage: repos=repo-foo hash=asdf123 bash install_libs.sh

install() {
    repo=$1
    cd $(mktemp -d)
    tmp_path=$(pwd)
    git clone https://github.com/nathants/${repo}
    cd ${repo}
    hash=${hash:-$(git log --format=%h|head -n1)}
    hash=$(echo $hash|head -c7)
    sed -r -i "s:defproject ([^ ]+) .*:defproject snapshots/\1 \"master-${hash}\":" project.clj
    lein install
    cd /tmp
    rm -rf ${tmp_path}

}

repos=${repos:-"clj-schema clj-confs"}

for repo in $repos; do
    install $repo
done
