FROM eclipse-temurin:21-jre

WORKDIR /app
COPY chatbot-service.jar /app/chatbot-service.jar
EXPOSE 8766
ENTRYPOINT ["java", "-jar", "/app/chatbot-service.jar"]
