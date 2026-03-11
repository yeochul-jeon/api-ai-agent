FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app

COPY build/libs/api-ai-agent-*.jar app.jar

EXPOSE 3000

ENV JAVA_OPTS="-XX:+UseZGC -Xmx512m -Dspring.threads.virtual.enabled=true"

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar app.jar"]
