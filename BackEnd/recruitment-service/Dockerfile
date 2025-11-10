FROM maven:3.8-eclipse-temurin-17-alpine AS build
WORKDIR /build

# Build common-library
COPY ../common-library/pom.xml ./common-library/pom.xml
COPY ../common-library/src ./common-library/src
RUN cd common-library && mvn clean install -DskipTests -B

# Build recruitment-service
WORKDIR /build/service
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests -B

# Stage 2: Run
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /build/service/target/*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-jar", "app.jar"]