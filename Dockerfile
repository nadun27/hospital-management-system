FROM node:22.14.0-bookworm-slim AS frontend
WORKDIR /work/frontend
COPY frontend/package*.json ./
RUN npm install --global npm@10.9.2
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM maven:3.9.10-eclipse-temurin-21 AS backend
WORKDIR /work/backend
COPY backend/pom.xml ./
RUN mvn -q dependency:go-offline
COPY backend/src ./src
COPY --from=frontend /work/frontend/dist/hospital/browser ./src/main/resources/static
RUN mvn -q package

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=backend /work/backend/target/hospital-management-1.0.0.jar app.jar
COPY aiven-ca.pem /app/aiven-ca.pem
RUN keytool -importcert -noprompt \
    -alias aiven-mysql \
    -file /app/aiven-ca.pem \
    -keystore /app/aiven-truststore.p12 \
    -storetype PKCS12 \
    -storepass changeit \
    && chmod 444 /app/aiven-truststore.p12
ENV JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=/app/aiven-truststore.p12 -Djavax.net.ssl.trustStorePassword=changeit -Djavax.net.ssl.trustStoreType=PKCS12 -XX:MaxRAMPercentage=60"
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]