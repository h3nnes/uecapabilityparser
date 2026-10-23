FROM --platform=$BUILDPLATFORM gradle:9.4-jdk17-noble AS build

COPY --chown=gradle:gradle . /home/gradle/
WORKDIR /home/gradle/
# Replace project.version=staging with project.version=commit@hash
RUN git config --global --add safe.directory "*" \
    && rev=$(git rev-parse --short --verify HEAD) \
    && sed -i "s/\(project.version=\)staging$/\1commit@$rev/g" src/main/resources/application.properties \
    || true
# Build jar
RUN gradle build --no-daemon -x check


FROM eclipse-temurin:21-jre-noble AS deploy

ARG SCAT_REPO=https://github.com/fgsect/scat
# v2.1.2
ARG SCAT_TAG=646a764eb9fba4e2aa6ba0e847c1fd018853fc17
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PATH="${PATH}:/scat/bin"

RUN groupadd -r -g 2000 java && useradd -m -d /home/java/ -s /bin/bash -u 2000 -r -g java java \
    && apt-get update \
    && apt-get upgrade -y \
    && apt-get install -y git tshark python3 python3-venv --no-install-recommends \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /home/gradle/build/libs/*-all.jar /app/uecapabilityparser.jar

# Install scat
# scat >= 2.1.0 uses a hatch-vcs build backend that requires the git metadata
# of the source tree, so install from a clone instead of a tarball
RUN python3 -m venv /scat \
    && git clone --filter=blob:none ${SCAT_REPO} /tmp/scat-src \
    && git -C /tmp/scat-src checkout -q ${SCAT_TAG} \
    && /scat/bin/python -m pip install --no-cache-dir /tmp/scat-src \
    && rm -rf /tmp/scat-src

USER java
WORKDIR /home/java
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70.0 -XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -XX:ShenandoahUncommitDelay=30000 -XX:ShenandoahGuaranteedGCInterval=60000"

ENTRYPOINT [ "java", "-jar", "/app/uecapabilityparser.jar" ]
