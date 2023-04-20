FROM openjdk:11-jdk-slim

WORKDIR /app

COPY src/Server.java /app

RUN javac Server.java

EXPOSE 8080

CMD ["java", "Server"]
