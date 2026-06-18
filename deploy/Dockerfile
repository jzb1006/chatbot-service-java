FROM eclipse-temurin:21-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY chatbot-service.jar /app/chatbot-service.jar
EXPOSE 8766
ENTRYPOINT ["java", "-jar", "/app/chatbot-service.jar"]
