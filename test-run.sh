#!/bin/sh

cd lib
clojure -X:jar:install
cd ../app
clojure -X:jar:install
clojure -X:jar:uber
cd ..
java -jar app/target/monkeyci-standalone.jar build -p test
