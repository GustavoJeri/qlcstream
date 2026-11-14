FROM maven:3.9.12-eclipse-temurin-21-alpine AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src src
RUN mvn -q -DskipTests package

FROM python:3.13-alpine@sha256:9ef1a599f863231c3d91e941389bfdc4a2d6070557243d54df1a4e57015b960e AS file-operations

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S qlcstream && adduser -S qlcstream -G qlcstream
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar
# musl's Java provider lacks SecureDirectoryStream. Copy only the interpreter
# and encodings needed by the descriptor-relative deletion helper, without pip.
COPY --from=file-operations /usr/local/bin/python3.13 /usr/local/bin/python3
COPY --from=file-operations /usr/local/lib/libpython3.13.so.1.0 /usr/local/lib/libpython3.13.so.1.0
COPY --from=file-operations /usr/local/lib/python3.13/encodings /usr/local/lib/python3.13/encodings
COPY --from=file-operations /usr/local/lib/python3.13/LICENSE.txt /usr/local/lib/python3.13/LICENSE.txt
COPY docker/library-file-delete.py /app/library-file-delete.py
USER qlcstream
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
