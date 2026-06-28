FROM amazoncorretto:21-alpine-jdk
WORKDIR /app
COPY target/*.jar app.jar
RUN mkdir -p /app/efs
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]