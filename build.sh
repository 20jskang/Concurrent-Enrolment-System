#!/bin/sh
# Optional convenience script. You don't have to use this - it just runs
# the exact javac/jar commands described in README.md. Safe to delete or
# ignore if your own build process differs.
#
# Usage: ./build.sh

set -e
echo "Compiling..."
javac *.java

echo "Packaging SubjectServer.jar..."
jar cfe SubjectServer.jar SubjectServer *.class

echo "Packaging SubjectClient.jar..."
jar cfe SubjectClient.jar SubjectClient *.class

echo "Done. Run with:"
echo "  java -jar SubjectServer.jar <port> <subject-data-file> <artificial-delay-ms>"
echo "  java -jar SubjectClient.jar <server-address> <server-port>"
