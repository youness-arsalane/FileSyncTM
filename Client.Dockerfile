FROM openjdk:11-jdk-slim

WORKDIR /app

COPY src/Client.java /app

RUN javac Client.java

EXPOSE 8080

CMD ["java", "Client"]
