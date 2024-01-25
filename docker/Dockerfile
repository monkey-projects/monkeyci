FROM docker.io/alpine:latest

# Install podman, fuse overlay, qemu (for multi-platform)
RUN apk update && apk add podman fuse-overlayfs qemu-system-arm qemu-system-x86_64 qemu-arm qemu-x86_64 qemu-tools openjdk21 curl bash
# Manually install clojure cli, cause the one from alpine is no good
RUN curl -sLO https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh && chmod a+x ./linux-install.sh && ./linux-install.sh && rm ./linux-install.sh
# We'll run the whole thing as non-root user
RUN adduser -D monkeyci \
  && echo monkeyci:10000:5000 > /etc/subuid \
  && echo monkeyci:10000:5000 > /etc/subgid

VOLUME /var/lib/containers
#VOLUME /home/monkeycui/.local/share/containers

ADD --chmod=644 docker/global-containers.conf /etc/containers/containers.conf
ADD docker/local-containers.conf /home/monkeyci/.config/containers/containers.conf

# Enable fuse overlay fs
RUN sed -i -e 's|^#mount_program|mount_program|g' -e '/additionalimage.*/a "/var/lib/shared",' -e 's|^mountopt[[:space:]]*=.*$|mountopt = "nodev,fsync=0"|g' /etc/containers/storage.conf
RUN mkdir -p /var/lib/shared/overlay-images /var/lib/shared/overlay-layers /var/lib/shared/vfs-images /var/lib/shared/vfs-layers
RUN touch /var/lib/shared/overlay-images/images.lock /var/lib/shared/overlay-layers/layers.lock /var/lib/shared/vfs-images/images.lock /var/lib/shared/vfs-layers/layers.lock
# Make sure permissions are correct
RUN chown monkeyci:monkeyci -R /home/monkeyci
USER monkeyci

WORKDIR /home/monkeyci
# Config dir, can be used to specify logback config
VOLUME /home/monkeyci/config
ENTRYPOINT ["java", "-Dlogback.configurationFile=config/logback.xml", "-jar", "monkeyci.jar"]

VOLUME /home/monkeyci/work

ENV MONKEYCI_CONTAINERS_TYPE=podman
ENV MONKEYCI_WORK_DIR=/home/monkeyci/work

ADD app/target/monkeyci-standalone.jar monkeyci.jar
