FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /workspace
COPY pom.xml .
COPY chatbot-common/pom.xml chatbot-common/pom.xml
COPY chatbot-hermes-adapter/pom.xml chatbot-hermes-adapter/pom.xml
COPY chatbot-speech-api/pom.xml chatbot-speech-api/pom.xml
COPY chatbot-device-gateway/pom.xml chatbot-device-gateway/pom.xml
COPY chatbot-voice-gateway/pom.xml chatbot-voice-gateway/pom.xml
COPY chatbot-bootstrap/pom.xml chatbot-bootstrap/pom.xml
RUN mvn -B -pl chatbot-bootstrap -am dependency:go-offline

COPY . .
RUN mvn -B -pl chatbot-bootstrap -am -DskipTests package

FROM eclipse-temurin:21-jre

WORKDIR /app
COPY --from=build /workspace/chatbot-bootstrap/target/chatbot-bootstrap-0.0.1-SNAPSHOT.jar /app/chatbot-service.jar
EXPOSE 8766
ENTRYPOINT ["java", "-jar", "/app/chatbot-service.jar"]
