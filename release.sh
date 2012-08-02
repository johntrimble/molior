#!/usr/bin/env bash
git checkout develop
git branch release-$1
git checkout release-$1
mvn -Pmeltmedia release:clean release:prepare release:perform
mvn -Pmeltmedia release:clean
git checkout develop
git merge --no-ff release-$1
git branch -d release-$1
git checkout master
git merge --no-ff v$1
git push
git push --tags v$1
git checkout develop
