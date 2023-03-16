FROM openjdk:11-jdk-slim
ADD target/json-api/share/java/*.jar /app/
RUN mv /app/json-api_*.jar /app/json-api.jar
ADD json-api-app.conf /app/
WORKDIR /app
CMD ["java", "-jar", "json-api.jar", "--log-level", "DEBUG", "--log-encoder",  "json", "--config", "json-api-app.conf"]
