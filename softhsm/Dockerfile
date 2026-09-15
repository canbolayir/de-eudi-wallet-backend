FROM eclipse-temurin:25-jre-noble@sha256:d120abd9d8d7dec94520ce974ece62d0e4eed8576eb00bbc84e6128307ab48ef

COPY softhsm/init-slot.sh /softhsm/init-slot.sh
COPY softhsm/generated/hsm-keys/ /softhsm/hsm-keys/
ENV KEYS_DIR=/softhsm/hsm-keys
RUN apt-get update \
    && apt-get install -y --no-install-recommends softhsm2 opensc curl \
    && usermod -aG softhsm ubuntu && chmod g+rw /etc/softhsm/softhsm2.conf && chmod g+rwx /var/lib/softhsm \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

ARG APP_JAR=wallet-backend.jar
COPY build/libs/${APP_JAR} /app/main.jar

COPY --chmod=755 softhsm/entrypoint.sh /docker-entrypoint.sh

ENTRYPOINT ["/docker-entrypoint.sh"]

CMD ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/main.jar"]

EXPOSE 8080
