# Backend image: builds the Spring Boot jar, then runs it on a small Java runtime as a non-root user.
# Built by deploy/docker-compose.yml (context = this folder).

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
# dependencies first, so they stay cached when only the code changes
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B -DskipTests package && cp target/*.jar /src/app.jar

FROM eclipse-temurin:25-jre
RUN groupadd -r crm && useradd -r -g crm -u 10001 crm
WORKDIR /app
COPY --from=build /src/app.jar app.jar
USER crm
# 768 MB heap: plenty for this app (it stayed near 440 MB under load); restart cleanly if memory ever runs out
ENV JAVA_OPTS="-Xmx768m -XX:+ExitOnOutOfMemoryError"
EXPOSE 8081
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
