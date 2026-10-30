#!/bin/sh
#
# Builds every EaglerXServer jar and copies them to the repository root.
#
# The Gradle build runs inside a Java 21 container by default. This is not
# gratuitous: the host JDK is newer than Gradle 8.5 supports, and a host-side
# run fails with "BUG! exception in phase 'semantic analysis' ... Unsupported
# class file major version 70".
#
# Overrides:
#   EAGLERX_BUILD_LOCAL=1     build with the host JDK instead of Docker
#   EAGLERX_BUILD_IMAGE=...   use a different build image
#   EAGLERX_GRADLE_CACHE=...  use a different Gradle cache directory

set -e

IMAGE="${EAGLERX_BUILD_IMAGE:-ghcr.io/pterodactyl/yolks:java_21}"
GRADLE_CACHE="${EAGLERX_GRADLE_CACHE:-/tmp/gradle-cache2}"

TASKS="core:shadowJar backend-rpc-core:shadowJar rewind_v1_5:shadowJar eaglermotd:shadowJar eaglerweb:shadowJar plan:shadowJar supervisor-core:shadowJar"

BUILD_DIRS="core/build backend-rpc-core/build rewind_v1_5/build eaglermotd/build eaglerweb/build plan/build supervisor-core/build"

if [ "${EAGLERX_BUILD_LOCAL}" = "1" ] || ! command -v docker >/dev/null 2>&1; then
	echo "Building with the host JDK"
	# "sh gradlew" rather than "./gradlew": the checked-in file has no exec bit.
	sh gradlew --console=plain $TASKS
else
	echo "Building in $IMAGE"

	# A failed host-side run leaves a cache lock that blocks the container build.
	rm -rf .gradle/8.5/checksums .gradle/8.5/scripts 2>/dev/null || true

	# --entrypoint bash is required: the image's own entrypoint consumes the
	# command, which shows up as "exec: sh gradlew ... not found".
	# The chown keeps the build output usable by the invoking user instead of
	# leaving root-owned files behind in the working tree.
	docker run --rm -u 0 --entrypoint bash \
		-v "$PWD":/repo \
		-v "${GRADLE_CACHE}":/gcache \
		-w /repo \
		-e GRADLE_USER_HOME=/gcache \
		-e HOME=/root \
		-e HOST_UID="$(id -u)" \
		-e HOST_GID="$(id -g)" \
		"$IMAGE" \
		-c "sh gradlew --no-daemon --console=plain $TASKS && chown -R \"\$HOST_UID:\$HOST_GID\" $BUILD_DIRS"
fi

cp "core/build/libs/EaglerXServer.jar" "EaglerXServer.jar"
cp "backend-rpc-core/build/libs/EaglerXBackendRPC.jar" "EaglerXBackendRPC.jar"
cp "rewind_v1_5/build/libs/EaglerXRewind.jar" "EaglerXRewind.jar"
cp "eaglermotd/build/libs/EaglerMOTD.jar" "EaglerMOTD.jar"
cp "eaglerweb/build/libs/EaglerWeb.jar" "EaglerWeb.jar"
cp "plan/build/libs/EaglerXPlan.jar" "EaglerXPlan.jar"
cp "supervisor-core/build/libs/EaglerXSupervisor.jar" "EaglerXSupervisor.jar"

echo "Done."
